package com.kasumi.tool

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tab "Auto Rejoin Roblox" — vòng lặp polling phát hiện khi tài khoản Roblox
 * bị rớt (kicked / crash / force-stop / disconnect) và tự động re-launch lại
 * đúng PlaceId mà người dùng cấu hình.
 *
 * UI gồm 4 khối:
 *  1. **StatusCard**: hiển thị quyền root + bản Roblox được phát hiện.
 *  2. **ConfigCard**: input PlaceId, optional GameInstanceId, slider chu kỳ
 *     check (5–60s). Disable khi loop đang chạy để tránh user đổi giữa chừng.
 *  3. **ControlCard**: nút Start / Stop, hiển thị state hiện tại + tổng số lần
 *     đã rejoin từ lúc bật.
 *  4. **LogCard**: log cuộn các sự kiện gần đây (tối đa 50 dòng).
 *
 * Loop được bind vào `LaunchedEffect(running)` — khi user toggle off hoặc
 * navigate sang tab khác (RobloxLoginScreen sẽ recompose & dispose), loop
 * tự stop nhờ cancellation của coroutine scope.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoRejoinScreen(
    onShowSnackbar: (String) -> Unit
) {
    val context = LocalContext.current

    // Strings cần dùng trong lambda non-Composable
    val msgInvalidPlace = stringResource(R.string.auto_rejoin_snackbar_invalid_place_id)
    val msgNotReady = stringResource(R.string.auto_rejoin_snackbar_not_ready)
    val msgStarted = stringResource(R.string.auto_rejoin_snackbar_started)
    val msgStopped = stringResource(R.string.auto_rejoin_snackbar_stopped)

    var rooted by remember { mutableStateOf<Boolean?>(null) }
    var detectedPackage by remember { mutableStateOf<String?>(null) }
    var robloxInstalled by remember { mutableStateOf<Boolean?>(null) }

    // Cấu hình dùng `rememberSaveable` để user không mất Place ID / cycle khi
    // chuyển tab và quay lại (composable bị dispose). State bắt buoc reset
    // (`running`, log entries, counter, currentState) vẫn dùng `remember` —
    // vòng lặp phải dừng khi tab bị dispose, nên không cần persist.
    var placeIdInput by rememberSaveable { mutableStateOf("") }
    var gameInstanceInput by rememberSaveable { mutableStateOf("") }
    var intervalSec by rememberSaveable { mutableFloatStateOf(15f) }

    var running by remember { mutableStateOf(false) }
    var rejoinCount by remember { mutableIntStateOf(0) }
    var currentState by remember { mutableStateOf<AutoRejoinManager.RobloxState?>(null) }
    var currentPid by remember { mutableStateOf<Int?>(null) }
    val logEntries = remember { mutableStateListOf<LogEntry>() }
    // Đếm số tick liên tiếp ở `FOREGROUND_NO_GAME` — Roblox đang mở nhưng
    // chưa vào game đúng placeId. Sau khi vượt ngưỡng, ta force rejoin để
    // thoát khỏi tình trạng kẹt vô hạn (deeplink resolved OK nhưng game
    // không load: placeId chết, server đóng, hoặc Roblox bị đẩy về home).
    var noGameStreak by remember { mutableIntStateOf(0) }
    // Thời điểm rejoin gần nhất (Unix epoch ms) — truyền vào `getStatus` để
    // logcat chỉ đọc các dòng phát sinh sau thời điểm đó, tránh đọc lại
    // hint disconnect cũ → vòng lặp rejoin vô hạn trong lúc Roblox đang load
    // lại. 0 = chưa từng rejoin, fallback về `-t LOGCAT_LINES`.
    var lastRejoinEpochMs by remember { mutableLongStateOf(0L) }

    val ready = rooted == true && robloxInstalled == true

    LaunchedEffect(Unit) {
        // Detect root + Roblox song song để rút ngắn trạng thái "đang kiểm tra".
        val rootedDeferred = async(Dispatchers.IO) { RootInstaller.isDeviceRooted() }
        val pkgDeferred = async(Dispatchers.IO) {
            AutoRejoinManager.detectActivePackage(context)
        }
        rooted = rootedDeferred.await()
        val pkg = pkgDeferred.await()
        detectedPackage = pkg
        robloxInstalled = pkg != null
    }

    // Vòng lặp polling. Bind vào `running` — khi user tắt → block thoát ngay.
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val pkg = detectedPackage ?: return@LaunchedEffect
        val placeId = placeIdInput.trim()
        val gid = gameInstanceInput.trim().ifEmpty { null }
        val intervalMs = (intervalSec.toLong().coerceIn(5L, 60L)) * 1000L
        // Sau mỗi lần force-stop + rejoin, Roblox cần 20–60s để mở lại và load
        // game. Trong khoảng đó `pidof` có thể chớp tắt (chỉ trong vài giây
        // đầu sau force-stop, pre-zygote-fork) khiến logic lại thấy NOT_RUNNING
        // → rejoin lại → vòng lặp vô hạn. WARMUP_AFTER_REJOIN_MS là grace period
        // ngắn ở dưới `delay` thường để tải xử sau rejoin (logcat filter
        // theo `lastRejoinEpochMs` lo cái phần DISCONNECTED, nên chỉ cần thêm
        // grace cho NOT_RUNNING).
        val warmupMs = 30_000L
        // Số tick liên tiếp `FOREGROUND_NO_GAME` cho phép trước khi force
        // rejoin. Với interval mặc định 15s → ~2 phút (8 tick) chờ game load,
        // đủ cho cả case mạng yếu nhưng đủ ngắn để user không phải tự can
        // thiệp khi placeId chết / server đóng.
        val noGameMaxStreak = 8
        // Reset streak khi loop start (Stop-Start lại từ đầu).
        noGameStreak = 0

        while (isActive && running) {
            val report = withContext(Dispatchers.IO) {
                AutoRejoinManager.getStatus(pkg, placeId, lastRejoinEpochMs)
            }
            currentState = report.state
            currentPid = report.pid

            // Chia đôi: chỉ những trạng thái "unhealthy" mới trigger rejoin.
            // Nhừng trạng thái healthy/transient thì chỉ log rồi delay tick kế.
            val needRejoin: Boolean = when (report.state) {
                AutoRejoinManager.RobloxState.IN_GAME -> {
                    noGameStreak = 0
                    appendLog(
                        logEntries, LogLevel.OK,
                        "Đang trong game (placeId=${report.currentPlaceId}, pid=${report.pid})."
                    )
                    false
                }
                AutoRejoinManager.RobloxState.IN_GAME_WRONG_PLACE -> {
                    noGameStreak = 0
                    appendLog(
                        logEntries, LogLevel.WARN,
                        "Đang ở placeId khác (${report.currentPlaceId}). Bỏ qua — user có thể đã teleport."
                    )
                    false
                }
                AutoRejoinManager.RobloxState.FOREGROUND_NO_GAME -> {
                    noGameStreak += 1
                    if (noGameStreak >= noGameMaxStreak) {
                        // Roblox đang mở nhưng game không load nổi sau N tick
                        // → reset bằng force-stop + rejoin để thoát kẹt.
                        appendLog(
                            logEntries, LogLevel.WARN,
                            "Game không load sau $noGameMaxStreak lần check (~${noGameMaxStreak * intervalSec.toInt()}s). Force rejoin…"
                        )
                        withContext(Dispatchers.IO) { AutoRejoinManager.forceStop(pkg) }
                        noGameStreak = 0
                        true
                    } else {
                        appendLog(
                            logEntries, LogLevel.INFO,
                            "Roblox đang chạy nhưng chưa load vào game (pid=${report.pid}, streak=$noGameStreak/$noGameMaxStreak). Chờ tick kế tiếp…"
                        )
                        false
                    }
                }
                AutoRejoinManager.RobloxState.NOT_RUNNING -> {
                    noGameStreak = 0
                    appendLog(logEntries, LogLevel.WARN, "Roblox không chạy. Đang khởi động lại…")
                    true
                }
                AutoRejoinManager.RobloxState.DISCONNECTED -> {
                    noGameStreak = 0
                    appendLog(
                        logEntries, LogLevel.WARN,
                        "Phát hiện disconnect/kick: ${report.disconnectHint?.take(120) ?: "?"}"
                    )
                    withContext(Dispatchers.IO) { AutoRejoinManager.forceStop(pkg) }
                    true
                }
            }

            if (needRejoin) {
                val attempts = withContext(Dispatchers.IO) {
                    AutoRejoinManager.rejoin(pkg, placeId, gid)
                }
                rejoinCount += 1
                // Set timestamp NGÂY SAU khi `rejoin` trả về để logcat filter
                // ở vòng tick kế tiếp chỉ đọc dòng mới phát sinh.
                lastRejoinEpochMs = System.currentTimeMillis()
                attempts.forEach { a ->
                    val lvl = if (a.success) LogLevel.OK else LogLevel.ERR
                    val msg = "${a.method}: " +
                        if (a.success) "OK" else "exit=${a.exitCode} ${a.error.take(80)}"
                    appendLog(logEntries, lvl, msg)
                }
                appendLog(
                    logEntries, LogLevel.INFO,
                    "Chờ ${warmupMs / 1000}s để Roblox khởi động + load game trước khi check tiếp…"
                )
                delay(warmupMs)
            } else {
                delay(intervalMs)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = stringResource(R.string.auto_rejoin_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
        )

        AutoRejoinStatusCard(
            rooted = rooted,
            robloxInstalled = robloxInstalled,
            detectedPackage = detectedPackage
        )

        ConfigCard(
            placeId = placeIdInput,
            onPlaceIdChange = { placeIdInput = it.filter { c -> c.isDigit() }.take(16) },
            gameInstanceId = gameInstanceInput,
            // Không `.trim()` tại đây — trim mỗi keystroke sẽ xóa space đang gõ
            // giữa chừng + làm con trỏ nhảy. Trim chỉ được thực hiện khi đọc
            // giá trị để gửi shell trong `LaunchedEffect(running)` ở trên.
            onGameInstanceIdChange = { gameInstanceInput = it },
            intervalSec = intervalSec,
            onIntervalChange = { intervalSec = it },
            enabled = !running
        )

        ControlCard(
            running = running,
            ready = ready,
            currentState = currentState,
            currentPid = currentPid,
            rejoinCount = rejoinCount,
            onToggle = {
                if (!running) {
                    if (!ready) {
                        onShowSnackbar(msgNotReady)
                        return@ControlCard
                    }
                    if (!AutoRejoinManager.isValidPlaceId(placeIdInput)) {
                        onShowSnackbar(msgInvalidPlace)
                        return@ControlCard
                    }
                    rejoinCount = 0
                    logEntries.clear()
                    // Reset timestamp → tick đầu tiên fallback về `-t LINES`
                    // (xem doc `getStatus` cho luật sinceEpochMs > 0 vs = 0).
                    lastRejoinEpochMs = 0L
                    appendLog(
                        logEntries,
                        LogLevel.INFO,
                        "Bắt đầu vòng lặp auto-rejoin cho placeId=${placeIdInput.trim()}"
                    )
                    running = true
                    onShowSnackbar(msgStarted)
                } else {
                    running = false
                    // Reset state hiển thị về idle — nếu không, UI vẫn show
                    // PID + trạng thái của tick check cuối, khiến user nhầm
                    // là vòng lặp chưa dừng.
                    currentState = null
                    currentPid = null
                    appendLog(logEntries, LogLevel.INFO, "Đã dừng vòng lặp.")
                    onShowSnackbar(msgStopped)
                }
            }
        )

        LogCard(entries = logEntries)

        WarningCard()

        Spacer(modifier = Modifier.height(80.dp))
    }
}

private enum class LogLevel { INFO, OK, WARN, ERR }

private data class LogEntry(
    val timestamp: String,
    val level: LogLevel,
    val message: String
)

/** Giới hạn số dòng log để tránh phình memory khi loop chạy nhiều giờ. */
private const val MAX_LOG_ENTRIES = 50

/**
 * Formatter dùng chung cho timestamp log — khởi tạo 1 lần để không tạo object
 * mới mỗi tick polling khi loop chạy nhiều giờ. `SimpleDateFormat` không
 * thread-safe, nhưng `appendLog` chỉ được gọi trên main thread (Compose
 * snapshot updates) nên không cần đồng bộ hóa.
 */
private val LOG_TIME_FMT = SimpleDateFormat("HH:mm:ss", Locale.US)

private fun appendLog(list: MutableList<LogEntry>, level: LogLevel, message: String) {
    val ts = LOG_TIME_FMT.format(Date())
    list.add(0, LogEntry(ts, level, message))
    while (list.size > MAX_LOG_ENTRIES) {
        list.removeAt(list.size - 1)
    }
}

@Composable
private fun AutoRejoinStatusCard(
    rooted: Boolean?,
    robloxInstalled: Boolean?,
    detectedPackage: String?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.auto_rejoin_status_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            CheckRow(
                label = stringResource(R.string.auto_rejoin_status_root_label),
                ready = rooted == true,
                pending = rooted == null,
                hintWhenMissing = stringResource(R.string.auto_rejoin_status_root_hint)
            )

            val robloxLabel = when {
                detectedPackage != null -> stringResource(
                    R.string.auto_rejoin_status_roblox_installed, detectedPackage
                )
                robloxInstalled == false -> stringResource(R.string.auto_rejoin_status_roblox_missing)
                else -> stringResource(R.string.auto_rejoin_status_roblox_checking)
            }
            CheckRow(
                label = robloxLabel,
                ready = robloxInstalled == true,
                pending = robloxInstalled == null,
                hintWhenMissing = stringResource(R.string.auto_rejoin_status_roblox_hint)
            )
        }
    }
}

