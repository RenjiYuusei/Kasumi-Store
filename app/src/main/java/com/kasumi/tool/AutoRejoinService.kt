package com.kasumi.tool

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service chạy vòng lặp Auto-Rejoin Roblox độc lập với Composable
 * UI lifecycle.
 *
 * Đây là **lý do** module này tách khỏi `LaunchedEffect` ban đầu trong
 * [AutoRejoinScreen]: trên Android 8+, mọi process không có foreground
 * service / activity ở foreground sẽ bị OS giới hạn background execution
 * sau ~1–2 phút, kèm doze mode + battery optimization aggressive (đặc biệt
 * trên MIUI/ColorOS/OneUI). Khi user switch sang Roblox để play (use-case
 * chính của tính năng auto-rejoin!), Kasumi-Store activity vào STOPPED và
 * coroutine LaunchedEffect bị OS suspend → loop chết. Foreground service
 * + persistent notification giữ process alive trong khi user đang treo
 * Roblox.
 *
 * Service expose [state] dạng [StateFlow] để UI observe (không bind 2
 * chiều): UI gọi [start] / [stop] qua Intent, và collect [state] để render
 * status / log / counter. Khi Composable bị dispose (user navigate sang
 * tab khác), service vẫn chạy. Khi Composable recompose, nó re-observe
 * cùng StateFlow → UI khôi phục đúng trạng thái hiện tại.
 *
 * ### Notification
 * Vì foreground service là **bắt buộc** trên Android 8+, ta dùng
 * [foregroundServiceType] = `specialUse` (Android 14+). Notification có:
 *  - Title: tên tính năng + state hiện tại
 *  - Body: rejoin count + PID
 *  - Action **Dừng**: pending intent gửi [ACTION_STOP] về cùng service
 *
 * Channel `kasumi_auto_rejoin` ở importance LOW (không kêu, không vibrate)
 * vì user đang chơi game — nóưng vẫn xuất hiện trong status bar để OS
 * không kill process.
 */
