package com.kasumi.tool

import android.content.Context
import android.net.Uri
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Quản lý vòng lặp Auto-Rejoin cho ứng dụng Roblox.
 */
object AutoRejoinManager {

    private const val SU_TIMEOUT_SEC = 15L
    private const val LOGCAT_LINES = 1000

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

    enum class RobloxState {
        NOT_RUNNING,
        FOREGROUND_NO_GAME,
        IN_GAME,
        IN_GAME_WRONG_PLACE,
        DISCONNECTED,
    }

    data class StatusReport(
        val state: RobloxState,
        val pid: Int?,
        val currentPlaceId: String?,
        val disconnectHint: String?,
    )

    private data class RawResult(val exitCode: Int, val output: String, val error: String)

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

    fun detectActivePackage(context: Context): String? =
        RobloxLoginManager.detectActivePackage(context)

    fun isValidPlaceId(placeId: String): Boolean {
        val s = placeId.trim()
        return s.isNotEmpty() && s.length <= 16 && s.all { it.isDigit() }
    }

    fun getStatus(
        pkg: String,
        targetPlaceId: String,
        sinceEpochMs: Long = 0L
    ): StatusReport {
        val pidR = executeAsRoot("pidof ${shellQuote(pkg)} 2>/dev/null | awk '{print $1}'")
        val pid = pidR.output.trim().toIntOrNull()
        if (pid == null || pid <= 0) {
            return StatusReport(RobloxState.NOT_RUNNING, null, null, null)
        }

        val timeFilter = if (sinceEpochMs > 0) {
            val secs = sinceEpochMs / 1000
            val micros = ((sinceEpochMs % 1000) * 1000).toString().padStart(6, '0')
            "-T $secs.$micros"
        } else {
            "-t $LOGCAT_LINES"
        }
        val logcatCmd =
            "logcat -d $timeFilter --pid=$pid 2>/dev/null | grep -i -E " +
                "'(${DISCONNECT_PATTERNS.joinToString("|")})' | tail -5"
        val logR = executeAsRoot(logcatCmd, timeoutSec = 10L)
        val hint = logR.output.lineSequence().lastOrNull { it.isNotBlank() }?.trim()
        if (!hint.isNullOrEmpty()) {
            return StatusReport(RobloxState.DISCONNECTED, pid, null, hint)
        }

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

        return StatusReport(RobloxState.FOREGROUND_NO_GAME, pid, null, null)
    }

    /**
     * Kết quả tự dò game hiện tại.
     *
     * Phân loại loại server dựa trên `accessCode` lấy được từ URL vào game
     * mà Roblox ghi ra logcat (tag `rbx.web`):
     *  - Có `accessCode` → server VIP / server riêng (svv). Muốn rejoin đúng
     *    phòng bắt buộc phải kèm mã này.
     *  - Không có `accessCode` → server thường (svth), chỉ cần placeId.
     */
    data class DetectedGame(
        val placeId: String?,
        val accessCode: String? = null,
    ) {
        val hasPlaceId: Boolean get() = !placeId.isNullOrEmpty()
        val isPrivateServer: Boolean get() = !accessCode.isNullOrEmpty()
    }

    private val PLACE_URL_REGEX = Regex("(?i)placeid=([0-9]{3,16})")

    // accessCode là UUID trong URL vào server VIP/riêng, ví dụ:
    // .../games/start?placeId=...&accessCode=b66afff7-708c-4d01-8d59-0c493d096dd3
    private val ACCESS_CODE_REGEX =
        Regex("(?i)accesscode=([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})")

