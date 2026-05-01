package com.kasumi.tool

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CompletableFuture

/**
 * Quản lý đăng nhập Roblox bằng cookie .ROBLOSECURITY thông qua quyền root.
 *
 * Hỗ trợ cả 2 package:
 *  - `com.roblox.client` (bản global)
 *  - `com.roblox.client.vnggames` (bản VNG cho Việt Nam)
 *
 * Cookie được lưu trong SQLite database tại
 * `/data/data/<package>/app_webview/Default/Cookies`. Module này hỗ trợ:
 *
 * 1. Trích xuất cookie hiện tại từ database (chỉ đọc được khi cookie được lưu
 *    dạng plaintext trong cột `value`; trên một số phiên bản Chromium WebView
 *    cookie có thể được mã hóa trong `encrypted_value`).
 * 2. Chèn cookie vào database để đăng nhập trực tiếp vào tài khoản Roblox mà
 *    không cần tài khoản/mật khẩu.
 *
 * Triển khai: dùng `su` để **copy** file DB (cùng `-wal`/`-shm` nếu có) sang
 * `cacheDir` của ứng dụng (chmod 666), sau đó đọc/ghi bằng API
 * [SQLiteDatabase] có sẵn trong Android — không phụ thuộc vào lệnh `sqlite3`
 * trên thiết bị. Sau khi sửa, copy ngược lại + restore quyền (chown/chmod 660/restorecon).
 */
object RobloxLoginManager {

    const val ROBLOX_PACKAGE = "com.roblox.client"
    const val ROBLOX_VNG_PACKAGE = "com.roblox.client.vnggames"
    const val COOKIE_NAME = ".ROBLOSECURITY"

    private const val CACHE_DB_NAME = "roblox_cookies.db"
    private const val CACHE_WAL_NAME = "roblox_cookies.db-wal"
    private const val CACHE_SHM_NAME = "roblox_cookies.db-shm"

    private const val COOKIE_PREFIX = "_|WARNING:"
    private const val COOKIE_MIN_LENGTH = 100

    /** Đường dẫn `/data/data/<package>` của một package Roblox cụ thể. */
    private fun appDataPath(pkg: String) = "/data/data/$pkg"

    /** Thư mục chứa Cookies database của WebView. */
    private fun cookiesDir(pkg: String) = "${appDataPath(pkg)}/app_webview/Default"

    /** Đường dẫn đầy đủ tới file Cookies SQLite. */
    private fun cookiesDbPath(pkg: String) = "${cookiesDir(pkg)}/Cookies"

    data class StepResult(
        val name: String,
        val success: Boolean,
        val exitCode: Int,
        val output: String,
        val error: String
    )

    data class Outcome(
        val success: Boolean,
        val message: String,
        val steps: List<StepResult>,
        val cookie: String? = null
    )

    /** Kết quả thực thi `su -c <cmd>`. */
    private data class RawResult(val exitCode: Int, val output: String, val error: String)

