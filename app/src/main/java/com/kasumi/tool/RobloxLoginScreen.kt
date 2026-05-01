package com.kasumi.tool

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tab "Login Roblox" - cho phép trích xuất cookie .ROBLOSECURITY từ ứng dụng
 * Roblox đã đăng nhập, hoặc chèn cookie để đăng nhập trực tiếp vào tài khoản
 * khác mà không cần tài khoản/mật khẩu.
 *
 * Yêu cầu thiết bị có quyền root (su).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RobloxLoginScreen(
    onShowSnackbar: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var rooted by remember { mutableStateOf<Boolean?>(null) }
    var robloxInstalled by remember { mutableStateOf<Boolean?>(null) }
    var detectedPackage by remember { mutableStateOf<String?>(null) }

    var extracting by remember { mutableStateOf(false) }
    var injecting by remember { mutableStateOf(false) }

    var extractedCookie by remember { mutableStateOf<String?>(null) }
    var cookieRevealed by remember { mutableStateOf(false) }

    var cookieInput by remember { mutableStateOf("") }
    var lastResult by remember { mutableStateOf<RobloxLoginManager.Outcome?>(null) }

    LaunchedEffect(Unit) {
        rooted = withContext(Dispatchers.IO) { RootInstaller.isDeviceRooted() }
        val pkg = withContext(Dispatchers.IO) {
            RobloxLoginManager.detectActivePackage(context)
        }
        detectedPackage = pkg
        robloxInstalled = pkg != null
    }

    val ready = rooted == true && robloxInstalled == true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = stringResource(R.string.roblox_login_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
        )

        StatusCard(
            rooted = rooted,
            robloxInstalled = robloxInstalled,
            detectedPackage = detectedPackage
        )

        ExtractSection(
            enabled = ready && !extracting && !injecting,
            isLoading = extracting,
            cookie = extractedCookie,
            cookieRevealed = cookieRevealed,
            onToggleReveal = { cookieRevealed = !cookieRevealed },
            onExtract = {
                scope.launch {
                    extracting = true
                    extractedCookie = null
                    lastResult = null
                    val outcome = withContext(Dispatchers.IO) {
                        RobloxLoginManager.extractCookie(context)
                    }
                    lastResult = outcome
                    extracting = false
                    if (outcome.success && outcome.cookie != null) {
                        extractedCookie = outcome.cookie
                        cookieRevealed = false
                        onShowSnackbar(outcome.message)
                    } else {
                        onShowSnackbar(outcome.message)
                    }
                }
            },
            onCopy = { cookie ->
                clipboardManager.setText(AnnotatedString(cookie))
                // Trên Android 13+, hệ thống đã hiển thị toast tự động; vẫn giữ snackbar.
                onShowSnackbar("Đã sao chép cookie .ROBLOSECURITY")
            },
            onUseAsLogin = { cookie ->
                cookieInput = cookie
                onShowSnackbar("Đã dán cookie vào ô đăng nhập bên dưới")
            }
        )

        InjectSection(
            enabled = ready && !extracting && !injecting,
            isLoading = injecting,
            value = cookieInput,
            onValueChange = { cookieInput = it },
            onPasteFromClipboard = {
                val clipboardText = clipboardManager.getText()?.toString()
                if (clipboardText.isNullOrBlank()) {
                    onShowSnackbar("Clipboard trống")
                } else {
                    cookieInput = clipboardText.trim()
                    onShowSnackbar("Đã dán cookie từ clipboard")
                }
            },
            onClear = { cookieInput = "" },
            onLogin = {
                val cookie = cookieInput.trim()
                if (cookie.isEmpty()) {
                    onShowSnackbar("Hãy dán cookie .ROBLOSECURITY trước")
                    return@InjectSection
                }
                if (!RobloxLoginManager.isCookieFormatValid(cookie)) {
                    onShowSnackbar("Cookie không hợp lệ. Phải bắt đầu bằng `_|WARNING:`.")
                    return@InjectSection
                }
                scope.launch {
                    injecting = true
                    lastResult = null
                    val outcome = withContext(Dispatchers.IO) {
                        RobloxLoginManager.injectCookie(context, cookie)
                    }
                    lastResult = outcome
                    injecting = false
                    onShowSnackbar(outcome.message)
                }
            }
        )

        lastResult?.let { result ->
            ResultLogCard(result)
        }

        WarningCard()

        Spacer(modifier = Modifier.height(80.dp))
    }
}

