package com.kasumi.tool

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import java.io.File

/**
 * Quản lý đăng nhập Roblox bằng cookie .ROBLOSECURITY thông qua quyền root.
 *
 * Cookie được lưu trong SQLite database tại
 * `/data/data/com.roblox.client/app_webview/Default/Cookies`. Module này hỗ trợ:
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

    private const val APP_DATA = "/data/data/com.roblox.client"
    private const val COOKIES_DIR = "/data/data/com.roblox.client/app_webview/Default"
    private const val COOKIES_DB = "/data/data/com.roblox.client/app_webview/Default/Cookies"

    private const val CACHE_DB_NAME = "roblox_cookies.db"
    private const val CACHE_WAL_NAME = "roblox_cookies.db-wal"
    private const val CACHE_SHM_NAME = "roblox_cookies.db-shm"

    // Mẫu chuỗi cookie .ROBLOSECURITY hợp lệ (token bắt đầu bằng "_|WARNING:")
    private val COOKIE_REGEX = Regex("^[A-Za-z0-9_+\\-=/.|:]+\\.[A-Za-z0-9_+\\-=/.|:]+$")

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

    private fun executeAsRoot(command: String): RawResult {
        return try {
            val process = ProcessBuilder("su", "-c", command).start()
            val output = process.inputStream.bufferedReader().readText()
            val error = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            RawResult(exitCode, output.trim(), error.trim())
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

    /** Kiểm tra ứng dụng Roblox (com.roblox.client) đã được cài đặt hay chưa. */
    fun isRobloxInstalled(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    ROBLOX_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(ROBLOX_PACKAGE, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** Kiểm tra cookie có hợp lệ về mặt định dạng (không gồm ký tự nguy hiểm). */
    fun isCookieFormatValid(cookie: String): Boolean {
        val trimmed = cookie.trim()
        if (trimmed.length < 100) return false
        // Cookie hợp lệ phải bắt đầu bằng tiền tố cảnh báo của Roblox
        if (!trimmed.startsWith("_|WARNING:")) return false
        return COOKIE_REGEX.matches(trimmed)
    }

    private fun cleanupCache(context: Context) {
        File(context.cacheDir, CACHE_DB_NAME).delete()
        File(context.cacheDir, CACHE_WAL_NAME).delete()
        File(context.cacheDir, CACHE_SHM_NAME).delete()
    }

    /**
     * Trích xuất cookie .ROBLOSECURITY từ database WebView của Roblox.
     *
     * Trả về [Outcome.cookie] nếu thành công. Nếu cookie được mã hóa, [Outcome.message]
     * sẽ thông báo và trả về `null` cookie.
     */
    fun extractCookie(context: Context): Outcome {
        val steps = mutableListOf<StepResult>()

        // 1. Force-stop Roblox để đảm bảo DB ổn định khi copy
        val stop = runStep("Tắt Roblox", "am force-stop $ROBLOX_PACKAGE")
        steps += stop
        // Không return-fail ở đây: Roblox có thể đang không chạy

        // 2. Kiểm tra database tồn tại
        val checkDb = runStep(
            "Kiểm tra database cookie",
            "test -f $COOKIES_DB && echo 'EXISTS' || echo 'MISSING'"
        )
        steps += checkDb
        if (!checkDb.success || !checkDb.output.contains("EXISTS")) {
            return Outcome(
                success = false,
                message = "Không tìm thấy database cookie của Roblox. Hãy mở ứng dụng Roblox ít nhất 1 lần để khởi tạo dữ liệu.",
                steps = steps
            )
        }

        // 3. Copy DB (và -wal/-shm nếu có) sang cacheDir để đọc bằng SQLiteDatabase
        cleanupCache(context)
        val cacheDb = File(context.cacheDir, CACHE_DB_NAME)
        val cacheWal = File(context.cacheDir, CACHE_WAL_NAME)
        val cacheShm = File(context.cacheDir, CACHE_SHM_NAME)

        val copyCmd = listOf(
            "cp '$COOKIES_DB' '${cacheDb.absolutePath}'",
            "chmod 666 '${cacheDb.absolutePath}'",
            "if [ -f '$COOKIES_DIR/Cookies-wal' ]; then cp '$COOKIES_DIR/Cookies-wal' '${cacheWal.absolutePath}' && chmod 666 '${cacheWal.absolutePath}'; fi",
            "if [ -f '$COOKIES_DIR/Cookies-shm' ]; then cp '$COOKIES_DIR/Cookies-shm' '${cacheShm.absolutePath}' && chmod 666 '${cacheShm.absolutePath}'; fi"
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

        // 4. Đọc cookie bằng SQLiteDatabase API (không dùng shell sqlite3)
        return try {
            // Mở read-write để SQLite có thể checkpoint WAL nếu có
            SQLiteDatabase.openDatabase(
                cacheDb.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE
            ).use { db ->
                db.query(
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
        } catch (e: Exception) {
            steps += StepResult("Mở DB SQLite", false, -1, "", e.message ?: "Unknown error")
            Outcome(
                success = false,
                message = "Lỗi mở database SQLite: ${e.message}",
                steps = steps
            )
        } finally {
            cleanupCache(context)
        }
    }

    /**
     * Chèn cookie vào database để đăng nhập trực tiếp vào tài khoản Roblox.
     *
     * Quy trình:
     *  1. Force-stop Roblox.
     *  2. Xóa các file `Cookies-journal/wal/shm` để tránh xung đột writer.
     *  3. Copy file `Cookies` sang `cacheDir` (chmod 666 để app đọc được).
     *  4. Mở DB bằng [SQLiteDatabase], `DELETE WHERE host_key LIKE '%roblox.com'`,
     *     sau đó `INSERT` cookie mới với host `.roblox.com` (dùng [ContentValues],
     *     không cần escape SQL thủ công).
     *  5. Copy DB ngược về thư mục Roblox + sửa quyền (chown, chmod 660, restorecon).
     */
    fun injectCookie(context: Context, cookie: String): Outcome {
        val trimmed = cookie.trim()
        if (!isCookieFormatValid(trimmed)) {
            return Outcome(
                success = false,
                message = "Cookie không hợp lệ. Cookie phải bắt đầu bằng `_|WARNING:` và chỉ chứa các ký tự an toàn.",
                steps = emptyList()
            )
        }

        val steps = mutableListOf<StepResult>()

        // 1. Force-stop và dọn các file WAL/SHM/journal trên thư mục Roblox
        val cleanupSteps = listOf(
            "Tắt Roblox" to "am force-stop $ROBLOX_PACKAGE",
            "Xóa Cookies-journal" to "rm -f $COOKIES_DIR/Cookies-journal",
            "Xóa Cookies-wal" to "rm -f $COOKIES_DIR/Cookies-wal",
            "Xóa Cookies-shm" to "rm -f $COOKIES_DIR/Cookies-shm"
        )
        for ((name, cmd) in cleanupSteps) {
            val r = runStep(name, cmd)
            steps += r
            if (!r.success) {
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
            "test -f $COOKIES_DB && echo OK || echo MISSING"
        )
        steps += checkDb
        if (!checkDb.output.contains("OK")) {
            return Outcome(
                success = false,
                message = "Không tìm thấy database cookie của Roblox. Hãy mở ứng dụng Roblox ít nhất 1 lần rồi thử lại.",
                steps = steps
            )
        }

        // 3. Copy file DB sang cacheDir của app
        cleanupCache(context)
        val cacheDb = File(context.cacheDir, CACHE_DB_NAME)
        val copy = runStep(
            "Copy DB ra cache app",
            "cp '$COOKIES_DB' '${cacheDb.absolutePath}' && chmod 666 '${cacheDb.absolutePath}'"
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

        // 4. Mở DB bằng SQLiteDatabase và sửa cookie qua API Kotlin
        try {
            SQLiteDatabase.openDatabase(
                cacheDb.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE
            ).use { db ->
                db.beginTransaction()
                try {
                    val deleted = db.delete(
                        /* table = */ "cookies",
                        /* whereClause = */ "host_key LIKE ?",
                        /* whereArgs = */ arrayOf("%roblox.com")
                    )

                    val cv = buildCookieValues(trimmed)
                    db.insertOrThrow("cookies", null, cv)
                    db.setTransactionSuccessful()

                    steps += StepResult(
                        name = "Ghi cookie vào DB",
                        success = true,
                        exitCode = 0,
                        output = "Đã xóa $deleted cookie cũ + chèn 1 cookie mới",
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

        // 5. Copy DB ngược về Roblox + restore quyền
        val restoreCmd = listOf(
            "cp '${cacheDb.absolutePath}' '$COOKIES_DB'",
            "APP_USER=\$(stat -c '%U' $APP_DATA) && chown \$APP_USER:\$APP_USER $COOKIES_DB",
            "chmod 660 $COOKIES_DB",
            "restorecon $COOKIES_DB || true"
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
            message = "Đã đăng nhập thành công! Mở Roblox để vào thẳng tài khoản.",
            steps = steps
        )
    }

    /** Build [ContentValues] cho 1 dòng cookie `.ROBLOSECURITY` với host `.roblox.com`. */
    private fun buildCookieValues(cookieValue: String): ContentValues {
        // Chromium dùng microseconds kể từ epoch Windows (1601-01-01).
        // Khoảng cách giữa 1601-01-01 và 1970-01-01 là 11644473600 giây.
        val unixEpochOffsetMicros = 11644473600L * 1_000_000L
        val nowMicros = System.currentTimeMillis() * 1_000L + unixEpochOffsetMicros
        // Cookie .ROBLOSECURITY mặc định có hiệu lực ~1 năm; lấy 400 ngày
        // (giới hạn tối đa của Chromium đối với cookie persistent).
        val expiresUtc = nowMicros + 400L * 86400L * 1_000_000L

        return ContentValues().apply {
            put("creation_utc", nowMicros)
            put("host_key", ".roblox.com")
            put("top_frame_site_key", "")
            put("name", COOKIE_NAME)
            put("value", cookieValue)
            put("encrypted_value", ByteArray(0))
            put("path", "/")
            put("expires_utc", expiresUtc)
            put("is_secure", 1)
            put("is_httponly", 1)
            put("last_access_utc", nowMicros)
            put("has_expires", 1)
            put("is_persistent", 1)
            put("priority", 1)
            put("samesite", -1)
            put("source_scheme", 2)
            put("source_port", 443)
            put("last_update_utc", nowMicros)
        }
    }
}