    /**
     * Thực thi một lệnh shell với quyền root.
     *
     * Đọc stdout/stderr **song song** (CompletableFuture) để tránh deadlock khi
     * pipe buffer của một stream bị đầy (~64 KB trên Linux) trong khi stream
     * còn lại chưa được tiêu thụ.
     */
    private fun executeAsRoot(command: String): RawResult {
        return try {
            val process = ProcessBuilder("su", "-c", command).start()
            val outputFuture = CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader().readText()
            }
            val errorText = process.errorStream.bufferedReader().readText()
            val outputText = outputFuture.get()
            val exitCode = process.waitFor()
            RawResult(exitCode, outputText.trim(), errorText.trim())
        } catch (e: Exception) {
            RawResult(-1, "", e.message ?: "Unknown error")
        }
    }

    private fun runStep(name: String, command: String): StepResult {
        val r = executeAsRoot(command)
        return StepResult(
            name = name,
            success = r.exitCode == 0,
            exitCode = r.exitCode,
            output = r.output,
            error = r.error
        )
    }

    private fun isPackageInstalled(context: Context, pkg: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    pkg,
                    PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(pkg, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Trả về package Roblox đang được cài trên thiết bị, ưu tiên bản global
     * (`com.roblox.client`) nếu cả 2 đều có. `null` nếu không cài bản nào.
     */
    fun detectActivePackage(context: Context): String? {
        if (isPackageInstalled(context, ROBLOX_PACKAGE)) return ROBLOX_PACKAGE
        if (isPackageInstalled(context, ROBLOX_VNG_PACKAGE)) return ROBLOX_VNG_PACKAGE
        return null
    }

    /** Kiểm tra ít nhất một bản Roblox đã được cài đặt hay chưa. */
    fun isRobloxInstalled(context: Context): Boolean = detectActivePackage(context) != null

    /**
     * Kiểm tra cookie có nhìn như một cookie .ROBLOSECURITY hợp lệ hay không.
     *
     * Validate rất lỏng để người dùng có thể dán nguyên cookie từ bất kỳ nguồn nào.
     * Việc ghi vào DB đã parameterized qua [ContentValues] / `whereArgs` nên không cần
     * loại trừ ký tự "nguy hiểm" cho SQL/shell.
     */
    fun isCookieFormatValid(cookie: String): Boolean {
        val trimmed = cookie.trim()
        if (trimmed.length < COOKIE_MIN_LENGTH) return false
        // Cookie .ROBLOSECURITY luôn bắt đầu bằng "_|WARNING:" theo format của Roblox
        if (!trimmed.startsWith(COOKIE_PREFIX)) return false
        // Không cho phép ký tự điều khiển / xuống dòng để tránh người dùng vô tình
        // dán cả "name=value\n" của trình duyệt.
        if (trimmed.any { it == '\n' || it == '\r' || it == '\t' || it == ' ' }) return false
        // Cookie .ROBLOSECURITY của Roblox luôn là ASCII printable (base64-url +
        // dấu `_|.:-`) — reject mọi byte ngoài dải 33..126 để tránh chuỗi
        // giả mạo hoặc bị mã hóa sai (UTF-8 multi-byte, cắt dán nhầm).
        if (trimmed.any { it.code < 33 || it.code > 126 }) return false
        return true
    }

    private fun cleanupCache(context: Context) {
        File(context.cacheDir, CACHE_DB_NAME).delete()
        File(context.cacheDir, CACHE_WAL_NAME).delete()
        File(context.cacheDir, CACHE_SHM_NAME).delete()
    }

    /**
     * Sau khi copy DB sang cacheDir, ta hay gặp lỗi
     * `SQLITE_IOERR_BEGIN_ATOMIC` (code 7434) trên Android vì SQLite cố dùng
     * tính năng F2FS atomic write khi DB đang ở **WAL mode** (byte 18–19 của
     * file header = 2). Trên cacheDir của app khác (khác uid với DB gốc),
     * F2FS atomic write thuờng bị từ chối.
     *
     * Ghi đè byte 18 và 19 về `1` (legacy / rollback journal) để SQLite
     * bỏ qua WAL hoàn toàn trong bản copy này. Việc này không ảnh hưởng
     * đến DB gốc của Roblox (sẽ được khởi tạo lại WAL khi Roblox mở).
     */
    private fun forceLegacyJournalMode(dbFile: File) {
        if (dbFile.length() < 100) return
        RandomAccessFile(dbFile, "rw").use { raf ->
            raf.seek(18L)
            // 18 = file format write version, 19 = file format read version.
            // 1 = legacy/rollback, 2 = WAL.
            raf.writeByte(1)
            raf.writeByte(1)
        }
    }

    /**
     * Trích xuất cookie .ROBLOSECURITY từ database WebView của Roblox.
     *
     * Trả về [Outcome.cookie] nếu thành công. Nếu cookie được mã hóa, [Outcome.message]
     * sẽ thông báo và trả về `null` cookie.
     */
    fun extractCookie(context: Context): Outcome {
        val steps = mutableListOf<StepResult>()

        // 1. Xác định package Roblox đang cài (com.roblox.client hoặc VNG)
        val pkg = detectActivePackage(context) ?: return Outcome(
            success = false,
            message = "Không tìm thấy ứng dụng Roblox đã cài (com.roblox.client hoặc com.roblox.client.vnggames).",
            steps = steps
        )
        steps += StepResult("Phát hiện Roblox", true, 0, pkg, "")

        val cookiesDir = cookiesDir(pkg)
        val cookiesDb = cookiesDbPath(pkg)

        // 2. Force-stop Roblox để đảm bảo DB ổn định khi copy
        val stop = runStep("Tắt Roblox", "am force-stop $pkg")
        steps += stop
        // Không return-fail ở đây: Roblox có thể đang không chạy

        // 3. Kiểm tra database tồn tại
        val checkDb = runStep(
            "Kiểm tra database cookie",
            "test -f $cookiesDb && echo 'EXISTS' || echo 'MISSING'"
        )
        steps += checkDb
        if (!checkDb.success || !checkDb.output.contains("EXISTS")) {
            return Outcome(
                success = false,
                message = "Không tìm thấy database cookie của Roblox ($pkg). Hãy mở ứng dụng Roblox ít nhất 1 lần để khởi tạo dữ liệu.",
                steps = steps
            )
        }

        // 4. Copy DB (và -wal nếu có) sang cacheDir để đọc bằng SQLiteDatabase.
        // Không copy `-shm` vì nó chứa state đồng bộ inode-specific của Chromium
        // — SQLite sẽ tự tạo lại.
        cleanupCache(context)
        val cacheDb = File(context.cacheDir, CACHE_DB_NAME)
        val cacheWal = File(context.cacheDir, CACHE_WAL_NAME)

        val copyCmd = listOf(
            "cp '$cookiesDb' '${cacheDb.absolutePath}'",
            "chmod 666 '${cacheDb.absolutePath}'",
            "if [ -f '$cookiesDir/Cookies-wal' ]; then cp '$cookiesDir/Cookies-wal' '${cacheWal.absolutePath}' && chmod 666 '${cacheWal.absolutePath}'; fi"
        ).joinToString(" && ")
        val copy = runStep("Copy DB ra cache app", copyCmd)
        steps += copy
        if (!copy.success) {
            cleanupCache(context)
            return Outcome(
                success = false,
                message = "Không copy được database: ${copy.error.ifBlank { copy.output }}",
                steps = steps
            )
        }

        // 5. Đọc cookie bằng SQLiteDatabase API (không dùng shell sqlite3).
        //
        // Chiến lược 2 bước:
        //   a) Thử mở với WAL còn nguyên (READONLY) — SQLite sẽ tự merge WAL +
        //      main DB để đưa ra các cookie mới nhất. Do `am force-stop` gửi
        //      SIGKILL nên WAL có thể chứa cookie chưa checkpoint.
        //   b) Nếu (a) thất bại vì F2FS atomic-write (`SQLITE_IOERR_BEGIN_ATOMIC`,
        //      code 7434), fallback: ép byte 18-19 của header về 1 (legacy /
        //      rollback journal) + xóa -wal trong cache, mở lại. Trường hợp
        //      này có thể mất cookie chưa checkpoint từ WAL, nhưng vẫn lấy
        //      được dữ liệu trong main DB.
        return try {
            queryCookie(cacheDb, steps)
        } catch (e: Exception) {
            // Fallback: ép legacy + bỏ WAL rồi thử lại
            steps += StepResult(
                "Mở DB (lần 1)",
                false,
                -1,
                "",
                "${e.message ?: "unknown"} — fallback sang legacy mode"
            )
            try {
                forceLegacyJournalMode(cacheDb)
                cacheWal.delete()
                steps += StepResult("Ép legacy journal mode", true, 0, "OK", "")
                queryCookie(cacheDb, steps)
            } catch (e2: Exception) {
                steps += StepResult("Mở DB (lần 2)", false, -1, "", e2.message ?: "")
                Outcome(
                    success = false,
                    message = "Lỗi mở database SQLite: ${e2.message}",
                    steps = steps
                )
            }
        } finally {
            cleanupCache(context)
        }
    }

    /**
     * Truy vấn cookie từ file SQLite [cacheDb] ở cacheDir. Trách nhiệm của
     * caller là đảm bảo file sẵn sàng để mở (đã copy về, chỉnh quyền,…).
     * Phương thức này không cleanup file cache.
     */
    private fun queryCookie(cacheDb: File, steps: MutableList<StepResult>): Outcome {
        SQLiteDatabase.openDatabase(
            cacheDb.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        ).use { db ->
            return db.query(
                /* table = */ "cookies",
                /* columns = */ arrayOf("value", "encrypted_value"),
                /* selection = */ "name = ? AND host_key LIKE ?",
                /* selectionArgs = */ arrayOf(COOKIE_NAME, "%roblox.com"),
                /* groupBy = */ null,
                /* having = */ null,
                /* orderBy = */ "creation_utc DESC",
                /* limit = */ "1"
            ).use { c ->
                if (!c.moveToFirst()) {
                    steps += StepResult(
                        "Truy vấn cookie",
                        true,
                        0,
                        "Không tìm thấy cookie .ROBLOSECURITY",
                        ""
                    )
                    return@use Outcome(
                        success = false,
                        message = "Không tìm thấy cookie .ROBLOSECURITY trong database. Hãy đăng nhập Roblox trước rồi thử lại.",
                        steps = steps
                    )
                }
                val value = c.getString(0).orEmpty()
                val encVal = try {
                    c.getBlob(1)
                } catch (_: Exception) {
                    null
                }
                if (value.isNotBlank()) {
                    steps += StepResult("Truy vấn cookie", true, 0, "OK", "")
                    Outcome(
                        success = true,
                        message = "Đã lấy cookie .ROBLOSECURITY thành công.",
                        steps = steps,
                        cookie = value
                    )
                } else if (encVal != null && encVal.isNotEmpty()) {
                    steps += StepResult(
                        "Truy vấn cookie",
                        true,
                        0,
                        "encrypted_value = ${encVal.size} bytes",
                        ""
                    )
                    Outcome(
                        success = false,
                        message = "Cookie đang được WebView mã hóa (encrypted_value). Phiên bản WebView/Android này không cho phép đọc cookie trực tiếp.",
                        steps = steps
                    )
                } else {
                    steps += StepResult("Truy vấn cookie", true, 0, "Empty", "")
                    Outcome(
                        success = false,
                        message = "Cookie tồn tại nhưng giá trị rỗng. Có thể tài khoản chưa đăng nhập.",
                        steps = steps
                    )
                }
            }
        }
    }

    /**
     * Chèn cookie vào database để đăng nhập trực tiếp vào tài khoản Roblox.
     *
     * Quy trình:
     *  1. Phát hiện package Roblox (global hoặc VNG).
     *  2. Force-stop Roblox.
     *  3. Xóa các file `Cookies-journal/wal/shm` để tránh xung đột writer.
     *  4. Copy file `Cookies` sang `cacheDir` (chmod 666 để app đọc được).
     *  5. Mở DB bằng [SQLiteDatabase], `DELETE WHERE host_key LIKE '%roblox.com'`,
     *     sau đó `INSERT` cookie mới với host `.roblox.com` (dùng [ContentValues],
     *     không cần escape SQL thủ công).
     *  6. Copy DB ngược về thư mục Roblox + sửa quyền (chown UID/GID, chmod 660, restorecon).
     */
    fun injectCookie(context: Context, cookie: String): Outcome {
        val trimmed = cookie.trim()
        if (!isCookieFormatValid(trimmed)) {
            return Outcome(
                success = false,
                message = "Cookie không hợp lệ. Phải bắt đầu bằng `_|WARNING:` và không chứa khoảng trắng/xuống dòng.",
                steps = emptyList()
            )
        }

        val steps = mutableListOf<StepResult>()

        val pkg = detectActivePackage(context) ?: return Outcome(
            success = false,
            message = "Không tìm thấy ứng dụng Roblox đã cài (com.roblox.client hoặc com.roblox.client.vnggames).",
            steps = steps
        )
        steps += StepResult("Phát hiện Roblox", true, 0, pkg, "")

        val appData = appDataPath(pkg)
        val cookiesDir = cookiesDir(pkg)
        val cookiesDb = cookiesDbPath(pkg)

        // 1. Force-stop và dọn các file WAL/SHM/journal trên thư mục Roblox.
        // Không fail-fast nếu force-stop trả exit != 0 (Roblox có thể chưa chạy);
        // các bước rm -f vẫn idempotent nên chạy tiếp được.
        val cleanupSteps = listOf(
            "Tắt Roblox" to "am force-stop $pkg",
            "Xóa Cookies-journal" to "rm -f $cookiesDir/Cookies-journal",
            "Xóa Cookies-wal" to "rm -f $cookiesDir/Cookies-wal",
            "Xóa Cookies-shm" to "rm -f $cookiesDir/Cookies-shm"
        )
        cleanupSteps.forEachIndexed { idx, (name, cmd) ->
            val r = runStep(name, cmd)
            steps += r
            // idx == 0 là `am force-stop`: bỏ qua lỗi (đồng nhất với extractCookie).
            if (!r.success && idx > 0) {
                return Outcome(
                    success = false,
                    message = "Bước \"$name\" thất bại (exit ${r.exitCode}): ${r.error.ifBlank { r.output }}",
                    steps = steps
                )
            }
        }

        // 2. Đảm bảo database tồn tại
        val checkDb = runStep(
            "Kiểm tra database cookie",
            "test -f $cookiesDb && echo OK || echo MISSING"
        )
        steps += checkDb
        if (!checkDb.success || !checkDb.output.contains("OK")) {
            return Outcome(
                success = false,
                message = "Không tìm thấy database cookie của Roblox ($pkg). Hãy mở ứng dụng Roblox ít nhất 1 lần rồi thử lại.",
                steps = steps
            )
        }

        // 3. Copy file DB sang cacheDir của app
        cleanupCache(context)
        val cacheDb = File(context.cacheDir, CACHE_DB_NAME)
        val copy = runStep(
            "Copy DB ra cache app",
            "cp '$cookiesDb' '${cacheDb.absolutePath}' && chmod 666 '${cacheDb.absolutePath}'"
        )
        steps += copy
        if (!copy.success) {
            cleanupCache(context)
            return Outcome(
                success = false,
                message = "Không copy được database: ${copy.error.ifBlank { copy.output }}",
                steps = steps
            )
        }

        // 4. Ép cache DB sang legacy journal mode để tránh F2FS atomic write
        // (`SQLITE_IOERR_BEGIN_ATOMIC` / code 7434). Đây là thao tác ghép
        // 2 byte header trên cache copy, không ảnh hưởng DB gốc.
        try {
            forceLegacyJournalMode(cacheDb)
        } catch (e: Exception) {
            steps += StepResult("Ép legacy journal mode", false, -1, "", e.message ?: "")
            cleanupCache(context)
            return Outcome(
                success = false,
                message = "Không điều chỉnh được file header DB: ${e.message}",
                steps = steps
            )
        }
        steps += StepResult("Ép legacy journal mode", true, 0, "OK", "")

        // 5. Probe encryption mode: nếu WebView của Roblox đang lưu cookie
        // dạng encrypted_value (Chromium 112+ với OSCrypt enabled), việc
        // INSERT plaintext vào cột `value` sẽ bị WebView bỏ qua khi đọc
        // → login giả (DB ghi OK nhưng tài khoản không đổi). Cảnh báo
        // sớm thay vì báo thành công rồi user mở Roblox vẫn thấy account cũ.
        try {
            SQLiteDatabase.openDatabase(
                cacheDb.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY
            ).use { db ->
                db.rawQuery(
                    "SELECT COUNT(*) FROM cookies WHERE host_key LIKE ? AND length(encrypted_value) > 0",
                    arrayOf("%roblox.com")
                ).use { c ->
                    val encryptedCount = if (c.moveToFirst()) c.getInt(0) else 0
                    if (encryptedCount > 0) {
                        steps += StepResult(
                            "Probe encryption mode",
                            true,
                            0,
                            "Phát hiện $encryptedCount cookie có encrypted_value",
                            ""
                        )
                        cleanupCache(context)
                        return Outcome(
                            success = false,
                            message = "WebView của Roblox đang dùng mã hóa cookie (encrypted_value). Inject plaintext vào cột `value` sẽ bị WebView bỏ qua khi đọc → login sẽ thất bại. Tính năng này không hoạt động trên thiết bị/ROM hiện tại.",
                            steps = steps
                        )
                    }
                    steps += StepResult(
                        "Probe encryption mode",
                        true,
                        0,
                        "Plaintext mode (encrypted_value rỗng)",
                        ""
                    )
                }
            }
        } catch (e: Exception) {
            steps += StepResult("Probe encryption mode", false, -1, "", e.message ?: "")
            cleanupCache(context)
            return Outcome(
                success = false,
                message = "Lỗi mở database SQLite (probe): ${e.message}",
                steps = steps
            )
        }

        // 6. Mở DB bằng SQLiteDatabase và sửa cookie qua API Kotlin
        try {
            SQLiteDatabase.openDatabase(
                cacheDb.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE
            ).use { db ->
                // Đảm bảo connection thực sự ở rollback-journal mode + tắt
                // synchronous full để không khởi động atomic-write trong commit.
                db.execSQL("PRAGMA journal_mode = DELETE")
                // Giữ mức NORMAL: SQLite vẫn fsync sau commit để cacheDb được
                // lưu xuống đĩa trước khi bước copy ngược đọc lại. Việc bỏ
                // batch atomic-write đã được giải quyết bằng forceLegacyJournalMode
                // (ép header về 1) — không cần hạ mức xuống OFF.
                db.execSQL("PRAGMA synchronous = NORMAL")

                // Đọc schema thực tế của bảng `cookies`. Schema Chromium thay đổi
                // theo phiên bản WebView (v12 → v23+); ta chỉ insert những cột
                // mà schema thực sự có, tránh INSERT fail vì cột không tồn tại
                // (DB cũ) hoặc thiếu cột bắt buộc không default (DB mới).
                val schemaCols = listCookieColumns(db)
                if (schemaCols.isEmpty()) {
                    throw IllegalStateException("Không đọc được schema của bảng cookies")
                }
                steps += StepResult(
                    "Đọc schema cookies",
                    true,
                    0,
                    "${schemaCols.size} cột: ${schemaCols.joinToString(",")}",
                    ""
                )

                db.beginTransaction()
                try {
                    val deleted = db.delete(
                        /* table = */ "cookies",
                        /* whereClause = */ "host_key LIKE ?",
                        /* whereArgs = */ arrayOf("%roblox.com")
                    )

                    val cv = buildCookieValues(trimmed, schemaCols)
                    db.insertOrThrow("cookies", null, cv)
                    db.setTransactionSuccessful()

                    steps += StepResult(
                        name = "Ghi cookie vào DB",
                        success = true,
                        exitCode = 0,
                        output = "Đã xóa $deleted cookie cũ + chèn 1 cookie mới (${cv.size()} cột)",
                        error = ""
                    )
                } finally {
                    db.endTransaction()
                }
            }
        } catch (e: Exception) {
            steps += StepResult(
                name = "Ghi cookie vào DB",
                success = false,
                exitCode = -1,
                output = "",
                error = e.message ?: "Unknown error"
            )
            cleanupCache(context)
            return Outcome(
                success = false,
                message = "Lỗi ghi database SQLite: ${e.message}",
                steps = steps
            )
        }

        // 7. Copy DB ngược về Roblox + restore quyền.
        //
        // Hai điểm quan trọng:
        //  - Ghi qua file tạm `${cookiesDb}.tmp` rồi `mv` để có atomic
        //    rename (syscall `rename()`). Nếu process bị kill giữa chừng,
        //    file `Cookies` gốc vẫn nguyến vẹn — tránh corrupt SQLite.
        //  - Dùng UID/GID dạng số (`%u`/`%g`) thay vì tên symbolic (`%U`)
        //    — trên một số ROM Android, tên user dạng `u0_a123` không có
        //    trong /etc/passwd nên `chown <name>` sẽ fail.
        val restoreCmd = listOf(
            // Dọn file tmp leftover từ lần retry trước (nếu có) — file nằm
            // trong /data/data/<pkg>/app_webview/Default/ thuộc uid Roblox,
            // chỉ root mới xóa được, mà cleanupCache() chỉ dọn cacheDir của app.
            "rm -f '${cookiesDb}.tmp'",
            "cp '${cacheDb.absolutePath}' '${cookiesDb}.tmp'",
            "mv '${cookiesDb}.tmp' '$cookiesDb'",
            "APP_UID=\$(stat -c '%u' $appData) && APP_GID=\$(stat -c '%g' $appData) && chown \$APP_UID:\$APP_GID $cookiesDb",
            "chmod 660 $cookiesDb",
            "restorecon $cookiesDb || true"
        ).joinToString(" && ")
        val restore = runStep("Đẩy DB lại Roblox + sửa quyền", restoreCmd)
        steps += restore
        cleanupCache(context)
        if (!restore.success) {
            return Outcome(
                success = false,
                message = "Không đẩy được DB ngược về Roblox: ${restore.error.ifBlank { restore.output }}",
                steps = steps
            )
        }

        return Outcome(
            success = true,
            message = "Đã đăng nhập thành công! Mở Roblox ($pkg) để vào thẳng tài khoản.",
            steps = steps
        )
    }

    /**
     * Trả về tập tên cột hiện có trong bảng `cookies` của Chromium WebView.
     *
     * Schema Chromium thay đổi theo thời gian:
     *  - v12 (Chrome <80, ROM cũ): 15 cột, không có `top_frame_site_key`,
     *    `source_port`, `last_update_utc`.
     *  - v15+ (Chrome 92+): thêm `top_frame_site_key`, `source_port`.
     *  - v17+ (Chrome 105+): thêm `last_update_utc`.
     *  - v20+ (Chrome 112+): thêm `source_type`.
     *  - v23+ (Chrome 118+): thêm `has_cross_site_ancestor`.
     */
    private fun listCookieColumns(db: SQLiteDatabase): Set<String> {
        val cols = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info(cookies)", null).use { c ->
            val nameIdx = c.getColumnIndex("name")
            if (nameIdx < 0) return cols
            while (c.moveToNext()) {
                cols.add(c.getString(nameIdx))
            }
        }
        return cols
    }

    /**
     * Build [ContentValues] cho 1 dòng cookie `.ROBLOSECURITY` với host `.roblox.com`.
     *
     * Chỉ set những cột có mặt trong [schemaCols] — để tương thích cả schema
     * Chromium cũ (v12: không có `top_frame_site_key`/`source_port`/...) và mới
     * (v23+: có thêm `source_type`, `has_cross_site_ancestor`).
     */
    private fun buildCookieValues(cookieValue: String, schemaCols: Set<String>): ContentValues {
        // Chromium dùng microseconds kể từ epoch Windows (1601-01-01).
        // Khoảng cách giữa 1601-01-01 và 1970-01-01 là 11644473600 giây.
        val unixEpochOffsetMicros = 11644473600L * 1_000_000L
        val nowMicros = System.currentTimeMillis() * 1_000L + unixEpochOffsetMicros
        // Cookie .ROBLOSECURITY mặc định có hiệu lực ~1 năm; lấy 400 ngày
        // (giới hạn tối đa của Chromium đối với cookie persistent).
        val expiresUtc = nowMicros + 400L * 86400L * 1_000_000L

        val cv = ContentValues()
        // Cột bắt buộc (NOT NULL không default) trong mọi schema:
        if ("creation_utc" in schemaCols) cv.put("creation_utc", nowMicros)
        if ("host_key" in schemaCols) cv.put("host_key", ".roblox.com")
        if ("name" in schemaCols) cv.put("name", COOKIE_NAME)
        if ("value" in schemaCols) cv.put("value", cookieValue)
        if ("path" in schemaCols) cv.put("path", "/")
        if ("expires_utc" in schemaCols) cv.put("expires_utc", expiresUtc)
        if ("is_secure" in schemaCols) cv.put("is_secure", 1)
        if ("is_httponly" in schemaCols) cv.put("is_httponly", 1)
        if ("last_access_utc" in schemaCols) cv.put("last_access_utc", nowMicros)
        // Có DEFAULT trong mọi phiên bản nhưng vẫn nên set để đúng ngữ nghĩa:
        if ("has_expires" in schemaCols) cv.put("has_expires", 1)
        if ("is_persistent" in schemaCols) cv.put("is_persistent", 1)
        if ("priority" in schemaCols) cv.put("priority", 1)
        if ("encrypted_value" in schemaCols) cv.put("encrypted_value", ByteArray(0))
        if ("samesite" in schemaCols) cv.put("samesite", -1)
        if ("source_scheme" in schemaCols) cv.put("source_scheme", 2)
        // Chromium v15+:
        if ("top_frame_site_key" in schemaCols) cv.put("top_frame_site_key", "")
        if ("source_port" in schemaCols) cv.put("source_port", 443)
        // Chromium v17+:
        if ("last_update_utc" in schemaCols) cv.put("last_update_utc", nowMicros)
        // Chromium v20+ (Chrome 112+):
        if ("source_type" in schemaCols) cv.put("source_type", 0)
        // Chromium v23+ (Chrome 118+):
        if ("has_cross_site_ancestor" in schemaCols) cv.put("has_cross_site_ancestor", 0)
        return cv
    }
}
