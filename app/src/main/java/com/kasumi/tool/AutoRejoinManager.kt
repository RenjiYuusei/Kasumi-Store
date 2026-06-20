package com.kasumi.tool

import android.content.Context
import android.net.Uri
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
     *  2. Quét logcat từ thời điểm [sinceEpochMs] tìm pattern disconnect
     *     (lọc theo `--pid=<pid>` của Roblox).
     *     - Trùng → [RobloxState.DISCONNECTED] (cần rejoin).
     *
     *     **Lưu ý**: bước này phải chạy TRƯỚC dumpsys. Roblox dùng kiến trúc
     *     single-activity (Unity engine), nên sau khi bị kick, Activity gốc
     *     vẫn còn trong task stack với đúng intent
     *     `roblox://experiences/start?placeId=<target>` — `dumpsys` vẫn match
     *     placeId. Nếu check IN_GAME trước, ta sẽ early-return `IN_GAME` và
     *     không bao giờ phát hiện disconnect → tính năng coi như chỉ detect
     *     được `NOT_RUNNING` (process chết hoàn toàn). Logcat-first cho phép
     *     ưu tiên DISCONNECTED hơn IN_GAME khi cả 2 dấu hiệu cùng tồn tại.
     *  3. `dumpsys activity activities | grep placeId=` → đang trong game.
     *     - Nếu placeId trùng targetPlaceId → [RobloxState.IN_GAME] (healthy).
     *     - Khác → [RobloxState.IN_GAME_WRONG_PLACE] (user tự teleport,
     *       không can thiệp).
     *  4. Mặc định: process sống nhưng chưa vào game →
     *     [RobloxState.FOREGROUND_NO_GAME] (đang loading hoặc ở home).
     *
     * @param sinceEpochMs nếu > 0, chỉ quét các dòng logcat phát sinh sau
     *     thời điểm đó (Unix epoch ms). Cần thiết sau mỗi lần rejoin để
     *     **không** đọc lại đúng hint disconnect cũ trong buffer logcat —
     *     đây là nguồn gốc của vòng lặp rejoin vô hạn (force-stop ngay khi
     *     Roblox còn đang load 20–60s ban đầu). Nếu = 0 → fallback về
     *     `-t $LOGCAT_LINES` (số dòng) cho tick đầu tiên trước khi user bấm
     *     Start. Kết hợp với `--pid=<pid>` ở dưới: sau rejoin, PID Roblox
     *     mới khác → log của process cũ tự động bị loại, không cần epoch để
     *     phòng stale event của process trước.
     */
    fun getStatus(
        pkg: String,
        targetPlaceId: String,
        sinceEpochMs: Long = 0L
    ): StatusReport {
        val pidR = executeAsRoot("pidof ${shellQuote(pkg)} 2>/dev/null | awk '{print \$1}'")
        val pid = pidR.output.trim().toIntOrNull()
        if (pid == null || pid <= 0) {
            return StatusReport(RobloxState.NOT_RUNNING, null, null, null)
        }

        // Logcat filter:
        //  - sinceEpochMs > 0 → `-T <secs>.<ms>` để chỉ in các dòng phát sinh
        //    sau thời điểm rejoin gần nhất. Cú pháp `-T <epoch_sec>.<ms>` được
        //    Android logcat hỗ trợ từ Android 5.0+ (bằng minSdk 24 của app).
        //  - sinceEpochMs == 0 → tick đầu tiên, dùng `-t LOGCAT_LINES` để giới
        //    hạn số dòng quét.
        // Cả 2 chế độ đều `-d` (dump-and-exit) → không follow stdout.
        val timeFilter = if (sinceEpochMs > 0) {
            // Android logcat `-T <secs>.<frac>` diễn giải `frac` là
            // microseconds (6 chữ số), không phải milliseconds (3 chữ số).
            // Pad thêm 3 số `0` để chuyển ms → µs đúng spec — tránh lệch
            // ~999ms khiến tick đầu sau rejoin có thể đọc sót log.
            val secs = sinceEpochMs / 1000
            val micros = ((sinceEpochMs % 1000) * 1000).toString().padStart(6, '0')
            "-T $secs.$micros"
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
            // Disconnect-first: ưu tiên DISCONNECTED hơn IN_GAME để tránh
            // bị che bởi intent placeId còn sót trong activity stack sau
            // khi Roblox client kick user (single-activity Unity).
            return StatusReport(RobloxState.DISCONNECTED, pid, null, hint)
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

        return StatusReport(RobloxState.FOREGROUND_NO_GAME, pid, null, null)
    }

    /**
     * Kết quả khi user bấm "Tự dò" — extract Place ID hiện tại mà Roblox
     * đang treo từ activity stack. Cả 2 trường có thể null nếu Roblox không
     * chạy hoặc chưa load vào game (đang ở splash / home / login).
     *
     * @property isPrivateServer `true` nếu Roblox đang ở **server riêng /
     *     VIP server (svv)** — khi đó [gameInstanceId] mới được điền để rejoin
     *     đúng server riêng đó. Nếu đang ở **server thường (svth)**, cờ này
     *     `false` và [gameInstanceId] = null (rejoin chỉ bằng placeId để vào
     *     1 server công khai bất kỳ).
     */
    data class DetectedGame(
        val placeId: String?,
        val gameInstanceId: String?,
        val accessCode: String? = null,
        val isPrivateServer: Boolean = false,
    ) {
        val hasPlaceId: Boolean get() = !placeId.isNullOrEmpty()
    }

    /** Regex bắt `placeId=<id>` trong launch URI (deeplink hoặc URL web). */
    private val PLACE_URL_REGEX = Regex("(?i)placeid=([0-9]{3,16})")

    /** Regex bắt `gameInstanceId=<uuid>` trong launch URI của dumpsys. */
    private val GID_DUMP_REGEX = Regex("gameInstanceId=([^&\\s}\"\\])]+)")

    /**
     * Regex bắt `gameInstanceId` (1 GUID `xxxxxxxx-xxxx-...`) trong 1 dòng
     * logcat / URL join. Cho phép separator `=`, `:`, whitespace + optional
     * dấu nháy bao quanh (`"gameInstanceId":"<uuid>"`).
     */
    private val GID_LOG_REGEX =
        Regex("(?i)gameinstanceid\"?\\s*[=:]?\\s*\"?([0-9a-fA-F]{8}-[0-9a-fA-F-]{4,55})")

    /**
     * Regex bắt `accessCode` (mã truy cập **server riêng / VIP**, 1 GUID)
     * trong URL join. Khớp cả `accessCode=` và `reservedServerAccessCode=`.
     *
     * Đây là dấu hiệu svv QUAN TRỌNG NHẤT: server riêng join qua danh sách
     * private server có URL dạng
     * `…/games/start?placeId=…&accessCode=<uuid>&joinAttemptOrigin=privateServerListJoin`
     * — thường **KHÔNG** kèm `gameInstanceId`. Server thường (matchmaking)
     * KHÔNG bao giờ có accessCode.
     */
    private val ACCESS_CODE_REGEX =
        Regex("(?i)accesscode\"?\\s*[=:]?\\s*\"?([0-9a-fA-F]{8}-[0-9a-fA-F-]{4,55})")

    /**
     * Tự dò Place ID (+ định danh server riêng nếu đang ở **svv**) hiện tại
     * mà Roblox đang treo.
     *
     * Hữu ích cho UI "Tự dò": user mở Roblox, vào game → bấm 1 nút → các ô
     * input tự fill thay vì phải tìm rồi paste tay.
     *
     * ### Phân biệt server riêng (svv) vs server thường (svth)
     * Server riêng được join qua URL có **`accessCode`** (mã truy cập VIP /
     * private server) và/hoặc **`gameInstanceId`** (id reserved server đang
     * chạy). Cả 2 đều cho phép rejoin đúng server đó. Server thường
     * (matchmaking) KHÔNG có 2 field này:
     *  - **svv**: trả `placeId` + `accessCode` (và `gameInstanceId` nếu có)
     *    → rejoin thẳng vào đúng server riêng.
     *  - **svth**: chỉ trả `placeId` → để Roblox tự matchmaking.
     *
     * ### Nguồn dữ liệu
     *  1. **`dumpsys activity activities`** — deeplink `roblox://...` còn
     *     trong task stack (khi Roblox được launch qua deeplink). `grep -F
     *     ${pkg}` tránh nhầm bản global vs VNG.
     *  2. **logcat — dòng "join URL" MỚI NHẤT**. Roblox log mỗi lần join qua
     *     URL (nằm gọn trên 1 dòng), do component `rbx.web`:
     *       `https://www.roblox.com/games/start?placeId=…&accessCode=…&joinAttemptOrigin=privateServerListJoin`
     *       `https://www.roblox.com/games/start?placeId=…&gameInstanceId=…`
     *       `roblox://experiences/start?placeId=…&gameInstanceId=…`
     *     Lấy dòng CUỐI có `placeId=` (mới nhất → server hiện tại) rồi parse
     *     `placeId` + `accessCode` + `gameInstanceId` TỪ CÙNG 1 DÒNG.
     *  3. Nếu vẫn chưa có `placeId` (svth thường không log URL join qua
     *     rbx.web) → quét logcat tìm `placeId` chung (chỉ placeId, svth).
     *
     * ### Vì sao KHÔNG lọc `--pid` (root nguyên nhân bug cũ)
     * URL join được log bởi component `rbx.web`, có thể chạy ở **process con
     * khác pid** với main process (`pidof` trả pid của main). Bản trước lọc
     * `logcat --pid=<main>` → loại MẤT đúng dòng chứa accessCode /
     * gameInstanceId → báo nhầm svv thành svth. Bản này grep thẳng theo URL
     * join, không lọc pid.
     *
     * Parse các field từ cùng 1 dòng join mới nhất cũng tránh lỗi ghép field
     * cũ với placeId mới (chuyển private → public cùng phiên không kill app).
     *
     * @return [DetectedGame] với placeId (+ accessCode/gameInstanceId nếu
     *     svv). Trả về placeId = null nếu Roblox không chạy / chưa từng vào
     *     game / lỗi root.
     */
    fun detectCurrentGame(pkg: String): DetectedGame {
        // Step 1: Roblox phải đang chạy (pidof rỗng → process chết).
        val pidR = executeAsRoot("pidof ${shellQuote(pkg)} 2>/dev/null")
        if (pidR.output.trim().isEmpty()) {
            return DetectedGame(null, null)
        }

        // Step 2: dumpsys — deeplink roblox:// còn trong task stack.
        val dumpR = executeAsRoot(
            "dumpsys activity activities 2>/dev/null | grep -i 'roblox://' | grep -F ${shellQuote(pkg)} | head -10"
        )
        var placeId = PLACE_URL_REGEX.find(dumpR.output)?.groupValues?.getOrNull(1)
        // gameInstanceId/accessCode trong dumpsys URL có thể đã URL-encode →
        // decode về raw để hiển thị / dùng lại.
        var gid = GID_DUMP_REGEX.find(dumpR.output)?.groupValues?.getOrNull(1)
            ?.let { Uri.decode(it) }?.takeIf { it.isNotBlank() }
        var accessCode = ACCESS_CODE_REGEX.find(dumpR.output)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }

        // Step 3: logcat — dòng join URL mới nhất (xem doc ở trên). Luôn quét
        // logcat nếu chưa có accessCode (để chắc chắn svv/svth).
        if (placeId.isNullOrEmpty() || accessCode.isNullOrEmpty()) {
            val logR = executeAsRoot(
                "logcat -d -t 20000 2>/dev/null | grep -iE 'placeid=[0-9]' | " +
                    "grep -iE 'rbx\\.web|games/start|gameinstanceid|accesscode|roblox://' | tail -80",
                timeoutSec = 15L
            )
            val logLines = logR.output.lineSequence().toList()
            // 1. Tìm placeId mới nhất từ logcat nếu dumpsys chưa có.
            if (placeId.isNullOrEmpty()) {
                placeId = logLines.lastOrNull { PLACE_URL_REGEX.containsMatchIn(it) }
                    ?.let { PLACE_URL_REGEX.find(it)?.groupValues?.getOrNull(1) }
            }

            // 2. Tìm metadata (accessCode/gid) từ dòng join URL tốt nhất.
            // "Tốt nhất" = dòng mới nhất có cùng placeId và chứa accessCode hoặc log từ rbx.web.
            if (!placeId.isNullOrEmpty()) {
                val bestLine = logLines.lastOrNull { line ->
                    PLACE_URL_REGEX.find(line)?.groupValues?.getOrNull(1) == placeId &&
                        (ACCESS_CODE_REGEX.containsMatchIn(line) || line.contains("rbx.web", ignoreCase = true))
                } ?: logLines.lastOrNull { line ->
                    // Fallback: dòng cuối cùng có placeId này.
                    PLACE_URL_REGEX.find(line)?.groupValues?.getOrNull(1) == placeId
                }

                if (bestLine != null) {
                    if (gid.isNullOrEmpty()) {
                        gid = GID_LOG_REGEX.find(bestLine)?.groupValues?.getOrNull(1)
                            ?.takeIf { it.isNotBlank() }
                    }
                    if (accessCode.isNullOrEmpty()) {
                        accessCode = ACCESS_CODE_REGEX.find(bestLine)?.groupValues?.getOrNull(1)
                            ?.takeIf { it.isNotBlank() }
                    }
                }
            }
        }

        // Step 4: svth fallback — không tìm thấy URL join (svth thường không
        // log qua rbx.web) nhưng placeId vẫn nằm đâu đó trong logcat. Quét
        // chung placeId (chỉ placeId; không gid/accessCode → đúng là svth).
        if (placeId.isNullOrEmpty()) {
            val logR2 = executeAsRoot(
                "logcat -d -t 20000 2>/dev/null | grep -iE 'placeid' | tail -200",
                timeoutSec = 15L
            )
            val placeIdRegex = Regex("(?i)\\bplaceid\"?\\s*[=:]?\\s*\"?([0-9]{4,16})")
            placeId = placeIdRegex.findAll(logR2.output).map { it.groupValues[1] }.lastOrNull()
        }

        if (placeId.isNullOrEmpty()) {
            return DetectedGame(null, null)
        }

        // svv ⟺ có accessCode (dấu hiệu server riêng/VIP). gameInstanceId
        // chỉ là định danh instance, không đủ để coi là svv nếu thiếu
        // accessCode (ví dụ svth cũng có gid trong dumpsys).
        val isPrivate = !accessCode.isNullOrEmpty()
        return DetectedGame(
            placeId = placeId,
            gameInstanceId = gid,
            accessCode = accessCode,
            isPrivateServer = isPrivate,
        )
    }

    /**
     * Force-stop Roblox để dọn state trước khi rejoin. Idempotent: gọi nhiều
     * lần liên tiếp không gây lỗi (Android `am force-stop` tự handle no-op
     * khi process đã chết).
     */
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

    /**
     * Thử rejoin theo thứ tự M1 → M2 → M3, dừng ngay khi 1 method thành công
     * (exit code 0). Trả về danh sách `RejoinAttempt` để UI có thể hiển thị
     * log từng bước (thành công / failed) cho người dùng debug.
     *
     * @param pkg package Roblox (`com.roblox.client` hoặc VNG).
     * @param placeId placeId mục tiêu — phải đã pass [isValidPlaceId].
     * @param gameInstanceId tùy chọn: id reserved server (private/VIP server).
     * @param accessCode tùy chọn: mã truy cập server riêng / VIP — Roblox
     *     deeplink chấp nhận `&accessCode=` để rejoin thẳng vào server riêng
     *     (xem create.roblox.com/docs deeplinks). Server riêng join từ danh
     *     sách private server thường chỉ có accessCode (không gameInstanceId).
     */
    fun rejoin(
        pkg: String,
        placeId: String,
        gameInstanceId: String? = null,
        accessCode: String? = null
    ): List<RejoinAttempt> {
        val attempts = mutableListOf<RejoinAttempt>()

        val gid = gameInstanceId?.takeIf { it.isNotBlank() }
        val code = accessCode?.takeIf { it.isNotBlank() }

        // M1: deeplink dạng experiences (mới). Gắn kèm gameInstanceId và/hoặc
        // accessCode nếu có để rejoin đúng server riêng (svv).
        val m1Url = buildString {
            append("roblox://experiences/start?placeId=")
            append(placeId)
            // URL-encode các tham số để xử lý ký tự đặc biệt (`%`, `+`,
            // khoảng trắng, `#`, ...) khi user dán nhầm. Với UUID chuẩn
            // `[0-9a-f-]` thì encode là no-op. Dùng `android.net.Uri.encode`
            // (RFC 3986) thay vì `URLEncoder.encode` (form-encoding): tránh
            // convert dấu cách thành `+` mà Roblox URL parser không decode.
            if (gid != null) {
                append("&gameInstanceId=")
                append(Uri.encode(gid))
            }
            if (code != null) {
                append("&accessCode=")
                append(Uri.encode(code))
            }
        }
        val m1Cmd = "am start --activity-clear-task -a android.intent.action.VIEW " +
            "-d ${shellQuote(m1Url)} -p ${shellQuote(pkg)}"
        val r1 = executeAsRoot(m1Cmd)
        attempts += RejoinAttempt("M1 — Experiences deeplink", m1Cmd, r1.exitCode, r1.output, r1.error)
        if (isAmStartSuccess(r1)) return attempts

        // M2: deeplink legacy. Cũng gắn accessCode/gameInstanceId nếu có.
        val m2Url = buildString {
            append("roblox://placeId=")
            append(placeId)
            if (gid != null) {
                append("&gameInstanceId=")
                append(Uri.encode(gid))
            }
            if (code != null) {
                append("&accessCode=")
                append(Uri.encode(code))
            }
        }
        val m2Cmd = "am start --activity-clear-task -a android.intent.action.VIEW " +
            "-d ${shellQuote(m2Url)} -p ${shellQuote(pkg)}"
        val r2 = executeAsRoot(m2Cmd)
        attempts += RejoinAttempt("M2 — Legacy deeplink", m2Cmd, r2.exitCode, r2.output, r2.error)
        if (isAmStartSuccess(r2)) return attempts

        // M3: cold launch (chỉ mở app, không vào game)
        val m3Cmd = "am start -a android.intent.action.MAIN " +
            "-c android.intent.category.LAUNCHER -p ${shellQuote(pkg)}"
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

    /**
     * Kiểm tra `am start` thành công thực sự. Một số ROM (MIUI, ColorOS) có
     * thể trả exit code 0 nhưng in `"Error: Activity class ... does not
     * exist"` ra **stderr** thay vì stdout — nếu chỉ check stdout, M1 sẽ
     * được coi là OK và return sớm mà không thử M2/M3, gây thêm 1 chu kỳ
     * chờ + warmup 30s không cần thiết. Phải kiểm tra cả stdout lẫn stderr.
     */
    private fun isAmStartSuccess(r: RawResult): Boolean =
        r.exitCode == 0 &&
            !r.output.contains("Error", ignoreCase = true) &&
            !r.error.contains("Error", ignoreCase = true)
}