    fun detectCurrentGame(pkg: String): DetectedGame {
        // Step 1: Roblox phải đang chạy (pidof rỗng → process chết).
        val pidR = executeAsRoot("pidof ${shellQuote(pkg)} 2>/dev/null")
        if (pidR.output.trim().isEmpty()) {
            return DetectedGame(null)
        }

        // Step 2: logcat — dòng URL vào game mới nhất (tag rbx.web ghi cả
        // placeId lẫn accessCode nếu là server VIP/riêng). Ưu tiên nguồn này
        // vì chỉ nó mới mang accessCode để phân loại + rejoin đúng phòng VIP.
        val joinR = executeAsRoot(
            "logcat -d -t 20000 2>/dev/null | grep -iE 'games/start|roblox://|placeid=[0-9]' | tail -120",
            timeoutSec = 15L
        )
        val joinLine = joinR.output.lineSequence()
            .lastOrNull { PLACE_URL_REGEX.containsMatchIn(it) }
        if (joinLine != null) {
            val placeId = PLACE_URL_REGEX.find(joinLine)?.groupValues?.getOrNull(1)
            if (!placeId.isNullOrEmpty()) {
                val accessCode = ACCESS_CODE_REGEX.find(joinLine)?.groupValues?.getOrNull(1)
                return DetectedGame(placeId, accessCode)
            }
        }

        // Step 3: dumpsys — deeplink roblox:// còn trong task stack. Thường
        // chỉ còn placeId (server thường) nên không phân loại VIP ở đây.
        val dumpR = executeAsRoot(
            "dumpsys activity activities 2>/dev/null | grep -i 'roblox://' | grep -F ${shellQuote(pkg)} | head -10"
        )
        var placeId = PLACE_URL_REGEX.find(dumpR.output)?.groupValues?.getOrNull(1)

        // Step 4: fallback quét rộng placeId (server thường).
        if (placeId.isNullOrEmpty()) {
            val logR2 = executeAsRoot(
                "logcat -d -t 20000 2>/dev/null | grep -iE 'placeid' | tail -200",
                timeoutSec = 15L
            )
            val placeIdRegex = Regex("(?i)\\bplaceid\"?\\s*[=:]?\\s*\"?([0-9]{4,16})")
            placeId = placeIdRegex.findAll(logR2.output).map { it.groupValues[1] }.lastOrNull()
        }

        return DetectedGame(placeId)
    }

    fun forceStop(pkg: String): Boolean {
        val r = executeAsRoot("am force-stop ${shellQuote(pkg)}")
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

    fun rejoin(
        pkg: String,
        placeId: String,
        accessCode: String? = null,
    ): List<RejoinAttempt> {
        val attempts = mutableListOf<RejoinAttempt>()

        fun tryStart(method: String, url: String): Boolean {
            val cmd = "am start --activity-clear-task -a android.intent.action.VIEW " +
                "-d ${shellQuote(url)} -p ${shellQuote(pkg)}"
            val r = executeAsRoot(cmd)
            attempts += RejoinAttempt(method, cmd, r.exitCode, r.output, r.error)
            return isAmStartSuccess(r)
        }

        if (!accessCode.isNullOrBlank()) {
            // Server VIP/riêng: bắt buộc kèm accessCode, nếu không Roblox sẽ
            // vào phòng public bất kỳ chứ không đúng server người chơi đang ở.
            val vipDeeplink = "roblox://experiences/start?placeId=$placeId&accessCode=$accessCode"
            if (tryStart("VIP — Vào lại server riêng (deeplink)", vipDeeplink)) return attempts

            val vipWeb = "https://www.roblox.com/games/start?placeId=$placeId&accessCode=$accessCode"
            if (tryStart("VIP — Vào lại server riêng (link web)", vipWeb)) return attempts
        } else {
            val m1Url = "roblox://experiences/start?placeId=$placeId"
            if (tryStart("M1 — Vào lại game (deeplink)", m1Url)) return attempts

            val m2Url = "roblox://placeId=$placeId"
            if (tryStart("M2 — Vào lại game (deeplink cũ)", m2Url)) return attempts
        }

        val m3Cmd = "am start -a android.intent.action.MAIN " +
            "-c android.intent.category.LAUNCHER -p ${shellQuote(pkg)}"
        val r3 = executeAsRoot(m3Cmd)
        attempts += RejoinAttempt("M3 — Mở lại app Roblox", m3Cmd, r3.exitCode, r3.output, r3.error)
        return attempts
    }

    private fun shellQuote(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"

    private fun isAmStartSuccess(r: RawResult): Boolean =
        r.exitCode == 0 &&
            !r.output.contains("Error", ignoreCase = true) &&
            !r.error.contains("Error", ignoreCase = true)
}
