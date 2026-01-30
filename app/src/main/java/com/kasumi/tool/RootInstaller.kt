package com.kasumi.tool

import java.io.File

object RootInstaller {
    private val SAFE_FILENAME_REGEX = Regex("[^A-Za-z0-9._-]")
    private val SESSION_ID_REGEX_1 = Regex("\\[(\\d+)\\]")
    private val SESSION_ID_REGEX_2 = Regex("session\\s+(\\d+)", RegexOption.IGNORE_CASE)

    fun isDeviceRooted(): Boolean {
        return try {
            // Kiểm tra su thực sự hoạt động thay vì chỉ kiểm tra sự tồn tại của binary
            val p = ProcessBuilder("su", "-c", "id")
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText()
            val exit = p.waitFor()
            exit == 0 && out.contains("uid=0")
        } catch (_: Exception) {
            false
        }
    }

    // Fallback: cài đơn APK bằng session theo đường dẫn thay vì direct pm install PATH
    private fun installApkByPathSession(file: File): Pair<Boolean, String> {
        val safeName = file.name.replace(SAFE_FILENAME_REGEX, "_")
        val tmpPath = "/data/local/tmp/$safeName"
        return try {
            // Chuẩn bị thư mục tạm và copy file
            ProcessBuilder("su", "-c", "mkdir -p /data/local/tmp && chmod 777 /data/local/tmp")
                .redirectErrorStream(true)
                .start()
                .waitFor()
            var p = ProcessBuilder("su", "-c", "cat > $tmpPath")
                .redirectErrorStream(true)
                .start()
            file.inputStream().use { input ->
                p.outputStream.use { out ->
                    input.copyTo(out)
                    out.flush()
                }
            }
            p.waitFor()
            ProcessBuilder("su", "-c", "chmod 644 $tmpPath").start().waitFor()

            // Tạo session và ghi theo đường dẫn
            p = ProcessBuilder("su", "-c", "pm install-create -r")
                .redirectErrorStream(true)
                .start()
            val outCreate = p.inputStream.bufferedReader().readText()
            val exitCreate = p.waitFor()
            if (exitCreate != 0) {
                ProcessBuilder("su", "-c", "rm -f $tmpPath").start()
                return false to outCreate
            }
            val sessionId = SESSION_ID_REGEX_1.find(outCreate)?.groupValues?.get(1)
                ?: SESSION_ID_REGEX_2.find(outCreate)?.groupValues?.get(1)
                ?: run {
                    ProcessBuilder("su", "-c", "rm -f $tmpPath").start();
                    return false to outCreate
                }

            val baseName = file.name.replace(SAFE_FILENAME_REGEX, "_")
            p = ProcessBuilder("su", "-c", "pm install-write $sessionId $baseName $tmpPath")
                .redirectErrorStream(true)
                .start()
            val outWrite = p.inputStream.bufferedReader().readText()
            val exitWrite = p.waitFor()
            if (exitWrite != 0) {
                ProcessBuilder("su", "-c", "rm -f $tmpPath").start()
                return false to outWrite
            }

            p = ProcessBuilder("su", "-c", "pm install-commit $sessionId")
                .redirectErrorStream(true)
                .start()
            val outCommit = p.inputStream.bufferedReader().readText()
            val exitCommit = p.waitFor()
            ProcessBuilder("su", "-c", "rm -f $tmpPath").start()
            (exitCommit == 0) to outCommit
        } catch (e: Exception) {
            try { ProcessBuilder("su", "-c", "rm -f $tmpPath").start() } catch (_: Exception) {}
            false to (e.message ?: "unknown error")
        }
    }

    // Thông tin môi trường root để ghi log hỗ trợ chẩn đoán
    data class RootEnv(
        val rooted: Boolean,
        val uid0: Boolean,
        val provider: String,          // Magisk | KernelSU | SuperSU | Unknown | None
        val suPath: String?,
        val suVersion: String?,        // su -v / --version / -V
        val magiskVersionName: String?,
        val magiskVersionCode: String?,
        val kernelSuVersion: String?   // từ su -v hoặc getprop
    )

    fun probeRootEnv(): RootEnv {
        fun runAndRead(vararg cmd: String): Pair<Int, String> = try {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            val exit = p.waitFor()
            exit to out
        } catch (e: Exception) { -1 to (e.message ?: "") }

        fun runSuAndRead(cmd: String): Pair<Int, String> = try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            val exit = p.waitFor()
            exit to out
        } catch (e: Exception) { -1 to (e.message ?: "") }

        // su path
        val (whichExit, whichOut) = runAndRead("which", "su")
        val suPath = if (whichExit == 0) whichOut.lineSequence().firstOrNull()?.trim()?.takeUnless { it.isNullOrBlank() } else null

        // uid0 check via su -c id
        val (idExit, idOut) = runSuAndRead("id")
        val uid0 = (idExit == 0 && idOut.contains("uid=0"))

