package com.kasumi.tool

import java.io.File
import java.io.BufferedWriter
import java.io.BufferedReader

object RootInstaller {
    private fun sanitizeFilename(name: String): String {
        val sb = StringBuilder(name.length)
        for (c in name) {
            if ((c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') ||
                c == '.' || c == '_' || c == '-') {
                sb.append(c)
            } else {
                sb.append('_')
            }
        }
        return sb.toString()
    }

    private val SESSION_ID_REGEX_1 = Regex("\\[(\\d+)\\]")
    private val SESSION_ID_REGEX_2 = Regex("session\\s+(\\d+)", RegexOption.IGNORE_CASE)

    private val rootedCache: Boolean by lazy {
        try {
            // Kiểm tra su thực sự hoạt động thay vì chỉ kiểm tra sự tồn tại của binary
            val p = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start().apply { outputStream.close() }
            val out = p.inputStream.bufferedReader().readText()
            val procExit = p.waitFor()
            procExit == 0 && out.contains("uid=0")
        } catch (_: Exception) {
            false
        }
    }

    fun isDeviceRooted(): Boolean = rootedCache

    // Fallback: cài đơn APK bằng session theo đường dẫn thay vì direct pm install PATH
    private fun installApkByPathSession(file: File): Pair<Boolean, String> {
        val safeName = sanitizeFilename(file.name)
        val tmpPath = "/data/local/tmp/$safeName"
        val shell = ShellSession()
        return try {
            // Chuẩn bị thư mục tạm và copy file
            shell.exec("mkdir -p /data/local/tmp && chmod 777 /data/local/tmp")

            // Cat binary data separately
            val p = ProcessBuilder("su", "-c", "cat > $tmpPath")
                .redirectErrorStream(true)
                .start()
            val buffer = ByteArray(65536)
            file.inputStream().use { input ->
                p.outputStream.use { out ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } >= 0) {
                        out.write(buffer, 0, bytesRead)
                    }
                    out.flush()
                }
            }
            p.waitFor()

            shell.exec("chmod 644 $tmpPath")

            // Tạo session và ghi theo đường dẫn
            val (exitCreate, outCreate) = shell.exec("pm install-create -r")
            if (exitCreate != 0) {
                shell.exec("rm -f $tmpPath")
                return false to outCreate
            }
            val sessionId = SESSION_ID_REGEX_1.find(outCreate)?.groupValues?.get(1)
                ?: SESSION_ID_REGEX_2.find(outCreate)?.groupValues?.get(1)
                ?: run {
                    shell.exec("rm -f $tmpPath")
                    return false to outCreate
                }

            val (exitWrite, outWrite) = shell.exec("pm install-write $sessionId $safeName $tmpPath")
            if (exitWrite != 0) {
                shell.exec("rm -f $tmpPath")
                return false to outWrite
            }

            val (exitCommit, outCommit) = shell.exec("pm install-commit $sessionId")
            shell.exec("rm -f $tmpPath")
            (exitCommit == 0) to outCommit
        } catch (e: Exception) {
            try { shell.exec("rm -f $tmpPath") } catch (_: Exception) {}
            false to (e.message ?: "unknown error")
        } finally {
            shell.close()
        }
    }

    private class ShellSession(command: String = "su") : AutoCloseable {
        private val process: Process = ProcessBuilder(command).redirectErrorStream(true).start()
        private val writer: BufferedWriter = process.outputStream.bufferedWriter()
        private val reader: BufferedReader = process.inputStream.bufferedReader()
        private val markerBase = java.util.UUID.randomUUID().toString().replace("-", "")
        private var cmdCount = 0

        fun exec(cmd: String): Pair<Int, String> {
            val marker = "${markerBase}_${cmdCount++}"
            try {
                writer.write("$cmd\n")
                writer.write("echo $marker:$?\n")
                writer.flush()
            } catch (e: Exception) {
                return -1 to (e.message ?: "write error")
            }

            val output = StringBuilder()
            var exitCode = -1
            while (true) {
                val line = try {
                    reader.readLine()
                } catch (e: Exception) {
                    null
                }
                if (line == null) break

                if (line.contains(marker)) {
                    val parts = line.split("$marker:")
                    if (parts.size >= 2) {
                        exitCode = parts[1].trim().toIntOrNull() ?: -1
                        if (parts[0].isNotBlank()) {
                             output.append(parts[0]).append("\n")
                        }
                        break
                    }
                }
                output.append(line).append("\n")
            }
            return exitCode to output.toString().trim()
        }

        override fun close() {
            try {
                writer.write("ex" + "it\n")
                writer.flush()
                process.waitFor()
            } catch (e: Exception) {
                process.destroy()
            }
        }
    }

    fun installApk(file: File): Pair<Boolean, String> {
        // 1) Ưu tiên: copy sang /data/local/tmp và cài từ đường dẫn để tránh EPIPE
        val copy = copyToTmpAndInstall(file)
        if (copy.first) return copy
        // 1b) Thử theo cơ chế session bằng đường dẫn (một số ROM chặn direct pm install PATH)
        val byPathSession = installApkByPathSession(file)
        if (byPathSession.first) return byPathSession
        // 2) Thử stream stdin (có thể lỗi EPIPE trên một số ROM)
        val stream = streamInstall(file)
        return if (stream.first) stream else false to "copy:${copy.second}; stream:${stream.second}"
    }

    // Cài đặt nhiều APK (split) cho file .apks qua root
    fun installApks(files: List<File>): Pair<Boolean, String> {
        val inputs = files.filter { it.exists() && it.isFile }
        if (inputs.isEmpty()) return false to "no apk files"
        // 1) Ưu tiên: copy sang /data/local/tmp rồi install-create + install-write (đường dẫn) + commit
        val byPath = installApksByPath(inputs)
        if (byPath.first) return byPath
        // 2) Thử: install-create + install-write (stream stdin)
        val byStream = installApksByStream(inputs)
        if (byStream.first) return byStream
        // 3) Cuối cùng: pm install-multiple -r
        val fb = fallbackInstallMultiple(inputs)
        return if (fb.first) fb else false to "path:${byPath.second}; stream:${byStream.second}; fallback:${fb.second}"
    }

    private fun installApksByPath(files: List<File>): Pair<Boolean, String> {
        val shell = ShellSession()
        return try {
            val tmpDir = "/data/local/tmp/splits"
            shell.exec("rm -rf $tmpDir && mkdir -p $tmpDir && chmod 777 $tmpDir")

            val paths = mutableListOf<Pair<File, String>>()

            val p = ProcessBuilder("su", "-c", "tar -C $tmpDir -xf -")
                .redirectErrorStream(true)
                .start()

            p.outputStream.use { out ->
                TarUtil.streamFiles(files, out) { f ->
                    val safe = sanitizeFilename(f.name)
                    val remote = "$tmpDir/$safe"
                    paths.add(f to remote)
                    safe
                }
            }
            val tarExit = p.waitFor()
            if (tarExit != 0) {
                 val errorOutput = p.inputStream.bufferedReader().readText()
                 shell.exec("rm -rf $tmpDir")
                 return false to "tar extraction failed (procExit=$tarExit): $errorOutput"
            }
            shell.exec("chmod 644 $tmpDir/*")

            val (exitCreate, outCreate) = shell.exec("pm install-create -r")
            if (exitCreate != 0) {
                shell.exec("rm -rf $tmpDir")
                return false to outCreate
            }
            val sessionId = SESSION_ID_REGEX_1.find(outCreate)?.groupValues?.get(1)
                ?: SESSION_ID_REGEX_2.find(outCreate)?.groupValues?.get(1)
            if (sessionId.isNullOrBlank()) {
                shell.exec("rm -rf $tmpDir")
                return false to outCreate
            }
            if (paths.isNotEmpty()) {
                val writeCmd = paths.joinToString(" && ") { (_, remote) ->
                    val safeName = remote.substringAfterLast('/')
                    "echo '>>> $safeName' && pm install-write $sessionId $safeName $remote"
                }
                val (exitW, outW) = shell.exec(writeCmd)
                if (exitW != 0) {
                    shell.exec("rm -rf $tmpDir")
                    return false to outW
                }
            }

            val (exitCommit, outCommit) = shell.exec("pm install-commit $sessionId")
            shell.exec("rm -rf $tmpDir")
            (exitCommit == 0) to outCommit
        } catch (e: Exception) {
            false to (e.message ?: "unknown error")
        } finally {
            shell.close()
        }
    }

    private fun installApksByStream(files: List<File>): Pair<Boolean, String> {
        return try {
            var p = ProcessBuilder("su", "-c", "pm install-create -r")
                .redirectErrorStream(true)
                .start()
            val outCreate = p.inputStream.bufferedReader().readText()
            val exitCreate = p.waitFor()
            if (exitCreate != 0) return false to outCreate
            val sessionId = SESSION_ID_REGEX_1.find(outCreate)?.groupValues?.get(1)
                ?: SESSION_ID_REGEX_2.find(outCreate)?.groupValues?.get(1)
            if (sessionId.isNullOrBlank()) return false to outCreate

            // Giả định quan trọng: pm install-write -S <size> đọc chính xác <size> byte
            // từ stdin mà không buffer lố sang dữ liệu của file tiếp theo. Hành vi này
            // ổn định trên AOSP nhưng có thể lỗi trên một số ROM tùy chỉnh cũ.
            val cmdBuilder = StringBuilder()
            for (i in files.indices) {
                if (i > 0) cmdBuilder.append(" && ")
                val f = files[i]
                val size = f.length()
                val safeName = sanitizeFilename(f.name)
                cmdBuilder.append("pm install-write -S $size $sessionId $safeName -")
            }

            p = ProcessBuilder("su", "-c", cmdBuilder.toString())
                .redirectErrorStream(true)
                .start()

            var writeError: Exception? = null
            try {
                val buffer = ByteArray(65536)
                p.outputStream.use { out ->
                    for (f in files) {
                        f.inputStream().use { input ->
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } >= 0) {
                                out.write(buffer, 0, bytesRead)
                            }
                        }
                    }
                    out.flush()
                }
            } catch (e: Exception) {
                // Có thể bị Broken pipe nếu pm install-write dừng sớm (vd lỗi apk).
                // Không throw ngay để đọc nốt thông báo lỗi từ pm.
                writeError = e
            }

            val outW = p.inputStream.bufferedReader().readText()
            val exitW = p.waitFor()

            if (exitW != 0) return false to outW
            if (writeError != null) return false to "write error: ${writeError.message}; pm: $outW"
            p = ProcessBuilder("su", "-c", "pm install-commit $sessionId")
                .redirectErrorStream(true)
                .start()
            val outCommit = p.inputStream.bufferedReader().readText()
            val exitCommit = p.waitFor()
            (exitCommit == 0) to outCommit
        } catch (e: Exception) {
            false to (e.message ?: "unknown error")
        }
    }

    private fun streamInstall(file: File): Pair<Boolean, String> {
        return try {
            val size = file.length()
            val p = ProcessBuilder("su", "-c", "pm install -r -S $size")
                .redirectErrorStream(true)
                .start()
            val buffer = ByteArray(65536)
            file.inputStream().use { input ->
                p.outputStream.use { out ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } >= 0) {
                        out.write(buffer, 0, bytesRead)
                    }
                    out.flush()
                }
            }
            val procExit = p.waitFor()
            val output = p.inputStream.bufferedReader().readText()
            (procExit == 0) to output
        } catch (e: Exception) {
            false to (e.message ?: "unknown error")
        }
    }

    private fun copyToTmpAndInstall(file: File): Pair<Boolean, String> {
        val safeName = sanitizeFilename(file.name)
        val tmpPath = "/data/local/tmp/$safeName"
        val shell = ShellSession()
        return try {
            // Đảm bảo thư mục tạm tồn tại để tránh EPIPE do 'cat' thoát sớm
            shell.exec("mkdir -p /data/local/tmp && chmod 777 /data/local/tmp")

            // Copy via su using cat > tmp
            val p = ProcessBuilder("su", "-c", "cat > $tmpPath")
                .redirectErrorStream(true)
                .start()
            val buffer = ByteArray(65536)
            file.inputStream().use { input ->
                p.outputStream.use { out ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } >= 0) {
                        out.write(buffer, 0, bytesRead)
                    }
                    out.flush()
                }
            }
            p.waitFor()

            // chmod 644
            shell.exec("chmod 644 $tmpPath")

            // Try pm install -r from path
            var (procExit, output) = shell.exec("pm install -r $tmpPath")

            if (procExit != 0) {
                // fallback --user 0
                val res = shell.exec("pm install -r --user 0 $tmpPath")
                procExit = res.first
                output = res.second
            }
            // cleanup
            shell.exec("rm -f $tmpPath")
            (procExit == 0) to output
        } catch (e: Exception) {
            try { shell.exec("rm -f $tmpPath") } catch (_: Exception) {}
            false to (e.message ?: "unknown error")
        } finally {
            shell.close()
        }
    }

    private fun fallbackInstallMultiple(files: List<File>): Pair<Boolean, String> {
        return try {
            val tmpDir = "/data/local/tmp/splits"
            ProcessBuilder("su", "-c", "rm -rf $tmpDir && mkdir -p $tmpDir && chmod 777 $tmpDir")
                .redirectErrorStream(true)
                .start()
                .waitFor()
            val paths = mutableListOf<String>()
            val pExt = ProcessBuilder("su", "-c", "tar -C $tmpDir -xf -")
                .redirectErrorStream(true)
                .start()
            pExt.outputStream.use { out ->
                TarUtil.streamFiles(files, out) { f ->
                    val safe = sanitizeFilename(f.name)
                    val remote = "$tmpDir/$safe"
                    paths.add(remote)
                    safe
                }
            }
            pExt.waitFor()
            if (paths.isNotEmpty()) {
                ProcessBuilder("su", "-c", "chmod 644 $tmpDir/*").start().waitFor()
            }
            val cmd = listOf("su", "-c", "pm install-multiple -r ${paths.joinToString(" ")}")
            val p = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText()
            val procExit = p.waitFor()
            // cleanup
            ProcessBuilder("su", "-c", "rm -rf $tmpDir").start()
            (procExit == 0) to out
        } catch (e: Exception) {
            false to (e.message ?: "unknown error")
        }
    }
}
