package com.kasumi.tool

import android.content.Context
import java.net.URLEncoder
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Quản lý vòng lặp Auto-Rejoin cho ứng dụng Roblox.
 *
 * Mục tiêu: phát hiện khi tài khoản Roblox bị **rớt** khỏi game (kicked,
 * disconnected, crash, force-stop, ...) và tự động re-launch lại đúng PlaceId
 * mà người dùng cấu hình — tương tự cách hoạt động của các script auto-farm
 * cho Roblox/Delta.
 *
 * Toàn bộ logic root đều dùng cùng pattern `executeAsRoot` của
 * [RobloxLoginManager] (đọc stdout/stderr song song qua [CompletableFuture]
 * + timeout để tránh deadlock pipe / treo UI). Module này được giữ độc lập
 * vì các thao tác ở đây chỉ cần `am start` / `dumpsys` / `pidof` — không
 * đụng tới SQLite cookie database.
 *
 * ### Phương thức rejoin (theo thứ tự ưu tiên)
 *  - **M1 — Experiences deeplink**: `roblox://experiences/start?placeId=<id>`
 *    là dạng URI hiện đại, tương thích với Roblox client mới nhất và đa số
 *    bản VNG.
 *  - **M2 — Legacy deeplink**: `roblox://placeId=<id>` — fallback cho các
 *    ROM cũ hoặc bản client không expose intent-filter mới.
 *  - **M3 — Cold launch**: `am start -a android.intent.action.MAIN ...` —
 *    chỉ dùng khi cả deeplink đều thất bại. Người dùng vẫn đứng ở trang chủ
 *    Roblox, nhưng ít nhất app đã được mở để có thể rejoin thủ công.
 *
 * ### Phương thức kiểm tra tình trạng
 *  - Process còn sống: `pidof <pkg>` (1 dòng PID nếu sống, rỗng nếu chết).
 *  - Đang trong game: regex `placeId=([0-9]+)` trong output
 *    `dumpsys activity activities` (chỉ xuất hiện khi tab Roblox đã load
 *    thành công thông qua deeplink). Nếu thấy đúng placeId mà người dùng
 *    cấu hình → coi là healthy.
 *  - Bị kick/crash gần đây: tìm các từ khóa lỗi trong logcat 5 phút gần
 *    nhất (`logcat -d -t 1000`) — tương đồng với cách tool console gốc nhận
 *    biết ("Lost connection", "Disconnection Notification", "kicked",
 *    "Teleport failed", ...).
 *
 * Tất cả các thao tác đều idempotent — gọi rejoin khi đã ở trong game đúng
 * placeId vẫn an toàn (deeplink trùng sẽ bị Roblox no-op).
 */
object AutoRejoinManager {

    /** Timeout cho 1 lệnh `su -c` đơn lẻ. Giữ ngắn để không block vòng lặp. */
    private const val SU_TIMEOUT_SEC = 15L

    /** Số dòng logcat tối đa quét cho mỗi lần check kick/crash. */
    private const val LOGCAT_LINES = 1000

    /**
     * Regex các pattern thường thấy khi tài khoản Roblox bị rớt.
     *
     * Lấy trực tiếp từ tool console gốc — đã được kiểm chứng trên cả bản
     * Roblox global và VNG.
     */
    private val DISCONNECT_PATTERNS = listOf(
        "You have been kicked",
        "Lost connection with reason",
        "Sending disconnect with reason",
        "Disconnection Notification",
        "Connection lost",
        "Teleport failed",
        "same account launched",
        "server.?shut",
    )