        // su version (try several flags)
        var suVersion: String? = null
        for (args in listOf(arrayOf("su", "-v"), arrayOf("su", "--version"), arrayOf("su", "-V"))) {
            val (e, o) = runAndRead(*args)
            if (e == 0 && o.isNotBlank()) { suVersion = o.lines().first().trim(); break }
        }

        // Detect Magisk
        var magiskVName: String? = null
        var magiskVCode: String? = null
        run {
            val (e1, o1) = runSuAndRead("magisk -v")
            if (e1 == 0 && o1.isNotBlank()) magiskVName = o1.lines().first().trim()
            val (e2, o2) = runSuAndRead("magisk -V")
            if (e2 == 0 && o2.isNotBlank()) magiskVCode = o2.lines().first().trim()
            if (magiskVName == null) {
                val (eg1, og1) = runSuAndRead("getprop ro.magisk.version")
                if (eg1 == 0 && og1.isNotBlank()) magiskVName = og1.lines().first().trim()
            }
            if (magiskVCode == null) {
                val (eg2, og2) = runSuAndRead("getprop ro.magisk.version.code")
                if (eg2 == 0 && og2.isNotBlank()) magiskVCode = og2.lines().first().trim()
            }
            // Sanitize error-like outputs (tránh hiển thị 'sh: magisk: not found' như version)
            fun clean(s: String?): String? {
                if (s == null) return null
                val t = s.trim()
                val tl = t.lowercase()
                return if (tl.startsWith("sh:") || tl.contains("not found") || tl.contains("inaccessible")) null else t
            }
            magiskVName = clean(magiskVName)
            magiskVCode = clean(magiskVCode)
        }

        // Detect KernelSU heuristics
        var kernelSu: String? = null
        if (suVersion?.contains("KernelSU", ignoreCase = true) == true) {
            kernelSu = suVersion
        } else {
            val (ek1, ok1) = runSuAndRead("getprop ksu.version")
            if (ek1 == 0 && ok1.isNotBlank()) kernelSu = ok1.lines().first().trim()
            if (kernelSu == null) {
                val (ek2, ok2) = runSuAndRead("getprop ro.kernel.su")
                if (ek2 == 0 && ok2.isNotBlank()) kernelSu = ok2.lines().first().trim()
            }
        }

        val rooted = uid0
        val provider = when {
            rooted && (magiskVName != null || magiskVCode != null) -> "Magisk"
            rooted && kernelSu != null -> "KernelSU"
            rooted && (suVersion?.contains("super", ignoreCase = true) == true) -> "SuperSU"
            rooted -> "Unknown"
            else -> "None"
        }