@Composable
private fun CheckRow(
    label: String,
    ready: Boolean,
    pending: Boolean,
    hintWhenMissing: String
) {
    val (icon, tint, status) = when {
        pending -> Triple(
            Icons.Default.HourglassEmpty,
            MaterialTheme.colorScheme.onSurfaceVariant,
            stringResource(R.string.auto_rejoin_status_state_checking)
        )
        ready -> Triple(
            Icons.Default.CheckCircle,
            MaterialTheme.colorScheme.primary,
            stringResource(R.string.auto_rejoin_status_state_ready)
        )
        else -> Triple(
            Icons.Default.ErrorOutline,
            MaterialTheme.colorScheme.error,
            stringResource(R.string.auto_rejoin_status_state_unavailable)
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = status,
                style = MaterialTheme.typography.labelMedium,
                color = tint,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (!ready && !pending) {
            Text(
                text = hintWhenMissing,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 32.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfigCard(
    placeId: String,
    onPlaceIdChange: (String) -> Unit,
    gameInstanceId: String,
    onGameInstanceIdChange: (String) -> Unit,
    intervalSec: Float,
    onIntervalChange: (Float) -> Unit,
    enabled: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.auto_rejoin_config_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            OutlinedTextField(
                value = placeId,
                onValueChange = onPlaceIdChange,
                enabled = enabled,
                singleLine = true,
                label = { Text(stringResource(R.string.auto_rejoin_config_place_id)) },
                placeholder = { Text(stringResource(R.string.auto_rejoin_config_place_id_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )

            OutlinedTextField(
                value = gameInstanceId,
                onValueChange = onGameInstanceIdChange,
                enabled = enabled,
                singleLine = true,
                label = { Text(stringResource(R.string.auto_rejoin_config_game_instance)) },
                placeholder = { Text(stringResource(R.string.auto_rejoin_config_game_instance_hint)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )

            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.auto_rejoin_config_interval),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = stringResource(
                            R.string.auto_rejoin_config_interval_value,
                            intervalSec.toInt()
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = intervalSec,
                    onValueChange = onIntervalChange,
                    valueRange = 5f..60f,
                    steps = 10,
                    enabled = enabled
                )
            }
        }
    }
}

@Composable
private fun ControlCard(
    running: Boolean,
    ready: Boolean,
    currentState: AutoRejoinManager.RobloxState?,
    currentPid: Int?,
    rejoinCount: Int,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (running)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Replay,
                    contentDescription = null,
                    tint = if (running)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.auto_rejoin_control_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            val stateLabel = when (currentState) {
                AutoRejoinManager.RobloxState.IN_GAME ->
                    stringResource(R.string.auto_rejoin_state_in_game)
                AutoRejoinManager.RobloxState.IN_GAME_WRONG_PLACE ->
                    stringResource(R.string.auto_rejoin_state_in_game_wrong)
                AutoRejoinManager.RobloxState.FOREGROUND_NO_GAME ->
                    stringResource(R.string.auto_rejoin_state_foreground_no_game)
                AutoRejoinManager.RobloxState.NOT_RUNNING ->
                    stringResource(R.string.auto_rejoin_state_not_running)
                AutoRejoinManager.RobloxState.DISCONNECTED ->
                    stringResource(R.string.auto_rejoin_state_disconnected)
                null -> stringResource(R.string.auto_rejoin_state_idle)
            }

            Text(
                text = stringResource(R.string.auto_rejoin_state_format, stateLabel),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = stringResource(
                    R.string.auto_rejoin_pid_format,
                    currentPid?.toString() ?: "—"
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.auto_rejoin_count_format, rejoinCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = onToggle,
                enabled = ready || running,
                modifier = Modifier.fillMaxWidth(),
                colors = if (running)
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                else
                    ButtonDefaults.buttonColors()
            ) {
                Icon(
                    if (running) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(
                        if (running) R.string.auto_rejoin_control_stop
                        else R.string.auto_rejoin_control_start
                    ),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun LogCard(entries: List<LogEntry>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.auto_rejoin_log_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.auto_rejoin_log_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                entries.take(MAX_LOG_ENTRIES).forEach { entry ->
                    LogRow(entry)
                }
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val tint = when (entry.level) {
        LogLevel.OK -> MaterialTheme.colorScheme.primary
        LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
        LogLevel.ERR -> MaterialTheme.colorScheme.error
        LogLevel.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = entry.timestamp,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = entry.level.name,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = tint
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun WarningCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.auto_rejoin_warning_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = stringResource(R.string.auto_rejoin_warning_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}