class AutoRejoinService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
            else -> {
                // Service được restart bởi system (vd sau khi process bị
                // kill) mà không có extras → không thể tiếp tục an toàn.
                // Vẫn phải `startForeground()` 1 nhịp để thoả mãn Android 8+
                // contract khi entry point là startForegroundService().
                startForegroundCompat(buildNotification(state.value))
                stopServiceCompletely()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        loopJob?.cancel()
        serviceScope.cancel()
        // Reset state ngay cả khi service bị system / ADB terminate không đi
        // qua [stopServiceCompletely] (vd OOM kill, `am stopservice`). Nếu
        // không reset, _state.running vẫn = true → UI hiển thị nút "Dừng"
        // dù service đã chết, user buộc phải bấm "Dừng" trước khi khởi
        // động lại.
        _state.update {
            it.copy(running = false, currentState = null, currentPid = null)
        }
    }

    private fun handleStart(intent: Intent) {
        val pkg = intent.getStringExtra(EXTRA_PKG)
        val placeId = intent.getStringExtra(EXTRA_PLACE_ID)
        val intervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, 5_000L)
            .coerceIn(5_000L, 60_000L)

        if (pkg.isNullOrBlank() || placeId.isNullOrBlank()) {
            // Android 8+ bắt buộc mọi service được khởi động bằng
            // startForegroundService() phải gọi startForeground() trong vòng
            // 5s — nếu không sẽ crash `Context.startForegroundService() did
            // not then call Service.startForeground()`. Ngay cả trong nhánh
            // lỗi ta vẫn phải vào foreground 1 nhịp rồi mới stopSelf().
            startForegroundCompat(buildNotification(state.value))
            stopServiceCompletely()
            return
        }

        // Đẩy service vào foreground TRƯỚC khi launch coroutine — nếu không
        // OS có thể kill process ngay lập tức (Android 8+ rule).
        startForegroundCompat(buildNotification(state.value))

        // Reset state cho run mới + log start.
        _state.update {
            AutoRejoinUiState(
                running = true,
                pkg = pkg,
                placeId = placeId,

                intervalMs = intervalMs,
            )
        }
        appendLog(LogLevel.INFO, "Bắt đầu vòng lặp auto-rejoin cho placeId=$placeId (interval=${intervalMs / 1000}s).")

        loopJob?.cancel()
        loopJob = serviceScope.launch {
            runPollingLoop(pkg, placeId, intervalMs)
        }
    }

    private fun handleStop() {
        appendLog(LogLevel.INFO, "Đã dừng vòng lặp.")
        stopServiceCompletely()
    }

    private fun stopServiceCompletely() {
        loopJob?.cancel()
        loopJob = null
        // Reset state để UI hiển thị idle ngay khi service dừng.
        _state.update { it.copy(running = false, currentState = null, currentPid = null) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    /**
     * Vòng lặp polling chính. Logic giống y hệt phiên bản Composable cũ —
     * chỉ thay `running` flag bằng `isActive` của coroutine + cập nhật
     * notification mỗi tick.
     */
    private suspend fun runPollingLoop(
        pkg: String,
        placeId: String,
        intervalMs: Long,
    ) {
        // Sau mỗi lần force-stop + rejoin, Roblox cần 20–60s để mở lại và
        // load game. Trong khoảng đó pidof có thể chớp tắt → false positive
        // NOT_RUNNING → vòng lặp vô hạn. Grace period 30s.
        val warmupMs = 15_000L
        // Số tick liên tiếp `FOREGROUND_NO_GAME` cho phép trước khi force
        // rejoin (~2 phút với interval 15s mặc định).
        val noGameMaxStreak = 8
        var noGameStreak = 0
        // Timestamp mốc cho logcat filter (Unix epoch ms). Khởi tạo bằng
        // thời điểm service start để tick đầu tiên dùng `-T <epoch>` thay
        // vì `-t LOGCAT_LINES` — tránh case: Roblox đang chạy với cùng PID,
        // bị kick vài phút trước, user tự rejoin tay (không restart process)
        // rồi mới bật auto-rejoin → tick đầu sẽ thấy disconnect event cũ với
        // `--pid=<PID hiện tại>` và force-stop nhầm Roblox đang chạy ngon.
        // Mỗi lần rejoin sau đó cập nhật lại để bỏ qua hint trước rejoin.
        var lastRejoinEpochMs = System.currentTimeMillis()

        while (currentCoroutineContext().isActive) {
            val report = withContext(Dispatchers.IO) {
                AutoRejoinManager.getStatus(pkg, placeId, lastRejoinEpochMs)
            }
            _state.update { it.copy(currentState = report.state, currentPid = report.pid) }
            updateNotification()

            val needRejoin: Boolean = when (report.state) {
                AutoRejoinManager.RobloxState.IN_GAME -> {
                    noGameStreak = 0
                    appendLog(
                        LogLevel.OK,
                        "Đang trong game (placeId=${report.currentPlaceId}, pid=${report.pid}).",
                    )
                    false
                }
                AutoRejoinManager.RobloxState.IN_GAME_WRONG_PLACE -> {
                    noGameStreak = 0
                    appendLog(
                        LogLevel.WARN,
                        "Đang ở placeId khác (${report.currentPlaceId}). Bỏ qua — user có thể đã teleport.",
                    )
                    false
                }
                AutoRejoinManager.RobloxState.FOREGROUND_NO_GAME -> {
                    noGameStreak += 1
                    if (noGameStreak >= noGameMaxStreak) {
                        appendLog(
                            LogLevel.WARN,
                            "Game không load sau $noGameMaxStreak lần check (~${noGameMaxStreak * intervalMs / 1000}s). Force rejoin…",
                        )
                        withContext(Dispatchers.IO) { AutoRejoinManager.forceStop(pkg) }
                        noGameStreak = 0
                        true
                    } else {
                        appendLog(
                            LogLevel.INFO,
                            "Roblox đang chạy nhưng chưa load vào game (pid=${report.pid}, streak=$noGameStreak/$noGameMaxStreak). Chờ tick kế tiếp…",
                        )
                        false
                    }
                }
                AutoRejoinManager.RobloxState.NOT_RUNNING -> {
                    noGameStreak = 0
                    appendLog(LogLevel.WARN, "Roblox không chạy. Đang khởi động lại…")
                    true
                }
                AutoRejoinManager.RobloxState.DISCONNECTED -> {
                    noGameStreak = 0
                    appendLog(
                        LogLevel.WARN,
                        "Phát hiện disconnect/kick: ${report.disconnectHint?.take(120) ?: "?"}",
                    )
                    withContext(Dispatchers.IO) { AutoRejoinManager.forceStop(pkg) }
                    true
                }
            }

            if (needRejoin) {
                val attempts = withContext(Dispatchers.IO) {
                    AutoRejoinManager.rejoin(pkg, placeId)
                }
                lastRejoinEpochMs = System.currentTimeMillis()
                _state.update { it.copy(rejoinCount = it.rejoinCount + 1) }
                updateNotification()
                attempts.forEach { a ->
                    val lvl = if (a.success) LogLevel.OK else LogLevel.ERR
                    val msg = "${a.method}: " +
                        if (a.success) "OK" else "exit=${a.exitCode} ${a.error.take(80)}"
                    appendLog(lvl, msg)
                }
                appendLog(
                    LogLevel.INFO,
                    "Chờ ${warmupMs / 1000}s để Roblox khởi động + load game trước khi check tiếp…",
                )
                delay(warmupMs)
            } else {
                delay(intervalMs)
            }
        }
    }

    private fun appendLog(level: LogLevel, message: String) {
        // ThreadLocal.get() trả về `SimpleDateFormat?` về mặt nullability vì
        // initialValue có thể bị `?.let` override; với khai báo của ta dưới
        // companion thì không bao giờ null, nhưng `!!` để Kotlin happy.
        val ts = LOG_TIME_FMT.get()!!.format(Date())
        _state.update { current ->
            val newLogs = (listOf(LogEntry(ts, level, message)) + current.logs)
                .take(MAX_LOG_ENTRIES)
            current.copy(logs = newLogs)
        }
    }

    private fun startForegroundCompat(notif: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_ID, buildNotification(state.value))
    }

    private fun buildNotification(s: AutoRejoinUiState): Notification {
        val openAppPi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopPi = PendingIntent.getService(
            this,
            1,
            Intent(this, AutoRejoinService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stateLabel = getString(
            when (s.currentState) {
                AutoRejoinManager.RobloxState.IN_GAME -> R.string.auto_rejoin_notif_state_in_game
                AutoRejoinManager.RobloxState.IN_GAME_WRONG_PLACE -> R.string.auto_rejoin_notif_state_in_game_wrong
                AutoRejoinManager.RobloxState.FOREGROUND_NO_GAME -> R.string.auto_rejoin_notif_state_foreground_no_game
                AutoRejoinManager.RobloxState.NOT_RUNNING -> R.string.auto_rejoin_notif_state_not_running
                AutoRejoinManager.RobloxState.DISCONNECTED -> R.string.auto_rejoin_notif_state_disconnected
                null -> if (s.running)
                    R.string.auto_rejoin_notif_state_initializing
                else
                    R.string.auto_rejoin_notif_state_stopped
            }
        )
        val content = buildString {
            append(getString(R.string.auto_rejoin_notif_content_count, s.rejoinCount))
            if (s.currentPid != null) {
                append(" • ")
                append(getString(R.string.auto_rejoin_notif_content_pid, s.currentPid))
            }
            if (!s.placeId.isNullOrBlank()) {
                append(" • ")
                append(getString(R.string.auto_rejoin_notif_content_place, s.placeId))
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_rotate)
            .setContentTitle(getString(R.string.auto_rejoin_notif_title_format, stateLabel))
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppPi)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.auto_rejoin_notif_action_stop),
                stopPi,
            )
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "kasumi_auto_rejoin"
        private const val NOTIFICATION_ID = 0xA9D101
        private const val MAX_LOG_ENTRIES = 50

        const val ACTION_START = "com.kasumi.tool.action.AUTO_REJOIN_START"
        const val ACTION_STOP = "com.kasumi.tool.action.AUTO_REJOIN_STOP"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_PLACE_ID = "placeId"
        const val EXTRA_INTERVAL_MS = "intervalMs"

        // SimpleDateFormat KHÔNG thread-safe theo Java doc. Đặt trong
        // ThreadLocal để mỗi thread có instance riêng — an toàn cả khi sau
        // này có refactor cho phép appendLog chạy từ IO thread (thay vì chỉ
        // main như hiện tại). Object instance được lazy-init ở lần get()
        // đầu tiên trên mỗi thread.
        private val LOG_TIME_FMT: ThreadLocal<SimpleDateFormat> =
            object : ThreadLocal<SimpleDateFormat>() {
                override fun initialValue() = SimpleDateFormat("HH:mm:ss", Locale.US)
            }

        private val _state = MutableStateFlow(AutoRejoinUiState())

        /**
         * State chung của service. UI subscribe qua `collectAsState()` và
         * tự động re-render khi service cập nhật. Khi Composable bị dispose
         * (user chuyển tab) rồi recompose, observation tự thiết lập lại từ
         * StateFlow singleton → UI khôi phục đúng state hiện tại.
         */
        val state: StateFlow<AutoRejoinUiState> = _state.asStateFlow()

        /**
         * Khởi động service ở foreground. An toàn để gọi nhiều lần liên
         * tiếp — service tự cancel job cũ + restart từ đầu.
         */
        fun start(
            context: Context,
            pkg: String,
            placeId: String,
            intervalSec: Int,
        ) {
            val intent = Intent(context, AutoRejoinService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PKG, pkg)
                putExtra(EXTRA_PLACE_ID, placeId)
                putExtra(EXTRA_INTERVAL_MS, intervalSec.toLong() * 1000L)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** Dừng service (idempotent). */
        fun stop(context: Context) {
            val intent = Intent(context, AutoRejoinService::class.java)
                .setAction(ACTION_STOP)
            context.startService(intent)
        }

        private fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val ch = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.auto_rejoin_notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.auto_rejoin_notif_channel_desc)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(ch)
        }
    }
}

enum class LogLevel { INFO, OK, WARN, ERR }

data class LogEntry(
    val timestamp: String,
    val level: LogLevel,
    val message: String,
)

data class AutoRejoinUiState(
    val running: Boolean = false,
    val pkg: String? = null,
    val placeId: String? = null,
    val intervalMs: Long = 5_000L,
    val currentState: AutoRejoinManager.RobloxState? = null,
    val currentPid: Int? = null,
    val rejoinCount: Int = 0,
    /** Newest log entry first. */
    val logs: List<LogEntry> = emptyList(),
)
