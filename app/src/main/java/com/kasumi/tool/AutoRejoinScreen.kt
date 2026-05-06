package com.kasumi.tool

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

/**
 * Tab "Auto Rejoin Roblox" — UI cho vòng lặp polling auto-rejoin.
 *
 * Vòng lặp **không** chạy trong Composable nữa — đã chuyển sang
 * [AutoRejoinService] (foreground service) để tiếp tục chạy khi user
 * switch sang Roblox app. UI chỉ còn nhiệm vụ:
 *  - Phát hiện root + bản Roblox đã cài.
 *  - Nhận input (placeId, gameInstanceId, interval).
 *  - Start/stop service qua Intent helpers.
 *  - Observe [AutoRejoinService.state] qua [collectAsState] để render
 *    state hiện tại, log, counter — tự đồng bộ khi service cập nhật
 *    (kể cả khi user bấm "Dừng" từ notification).
 *
 * UI gồm 5 khối:
 *  1. **StatusCard**: hiển thị quyền root + bản Roblox được phát hiện.
 *  2. **ConfigCard**: input PlaceId, optional GameInstanceId, slider chu kỳ
 *     check (5–60s). Disable khi service đang chạy để tránh user đổi
 *     giữa chừng.
 *  3. **ControlCard**: nút Start / Stop, hiển thị state hiện tại + tổng số
 *     lần đã rejoin từ lúc bật.
 *  4. **LogCard**: log cuộn các sự kiện gần đây (tối đa 50 dòng).
 *  5. **WarningCard**: lưu ý về vận hành.
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
    val msgNotifPermDenied = stringResource(R.string.auto_rejoin_snackbar_notif_perm_denied)

    var rooted by remember { mutableStateOf<Boolean?>(null) }
    var detectedPackage by remember { mutableStateOf<String?>(null) }
    var robloxInstalled by remember { mutableStateOf<Boolean?>(null) }

    // Cấu hình dùng `rememberSaveable` để user không mất Place ID / cycle khi
    // chuyển tab và quay lại (composable bị dispose).
    var placeIdInput by rememberSaveable { mutableStateOf("") }
    var gameInstanceInput by rememberSaveable { mutableStateOf("") }
    var intervalSec by rememberSaveable { mutableFloatStateOf(15f) }

    // State runtime của vòng lặp đến từ [AutoRejoinService] (StateFlow
    // singleton). Khi Composable bị dispose rồi recompose, observation tự
    // thiết lập lại → UI hiển thị đúng state hiện tại dù user đã chuyển
    // tab và quay lại nhiều lần.
    val uiState by AutoRejoinService.state.collectAsState()

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

    // Helper khởi động service sau khi đã đảm bảo quyền + validate input.
    fun launchService() {
        val pkg = detectedPackage ?: return
        AutoRejoinService.start(
            context = context,
            pkg = pkg,
            placeId = placeIdInput.trim(),
            gameInstanceId = gameInstanceInput.trim().ifEmpty { null },
            intervalSec = intervalSec.toInt().coerceIn(5, 60),
        )
        onShowSnackbar(msgStarted)
    }

    // POST_NOTIFICATIONS là runtime permission từ Android 13 (API 33). Nếu
    // user deny, foreground service vẫn chạy nhưng notification sẽ bị OS
    // suppress → user không thấy trang thái ngoài app + không bấm Dừng từ
    // notification được. Hiển thị snackbar cảnh báo nhưng vẫn cho tiếp.
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) onShowSnackbar(msgNotifPermDenied)
        launchService()
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
            // giá trị để gửi service trong [launchService].
            onGameInstanceIdChange = { gameInstanceInput = it },
            intervalSec = intervalSec,
            onIntervalChange = { intervalSec = it },
            enabled = !uiState.running
        )

        ControlCard(
            running = uiState.running,
            ready = ready,
            currentState = uiState.currentState,
            currentPid = uiState.currentPid,
            rejoinCount = uiState.rejoinCount,
            onToggle = {
                if (!uiState.running) {
                    if (!ready) {
                        onShowSnackbar(msgNotReady)
                        return@ControlCard
                    }
                    if (!AutoRejoinManager.isValidPlaceId(placeIdInput)) {
                        onShowSnackbar(msgInvalidPlace)
                        return@ControlCard
                    }
                    // Android 13+: request POST_NOTIFICATIONS rồi mới start.
                    // Trên Android 12-: launchService() ngay (không cần perm).
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val granted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) launchService()
                        else notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        launchService()
                    }
                } else {
                    AutoRejoinService.stop(context)
                    onShowSnackbar(msgStopped)
                }
            }
        )

        LogCard(entries = uiState.logs)

        WarningCard()

        Spacer(modifier = Modifier.height(80.dp))
    }
}

// LogLevel / LogEntry / MAX_LOG_ENTRIES / formatter / appendLog đã chuyển sang
// [AutoRejoinService] — vòng lặp polling ghi log vào StateFlow chung để UI
// observe.
private const val MAX_LOG_ENTRIES_UI = 50

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
                entries.take(MAX_LOG_ENTRIES_UI).forEach { entry ->
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
