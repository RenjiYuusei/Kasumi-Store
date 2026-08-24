package com.kasumi.tool

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Tab "Bypass Key Delta" — người dùng chỉ cần dán link getkey Delta
 * (platoboost/platorelay) và bấm "Lấy key".
 *
 * Địa chỉ API bypass là cố định của dự án, được đọc NGẦM từ remote config
 * (file JSON trên GitHub) nên người dùng không phải cấu hình gì. Khi API đổi,
 * chỉ cần cập nhật file config đó — không phải build lại app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BypassKeyDeltaScreen(
    onShowSnackbar: (String) -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val manager = remember {
        DeltaBypassManager((context.applicationContext as KasumiApplication).okHttpClient)
    }

    // Địa chỉ API cố định, lấy ngầm từ remote config.
    var apiUrl by remember { mutableStateOf<String?>(null) }
    var loadingConfig by remember { mutableStateOf(false) }
    var configFailed by remember { mutableStateOf(false) }

    var link by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<DeltaBypassManager.BypassResult?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    fun loadConfig() {
        scope.launch {
            loadingConfig = true
            configFailed = false
            try {
                apiUrl = manager.fetchRemoteApiUrl()
                configFailed = apiUrl.isNullOrBlank()
            } finally {
                loadingConfig = false
            }
        }
    }

    LaunchedEffect(Unit) { loadConfig() }

    fun runBypass() {
        if (working) return
        val base = apiUrl
        if (base.isNullOrBlank()) {
            errorMsg = resources.getString(R.string.bypass_service_unavailable)
            return
        }
        result = null
        errorMsg = null
        scope.launch {
            working = true
            try {
                result = manager.bypass(base, link)
            } catch (e: DeltaBypassManager.BypassException) {
                errorMsg = e.text.asString(context)
            } catch (e: Exception) {
                errorMsg = e.message ?: resources.getString(R.string.bypass_error_unknown)
            } finally {
                working = false
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
            text = stringResource(R.string.bypass_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
        )

        InfoCard()

        // Báo lỗi khi không tải được cấu hình dịch vụ (kèm nút thử lại)
        if (configFailed) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.bypass_config_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    OutlinedButton(
                        onClick = { loadConfig() },
                        enabled = !loadingConfig
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.bypass_retry))
                    }
                }
            }
        }

        // Nhập link Delta
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
                Text(
                    text = "Link Delta",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.bypass_input_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    singleLine = true,
                    label = { Text("Link / token") },
                    placeholder = { Text("https://platoboost.com/...") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !working
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val text = clipboard.readPlainText()
                                if (text.isNullOrBlank()) {
                                    onShowSnackbar(resources.getString(R.string.bypass_clipboard_empty))
                                } else {
                                    link = text.trim()
                                }
                            }
                        },
                        enabled = !working,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.bypass_paste))
                    }
                    OutlinedButton(
                        onClick = { link = "" },
                        enabled = !working && link.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.bypass_clear))
                    }
                }

                Button(
                    onClick = { runBypass() },
                    enabled = !working && !loadingConfig && !apiUrl.isNullOrBlank() &&
                        link.trim().isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (loadingConfig) R.string.bypass_preparing else R.string.bypass_get_key))
                }
            }
        }

        if (working) {
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
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(R.string.bypass_working_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        errorMsg?.let { msg ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        result?.let { res ->
            ResultCard(
                res = res,
                onCopy = {
                    scope.launch {
                        clipboard.setPlainText("Delta key", res.key)
                        onShowSnackbar(resources.getString(R.string.bypass_key_copied))
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}

@Composable
private fun InfoCard() {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.bypass_howto_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = stringResource(R.string.bypass_howto_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ResultCard(
    res: DeltaBypassManager.BypassResult,
    onCopy: () -> Unit
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
                    Icons.Default.VpnKey,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.bypass_your_key),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            SelectionContainer {
                Text(
                    text = res.key,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            res.minutesLeft?.let { mins ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.bypass_remaining_minutes, mins) +
                            (if (mins >= 60) stringResource(R.string.bypass_remaining_hours_suffix, mins / 60) else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            res.elapsedSeconds?.let { sec ->
                Text(
                    text = stringResource(
                        R.string.bypass_elapsed,
                        String.format(java.util.Locale.US, "%.1f", sec),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

            Button(
                onClick = onCopy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.bypass_copy_key))
            }
        }
    }
}