    /** Trạng thái runtime của Roblox tại 1 thời điểm cụ thể. */
    enum class RobloxState {
        /** Process Roblox không tồn tại (chưa chạy hoặc đã bị kill). */
        NOT_RUNNING,
        /** Roblox đã chạy nhưng chưa load vào game (đang ở home / loading). */
        FOREGROUND_NO_GAME,
        /** Đang trong game ĐÚNG placeId người dùng cấu hình. */
        IN_GAME,
        /** Đang trong game NHƯNG khác placeId (user teleport sang nơi khác). */
        IN_GAME_WRONG_PLACE,
        /** Logcat ghi nhận disconnect/kick gần đây — cần rejoin ngay. */
        DISCONNECTED,
    }

    data class StatusReport(
        val state: RobloxState,
        val pid: Int?,
        val currentPlaceId: String?,
        val disconnectHint: String?,
    )

    /** Kết quả thực thi `su -c <cmd>`. */
    private data class RawResult(val exitCode: Int, val output: String, val error: String)

    /**
     * Thực thi lệnh shell với quyền root. Đồng nhất với
     * [RobloxLoginManager.executeAsRoot] về mặt timeout / pipe handling — copy
     * thay vì internalize để giữ 2 module độc lập (không dependency chéo).
     */
    private fun executeAsRoot(command: String, timeoutSec: Long = SU_TIMEOUT_SEC): RawResult {
        var process: Process? = null
        return try {
            val p = ProcessBuilder("su", "-c", command).start().also { process = it }
            val outFut = CompletableFuture.supplyAsync { p.inputStream.bufferedReader().readText() }
            val errFut = CompletableFuture.supplyAsync { p.errorStream.bufferedReader().readText() }
            val waitFut = CompletableFuture.supplyAsync { p.waitFor() }
            val exitCode = try {
                waitFut.get(timeoutSec, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                p.destroy()
                outFut.cancel(true); errFut.cancel(true); waitFut.cancel(true)
                return RawResult(exitCode = -1, output = "", error = "Timeout sau ${timeoutSec}s")
            }
            val out = runCatching { outFut.get(2, TimeUnit.SECONDS) }.getOrDefault("")
            val err = runCatching { errFut.get(2, TimeUnit.SECONDS) }.getOrDefault("")
            RawResult(exitCode, out.trim(), err.trim())
        } catch (t: Throwable) {
            RawResult(exitCode = -1, output = "", error = t.message ?: t.javaClass.simpleName)
        } finally {
            try { process?.destroy() } catch (_: Throwable) {}
        }
    }

    /**
     * Trả về package Roblox đang được cài (ưu tiên global trước VNG).
     *
     * Delegate sang [RobloxLoginManager] để giữ 1 nguồn sự thật duy nhất —
     * tránh tình trạng tab "Login Roblox" và "Auto Rejoin" detect khác nhau
     * khi cả 2 bản đều cài đặt.
     */
    fun detectActivePackage(context: Context): String? =
        RobloxLoginManager.detectActivePackage(context)

    /**
     * Validate placeId nhập vào: chỉ chấp nhận chuỗi số dương (không có dấu,
     * không có khoảng trắng). Giới hạn 16 ký tự để chặn input phá hoại
     * (Roblox placeId thực tế ≤ 13 chữ số tính tới 2026).
     */
    fun isValidPlaceId(placeId: String): Boolean {
        val s = placeId.trim()
        return s.isNotEmpty() && s.length <= 16 && s.all { it.isDigit() }
    }

    /**
     * Lấy status hiện tại của Roblox — quyết định có rejoin hay không.
     *
     * Thứ tự kiểm tra (early return):
     *  1. `pidof` rỗng → process chết → [RobloxState.NOT_RUNNING].
     *  2. `dumpsys activity activities | grep placeId=` → đang trong game.
     *     - Nếu placeId trùng targetPlaceId → [RobloxState.IN_GAME] (healthy).
     *     - Khác → [RobloxState.IN_GAME_WRONG_PLACE] (user tự teleport,
     *       không can thiệp).
     *  3. Quét logcat từ thời điểm [sinceEpochMs] tìm pattern disconnect.
     *     - Trùng → [RobloxState.DISCONNECTED] (cần rejoin).
     *  4. Mặc định: process sống nhưng chưa vào game →
     *     [RobloxState.FOREGROUND_NO_GAME] (đang loading hoặc ở home).
     *
     * @param sinceEpochMs nếu > 0, chỉ quét các dòng logcat phát sinh sau
     *     thời điểm đó (Unix epoch ms). Cần thiết sau mỗi lần rejoin để
     *     **không** đọc lại đúng hint disconnect cũ trong buffer logcat —
     *     đây là nguồn gốc của vòng lặp rejoin vô hạn (force-stop ngay khi
     *     Roblox còn đang load 20–60s ban đầu). Nếu = 0 → fallback về
     *     `-t $LOGCAT_LINES` (số dòng) cho tick đầu tiên trước khi user bấm
     *     Start.
     */
    fun getStatus(
        pkg: String,
        targetPlaceId: String,
        sinceEpochMs: Long = 0L
    ): StatusReport {
        val pidR = executeAsRoot("pidof $pkg 2>/dev/null | awk '{print \$1}'")
        val pid = pidR.output.trim().toIntOrNull()
        if (pid == null || pid <= 0) {
            return StatusReport(RobloxState.NOT_RUNNING, null, null, null)
        }

        // dumpsys chỉ ghi placeId vào intent của activity nếu deeplink đã được
        // resolve thành công. Khi user còn ở splash / login / home → grep
        // không match.
        // `grep -F` match literal: dấu `.` trong tên package (vd `com.roblox.client`)
        // không bị grep interpret là regex wildcard.
        val dumpR = executeAsRoot(
            "dumpsys activity activities 2>/dev/null | grep -i 'roblox://' | grep -F ${shellQuote(pkg)} | head -10"
        )
        val placeIdInDump = Regex("placeId=([0-9]+)").find(dumpR.output)?.groupValues?.getOrNull(1)
        if (!placeIdInDump.isNullOrEmpty()) {
            return if (placeIdInDump == targetPlaceId) {
                StatusReport(RobloxState.IN_GAME, pid, placeIdInDump, null)
            } else {
                StatusReport(RobloxState.IN_GAME_WRONG_PLACE, pid, placeIdInDump, null)
            }
        }

        // Logcat filter:
        //  - sinceEpochMs > 0 → `-T <secs>.<ms>` để chỉ in các dòng phát sinh
        //    sau thời điểm rejoin gần nhất. Cú pháp `-T <epoch_sec>.<ms>` được
        //    Android logcat hỗ trợ từ Android 5.0+ (bằng minSdk 24 của app).
        //  - sinceEpochMs == 0 → tick đầu tiên, dùng `-t LOGCAT_LINES` để giới
        //    hạn số dòng quét.
        // Cả 2 chế độ đều `-d` (dump-and-exit) → không follow stdout.
        val timeFilter = if (sinceEpochMs > 0) {
            val secs = sinceEpochMs / 1000
            val millis = (sinceEpochMs % 1000).toString().padStart(3, '0')
            "-T $secs.$millis"
        } else {
            "-t $LOGCAT_LINES"
        }
        // `--pid=<pid>` lọc log chỉ của process Roblox — tránh false positive
        // từ app khác (Wi-Fi stack, game khác, hệ thống) cũng ghi các chuỗi
        // như "Connection lost" / "Teleport failed". Flag này hỗ trợ từ
        // Android API 24, khớp với minSdk của app.
        val logcatCmd =
            "logcat -d $timeFilter --pid=$pid 2>/dev/null | grep -i -E " +
                "'(${DISCONNECT_PATTERNS.joinToString("|")})' | tail -5"
        val logR = executeAsRoot(logcatCmd, timeoutSec = 10L)
        val hint = logR.output.lineSequence().lastOrNull { it.isNotBlank() }?.trim()
        if (!hint.isNullOrEmpty()) {
            return StatusReport(RobloxState.DISCONNECTED, pid, null, hint)
        }

        return StatusReport(RobloxState.FOREGROUND_NO_GAME, pid, null, null)
    }

    /**
     * Force-stop Roblox để dọn state trước khi rejoin. Idempotent: gọi nhiều
     * lần liên tiếp không gây lỗi (Android `am force-stop` tự handle no-op
     * khi process đã chết).
     */
    fun forceStop(pkg: String): Boolean {
        val r = executeAsRoot("am force-stop $pkg")
        return r.exitCode == 0
    }

    data class RejoinAttempt(
        val method: String,
        val command: String,
        val exitCode: Int,
        val output: String,
        val error: String,
    ) {
        val success: Boolean get() = exitCode == 0
    }

    /**
     * Thử rejoin theo thứ tự M1 → M2 → M3, dừng ngay khi 1 method thành công
     * (exit code 0). Trả về danh sách `RejoinAttempt` để UI có thể hiển thị
     * log từng bước (thành công / failed) cho người dùng debug.
     *
     * @param pkg package Roblox (`com.roblox.client` hoặc VNG).
     * @param placeId placeId mục tiêu — phải đã pass [isValidPlaceId].
     * @param gameInstanceId tùy chọn: link tới private server / VIP server.
     */
    fun rejoin(
        pkg: String,
        placeId: String,
        gameInstanceId: String? = null
    ): List<RejoinAttempt> {
        val attempts = mutableListOf<RejoinAttempt>()

        // M1: deeplink dạng experiences (mới)
        val gid = gameInstanceId?.takeIf { it.isNotBlank() }
        val m1Url = buildString {
            append("roblox://experiences/start?placeId=")
            append(placeId)
            if (gid != null) {
                append("&gameInstanceId=")
                // URL-encode gid để xử lý các ký tự đặc biệt (`%`, `+`,
                // khoảng trắng, `#`, ...) khi user dán nhầm. Với UUID chuẩn
                // `[0-9a-f-]` thì encode là no-op (Roblox vẫn parse đúng).
                // Dùng UTF-8 — `URLEncoder` luôn hỗ trợ charset này, không
                // ném `UnsupportedEncodingException`.
                append(URLEncoder.encode(gid, "UTF-8"))
            }
        }
        val m1Cmd = "am start --activity-clear-task -a android.intent.action.VIEW " +
            "-d ${shellQuote(m1Url)} -p $pkg"
        val r1 = executeAsRoot(m1Cmd)
        attempts += RejoinAttempt("M1 — Experiences deeplink", m1Cmd, r1.exitCode, r1.output, r1.error)
        if (r1.exitCode == 0 && !r1.output.contains("Error", ignoreCase = true)) return attempts

        // M2: deeplink legacy
        val m2Url = "roblox://placeId=$placeId"
        val m2Cmd = "am start --activity-clear-task -a android.intent.action.VIEW " +
            "-d ${shellQuote(m2Url)} -p $pkg"
        val r2 = executeAsRoot(m2Cmd)
        attempts += RejoinAttempt("M2 — Legacy deeplink", m2Cmd, r2.exitCode, r2.output, r2.error)
        if (r2.exitCode == 0 && !r2.output.contains("Error", ignoreCase = true)) return attempts

        // M3: cold launch (chỉ mở app, không vào game)
        val m3Cmd = "am start -a android.intent.action.MAIN " +
            "-c android.intent.category.LAUNCHER -p $pkg"
        val r3 = executeAsRoot(m3Cmd)
        attempts += RejoinAttempt("M3 — Cold launch (mở app)", m3Cmd, r3.exitCode, r3.output, r3.error)
        return attempts
    }

    /**
     * Wrap chuỗi trong single quote cho shell, escape `'` bên trong theo
     * pattern `'\''`. Cần thiết vì URL deeplink chứa `&`, `?`, `=` — nếu để
     * trần thì shell sẽ background process / treat token sai.
     */
    private fun shellQuote(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"
}
