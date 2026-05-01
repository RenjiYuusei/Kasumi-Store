package com.kasumi.tool

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

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
 * Mọi thao tác đều yêu cầu quyền root (su).
 */
object RobloxLoginManager {

    const val ROBLOX_PACKAGE = "com.roblox.client"
    const val ROBLOX_VNG_PACKAGE = "com.roblox.client.vnggames"
    const val COOKIE_NAME = ".ROBLOSECURITY"

    private const val APP_DATA = "/data/data/com.roblox.client"
    private const val COOKIES_DIR = "/data/data/com.roblox.client/app_webview/Default"
    private const val COOKIES_DB = "/data/data/com.roblox.client/app_webview/Default/Cookies"

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

    /**
     * Trích xuất cookie .ROBLOSECURITY từ database WebView của Roblox.
     *
     * Trả về [Outcome.cookie] nếu thành công. Nếu cookie được mã hóa, [Outcome.message]
     * sẽ thông báo và trả về `null` cookie.
     */
    fun extractCookie(): Outcome {
        val steps = mutableListOf<StepResult>()

        // 1. Kiểm tra database tồn tại
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

        // 2. Kiểm tra sqlite3 có sẵn
        val checkSqlite = runStep(
            "Kiểm tra sqlite3",
            "command -v sqlite3 >/dev/null 2>&1 && echo OK || echo MISSING"
        )
        steps += checkSqlite
        if (!checkSqlite.output.contains("OK")) {
            return Outcome(
                success = false,
                message = "Thiết bị không có lệnh `sqlite3`. Hãy cài thêm `sqlite3` (Magisk module hoặc termux) rồi thử lại.",
                steps = steps
            )
        }

        // 3. Đọc cookie - thử cột `value` trước (plaintext),
        //    nếu rỗng thì báo có khả năng cookie đang được mã hóa.
        val sql = "SELECT value FROM cookies WHERE name='$COOKIE_NAME' AND host_key LIKE '%roblox.com' LIMIT 1;"
        val readCmd = "sqlite3 $COOKIES_DB \"$sql\""
        val readResult = runStep("Đọc cookie .ROBLOSECURITY", readCmd)
        steps += readResult

        if (!readResult.success) {
            return Outcome(
                success = false,
                message = "Không đọc được database cookie. ${readResult.error.ifBlank { readResult.output }}",
                steps = steps
            )
        }

        val value = readResult.output.trim()
        if (value.isEmpty()) {
            // Thử kiểm tra xem có row tồn tại không (encrypted)
            val checkEnc = runStep(
                "Kiểm tra encrypted_value",
                "sqlite3 $COOKIES_DB \"SELECT length(encrypted_value) FROM cookies WHERE name='$COOKIE_NAME' AND host_key LIKE '%roblox.com' LIMIT 1;\""
            )
            steps += checkEnc
            val encLen = checkEnc.output.trim().toIntOrNull() ?: 0
            return if (encLen > 0) {
                Outcome(
                    success = false,
                    message = "Cookie hiện đang được mã hóa (encrypted_value=$encLen bytes). Phiên bản WebView này không lưu cookie dạng plaintext nên không thể trích xuất trực tiếp.",
                    steps = steps
                )
            } else {
                Outcome(
                    success = false,
                    message = "Không tìm thấy cookie .ROBLOSECURITY. Hãy đăng nhập vào Roblox ít nhất 1 lần rồi thử lại.",
                    steps = steps
                )
            }
        }

        return Outcome(
            success = true,
            message = "Đã trích xuất cookie thành công.",
            steps = steps,
            cookie = value
        )
    }

    /**
     * Chèn cookie vào database để đăng nhập trực tiếp vào tài khoản Roblox.
     *
     * Quy trình tương tự script gốc của user:
     *  1. Force-stop Roblox.
     *  2. Xóa các file `Cookies-journal/wal/shm` để tránh xung đột writer.
     *  3. `DELETE FROM cookies` để xóa toàn bộ dữ liệu cũ.
     *  4. `INSERT OR REPLACE` cookie mới với host `.roblox.com`.
     *  5. Sửa quyền (chown, chmod, restorecon) cho khớp với uid của ứng dụng Roblox.
     */
    fun injectCookie(cookie: String): Outcome {
        val trimmed = cookie.trim()
        if (!isCookieFormatValid(trimmed)) {
            return Outcome(
                success = false,
                message = "Cookie không hợp lệ. Cookie phải bắt đầu bằng `_|WARNING:` và chỉ chứa các ký tự an toàn.",
                steps = emptyList()
            )
        }

        val steps = mutableListOf<StepResult>()

        // Bước 1: Force-stop và xóa các file phụ trợ của SQLite
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

        // Bước 2: Đảm bảo database tồn tại
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

        // Bước 3: Xóa data cũ và chèn cookie mới
        val sqlEscapedCookie = trimmed.replace("'", "''")
        val deleteCmd = "sqlite3 $COOKIES_DB \"DELETE FROM cookies;\""
        val insertSql = buildInsertSql(sqlEscapedCookie)
        val insertCmd = "sqlite3 $COOKIES_DB \"$insertSql\""

        val finalSteps = listOf(
            "Xóa sạch cookie cũ" to deleteCmd,
            "Chèn cookie mới" to insertCmd,
            "Sửa quyền (chown)" to "APP_USER=\$(stat -c '%U' $APP_DATA) && chown \$APP_USER:\$APP_USER $COOKIES_DB",
            "Sửa quyền (chmod)" to "chmod 660 $COOKIES_DB",
            "Sửa quyền (restorecon)" to "restorecon $COOKIES_DB || true"
        )

        for ((name, cmd) in finalSteps) {
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

        return Outcome(
            success = true,
            message = "Đã đăng nhập thành công! Mở Roblox để vào thẳng tài khoản.",
            steps = steps
        )
    }

    /** Xây dựng câu lệnh `INSERT OR REPLACE` cho bảng `cookies` của Chromium. */
    private fun buildInsertSql(cookieValue: String): String {
        // Các giá trị thời gian được lấy theo template trong script gốc; Chromium
        // sẽ tự cập nhật khi WebView khởi tạo lại.
        val creationUtc = 13421673867034526L
        val expiresUtc = 13456233867034526L
        val lastAccessUtc = 13421673867034526L
        val lastUpdateUtc = 13421673867053634L

        return "INSERT OR REPLACE INTO cookies " +
            "(creation_utc, host_key, top_frame_site_key, name, value, encrypted_value, path, expires_utc, " +
            "is_secure, is_httponly, last_access_utc, has_expires, is_persistent, priority, samesite, " +
            "source_scheme, source_port, last_update_utc) " +
            "VALUES ($creationUtc, '.roblox.com', '', '$COOKIE_NAME', '$cookieValue', X'', '/', $expiresUtc, " +
            "1, 1, $lastAccessUtc, 1, 1, 1, -1, 2, 443, $lastUpdateUtc);"
    }
}