        return RootEnv(
            rooted = rooted,
            uid0 = uid0,
            provider = provider,
            suPath = suPath,
            suVersion = suVersion,
            magiskVersionName = magiskVName,
            magiskVersionCode = magiskVCode,
            kernelSuVersion = kernelSu
        )
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
        return try {
            val tmpDir = "/data/local/tmp/splits"
            ProcessBuilder("su", "-c", "rm -rf $tmpDir && mkdir -p $tmpDir && chmod 777 $tmpDir")
                .redirectErrorStream(true)
                .start()
                .waitFor()
            val paths = mutableListOf<Pair<File, String>>()
            for (f in files) {
                val safe = f.name.replace(SAFE_FILENAME_REGEX, "_")
                val remote = "$tmpDir/$safe"
                var p = ProcessBuilder("su", "-c", "cat > $remote")
                    .redirectErrorStream(true)
                    .start()
                f.inputStream().use { input ->
                    p.outputStream.use { out ->
                        input.copyTo(out)
                        out.flush()
                    }
                }
                p.waitFor()
                ProcessBuilder("su", "-c", "chmod 644 $remote").start().waitFor()
                paths.add(f to remote)
            }
            var p = ProcessBuilder("su", "-c", "pm install-create -r")
                .redirectErrorStream(true)
                .start()
            val outCreate = p.inputStream.bufferedReader().readText()
            val exitCreate = p.waitFor()
            if (exitCreate != 0) {
                ProcessBuilder("su", "-c", "rm -rf $tmpDir").start()
                return false to outCreate
            }
            val sessionId = SESSION_ID_REGEX_1.find(outCreate)?.groupValues?.get(1)
                ?: SESSION_ID_REGEX_2.find(outCreate)?.groupValues?.get(1)
            if (sessionId.isNullOrBlank()) {
                ProcessBuilder("su", "-c", "rm -rf $tmpDir").start()
                return false to outCreate
            }
            for ((f, remote) in paths) {
                val safeName = f.name.replace(SAFE_FILENAME_REGEX, "_")
                p = ProcessBuilder("su", "-c", "pm install-write $sessionId $safeName $remote")
                    .redirectErrorStream(true)
                    .start()
                val outW = p.inputStream.bufferedReader().readText()
                val exitW = p.waitFor()
                if (exitW != 0) {
                    ProcessBuilder("su", "-c", "rm -rf $tmpDir").start()
                    return false to outW
                }
            }
            p = ProcessBuilder("su", "-c", "pm install-commit $sessionId")
                .redirectErrorStream(true)
                .start()
            val outCommit = p.inputStream.bufferedReader().readText()
            val exitCommit = p.waitFor()
            ProcessBuilder("su", "-c", "rm -rf $tmpDir").start()
            (exitCommit == 0) to outCommit
        } catch (e: Exception) {
            false to (e.message ?: "unknown error")
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
            for (f in files) {
                val size = f.length()
                val safeName = f.name.replace(SAFE_FILENAME_REGEX, "_")
                p = ProcessBuilder("su", "-c", "pm install-write -S $size $sessionId $safeName -")
                    .redirectErrorStream(true)
                    .start()
                f.inputStream().use { input ->
                    p.outputStream.use { out ->
                        input.copyTo(out)
                        out.flush()
                    }
                }
                val exitW = p.waitFor()
                val outW = p.inputStream.bufferedReader().readText()
                if (exitW != 0) return false to outW
            }
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

    fun uninstall(packageName: String): Pair<Boolean, String> {
        return try {
            var p = ProcessBuilder("su", "-c", "pm uninstall $packageName")
                .redirectErrorStream(true)
                .start()
            val out1 = p.inputStream.bufferedReader().readText()
            val exit1 = p.waitFor()
            if (exit1 == 0) return true to out1
            // fallback --user 0
            p = ProcessBuilder("su", "-c", "pm uninstall --user 0 $packageName")
                .redirectErrorStream(true)
                .start()
            val out2 = p.inputStream.bufferedReader().readText()
            val exit2 = p.waitFor()
            (exit2 == 0) to out2
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
            file.inputStream().use { input ->
                p.outputStream.use { out ->
                    input.copyTo(out)
                    out.flush()
                }
            }
            val exit = p.waitFor()
            val output = p.inputStream.bufferedReader().readText()
            (exit == 0) to output
        } catch (e: Exception) {
            false to (e.message ?: "unknown error")
        }
    }

    private fun copyToTmpAndInstall(file: File): Pair<Boolean, String> {
        val safeName = file.name.replace(SAFE_FILENAME_REGEX, "_")
        val tmpPath = "/data/local/tmp/$safeName"
        return try {
            // Đảm bảo thư mục tạm tồn tại để tránh EPIPE do 'cat' thoát sớm
            ProcessBuilder("su", "-c", "mkdir -p /data/local/tmp && chmod 777 /data/local/tmp")
                .redirectErrorStream(true)
                .start()
                .waitFor()
            // Copy via su using cat > tmp
            var p = ProcessBuilder("su", "-c", "cat > $tmpPath")
                .redirectErrorStream(true)
                .start()
            file.inputStream().use { input ->
                p.outputStream.use { out ->
                    input.copyTo(out)
                    out.flush()
                }
            }
            p.waitFor()
            // chmod 644
            p = ProcessBuilder("su", "-c", "chmod 644 $tmpPath")
                .redirectErrorStream(true)
                .start()
            p.waitFor()
            // Try pm install -r from path
            p = ProcessBuilder("su", "-c", "pm install -r $tmpPath")
                .redirectErrorStream(true)
                .start()
            var output = p.inputStream.bufferedReader().readText()
            var exit = p.waitFor()
            if (exit != 0) {
                // fallback --user 0
                p = ProcessBuilder("su", "-c", "pm install -r --user 0 $tmpPath")
                    .redirectErrorStream(true)
                    .start()
                output = p.inputStream.bufferedReader().readText()
                exit = p.waitFor()
            }
            // cleanup
            ProcessBuilder("su", "-c", "rm -f $tmpPath").start()
            (exit == 0) to output
        } catch (e: Exception) {
            try { ProcessBuilder("su", "-c", "rm -f $tmpPath").start() } catch (_: Exception) {}
            false to (e.message ?: "unknown error")
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
            for (f in files) {
                val safe = f.name.replace(SAFE_FILENAME_REGEX, "_")
                val remote = "$tmpDir/$safe"
                var p = ProcessBuilder("su", "-c", "cat > $remote")
                    .redirectErrorStream(true)
                    .start()
                f.inputStream().use { input ->
                    p.outputStream.use { out ->
                        input.copyTo(out)
                        out.flush()
                    }
                }
                p.waitFor()
                ProcessBuilder("su", "-c", "chmod 644 $remote").start().waitFor()
                paths.add(remote)
            }
            val cmd = listOf("su", "-c", "pm install-multiple -r ${paths.joinToString(" ")}")
            val p = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText()
            val exit = p.waitFor()
            // cleanup
            ProcessBuilder("su", "-c", "rm -rf $tmpDir").start()
            (exit == 0) to out
        } catch (e: Exception) {
            false to (e.message ?: "unknown error")
        }
    }
}