@Composable
private fun StatusCard(
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
                    text = "Trạng thái thiết bị",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            StatusRow(
                label = "Quyền Root (su)",
                ready = rooted == true,
                pending = rooted == null,
                hintWhenMissing = "Cần root (Magisk/KernelSU) để truy cập database cookie."
            )
            val robloxLabel = when {
                detectedPackage != null -> "Roblox đã cài ($detectedPackage)"
                robloxInstalled == false -> "Roblox chưa cài (com.roblox.client / .vnggames)"
                else -> "Roblox (đang kiểm tra…)"
            }
            StatusRow(
                label = robloxLabel,
                ready = robloxInstalled == true,
                pending = robloxInstalled == null,
                hintWhenMissing = "Hãy cài đặt Roblox (bản global hoặc VNG) và mở ít nhất 1 lần để khởi tạo dữ liệu."
            )
        }
    }
}

@Composable
private fun StatusRow(
    label: String,
    ready: Boolean,
    pending: Boolean,
    hintWhenMissing: String
) {
    val (icon, tint, status) = when {
        pending -> Triple(Icons.Default.Warning, MaterialTheme.colorScheme.onSurfaceVariant, "Đang kiểm tra…")
        ready -> Triple(Icons.Default.CheckCircle, MaterialTheme.colorScheme.primary, "Sẵn sàng")
        else -> Triple(Icons.Default.Error, MaterialTheme.colorScheme.error, "Không khả dụng")
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = tint
                )
            }
        }
        if (!ready && !pending) {
            Text(
                text = hintWhenMissing,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 34.dp, top = 2.dp)
            )
        }
    }
}

@Composable
private fun ExtractSection(
    enabled: Boolean,
    isLoading: Boolean,
    cookie: String?,
    cookieRevealed: Boolean,
    onToggleReveal: () -> Unit,
    onExtract: () -> Unit,
    onCopy: (String) -> Unit,
    onUseAsLogin: (String) -> Unit
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
                Icon(Icons.Default.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.roblox_login_extract_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = stringResource(R.string.roblox_login_extract_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = onExtract,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Đang đọc cookie…")
                } else {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Đọc cookie từ Roblox")
                }
            }

            if (cookie != null) {
                CookiePreview(
                    cookie = cookie,
                    revealed = cookieRevealed,
                    onToggleReveal = onToggleReveal
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onCopy(cookie) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Sao chép")
                    }
                    OutlinedButton(
                        onClick = { onUseAsLogin(cookie) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Dùng để login")
                    }
                }
            }
        }
    }
}

@Composable
private fun CookiePreview(cookie: String, revealed: Boolean, onToggleReveal: () -> Unit) {
    val display = if (revealed) cookie else maskCookie(cookie)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Cookie .ROBLOSECURITY (${cookie.length} ký tự)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onToggleReveal, modifier = Modifier.size(28.dp)) {
                    Icon(
                        if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (revealed) "Ẩn" else "Hiện",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Text(
                text = display,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun maskCookie(cookie: String): String {
    if (cookie.length <= 24) return "•".repeat(cookie.length)
    val head = cookie.take(12)
    val tail = cookie.takeLast(8)
    return "$head${"•".repeat(20)}$tail"
}

@Composable
private fun InjectSection(
    enabled: Boolean,
    isLoading: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
    onPasteFromClipboard: () -> Unit,
    onClear: () -> Unit,
    onLogin: () -> Unit
) {
    var hidden by remember { mutableStateOf(true) }
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
                Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.roblox_login_inject_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = stringResource(R.string.roblox_login_inject_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(".ROBLOSECURITY") },
                placeholder = { Text("_|WARNING:-DO-NOT-SHARE-THIS…") },
                singleLine = false,
                maxLines = 4,
                visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
                shape = RoundedCornerShape(14.dp),
                trailingIcon = {
                    IconButton(onClick = { hidden = !hidden }) {
                        Icon(
                            if (hidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (hidden) "Hiện" else "Ẩn"
                        )
                    }
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onPasteFromClipboard,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isLoading
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Dán")
                }
                OutlinedButton(
                    onClick = onClear,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    enabled = value.isNotEmpty() && !isLoading
                ) {
                    Text("Xóa")
                }
            }

            Button(
                onClick = onLogin,
                enabled = enabled && value.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Đang đăng nhập…")
                } else {
                    Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Đăng nhập")
                }
            }
        }
    }
}

@Composable
private fun ResultLogCard(result: RobloxLoginManager.Outcome) {
    val borderColor = if (result.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (result.success) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = borderColor
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (result.success) "Thành công" else "Lỗi",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = borderColor
                )
            }
            Text(
                text = result.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (result.steps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Nhật ký",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                result.steps.forEach { step ->
                    val color = if (step.success) Color(0xFF66BB6A) else MaterialTheme.colorScheme.error
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = if (step.success) "✓" else "✗",
                            color = color,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = step.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            val detail = listOfNotNull(
                                step.output.takeIf { it.isNotBlank() },
                                step.error.takeIf { it.isNotBlank() }
                            ).joinToString(" | ")
                            if (detail.isNotBlank()) {
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WarningCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
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
                    text = stringResource(R.string.roblox_login_warning_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = stringResource(R.string.roblox_login_warning_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

