package com.kasumi.tool

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Tab "Đồng bộ" — sao lưu/khôi phục dữ liệu config của các script Delta client
 * (thư mục /storage/emulated/0/Delta/Workspace) qua database Neon, giúp mang
 * config từ máy này sang máy khác.
 *
 * Người dùng nhập tên đồng bộ (profile), bấm "Đồng bộ lên" để đẩy config lên
 * DB, hoặc "Tải về máy" ở máy khác để khôi phục. Phần dưới hiển thị các tệp
 * config đang lưu trên DB kèm nút cập nhật và nút xoá.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    onShowSnackbar: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val manager = remember {
        NeonSyncManager((context.applicationContext as KasumiApplication).okHttpClient)
    }

    var hasStorage by remember { mutableStateOf(hasStoragePermission()) }
    var profileName by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf<String?>(null) }

    var localCount by remember { mutableIntStateOf(0) }
    var localSize by remember { mutableLongStateOf(0L) }
    var workspaceExists by remember { mutableStateOf(true) }

    // null = chưa tải; danh sách rỗng = đã tải nhưng DB không có dữ liệu.
    var remote by remember { mutableStateOf<List<NeonSyncManager.RemoteEntry>?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    fun refreshLocal() {
        scope.launch {
            val scan = manager.scanLocal()
            localCount = scan.files.size
            localSize = scan.files.sumOf { it.size }
            workspaceExists = NeonSyncManager.workspaceDir.exists()
        }
    }

    fun refreshRemote() {
        val name = profileName.trim()
        if (name.isEmpty()) return
        scope.launch {
            working = true
            progressText = "Đang tải danh sách config từ database…"
            try {
                remote = manager.fetchRemote(name)
            } catch (e: Exception) {
                onShowSnackbar("Lỗi khi tải dữ liệu: ${e.message}")
            } finally {
                working = false
                progressText = null
            }
        }
    }

    LaunchedEffect(hasStorage) {
        if (hasStorage) refreshLocal()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Đồng bộ Config",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
        )

        InfoCard()

        if (!hasStorage) {
            PermissionCard(onGrant = {
                requestAllFilesAccess(context)
                hasStorage = hasStoragePermission()
            })
        } else {
            LocalStatusCard(
                exists = workspaceExists,
                count = localCount,
                size = localSize
            )
        }

        // Nhập tên đồng bộ
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
                    text = "Tên đồng bộ",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Đặt một tên để nhận diện bộ config (ví dụ tên máy của bạn). " +
                        "Dùng lại đúng tên này ở máy khác để tải về.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = profileName,
                    onValueChange = { profileName = it },
                    singleLine = true,
                    label = { Text("Tên") },
                    placeholder = { Text("vd: may-cua-toi") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !working
                )

                val nameOk = profileName.trim().isNotEmpty()
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            val name = profileName.trim()
                            scope.launch {
                                working = true
                                try {
                                    val n = manager.syncUp(name) { done, total, fileName ->
                                        progressText = "Đang tải lên $done/$total: $fileName"
                                    }
                                    onShowSnackbar("Đã đồng bộ $n tệp config lên database.")
                                    refreshLocal()
                                    remote = manager.fetchRemote(name)
                                } catch (e: Exception) {
                                    onShowSnackbar("Đồng bộ thất bại: ${e.message}")
                                } finally {
                                    working = false
                                    progressText = null
                                }
                            }
                        },
                        enabled = nameOk && !working && hasStorage,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (remote.isNullOrEmpty()) "Đồng bộ lên" else "Cập nhật đồng bộ")
                    }
                    OutlinedButton(
                        onClick = {
                            val name = profileName.trim()
                            scope.launch {
                                working = true
                                try {
                                    val n = manager.syncDown(name) { done, total, fileName ->
                                        progressText = "Đang tải về $done/$total: $fileName"
                                    }
                                    if (n == 0) {
                                        onShowSnackbar("Không có config nào trên database cho tên này.")
                                    } else {
                                        onShowSnackbar("Đã tải $n tệp config về máy.")
                                    }
                                    refreshLocal()
                                } catch (e: Exception) {
                                    onShowSnackbar("Tải về thất bại: ${e.message}")
                                } finally {
                                    working = false
                                    progressText = null
                                }
                            }
                        },
                        enabled = nameOk && !working && hasStorage,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Tải về máy")
                    }
                }

                TextButton(
                    onClick = { refreshRemote() },
                    enabled = nameOk && !working
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Xem dữ liệu trên database")
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
                        text = progressText ?: "Đang xử lý…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        RemoteDataSection(
            profileName = profileName.trim(),
            remote = remote,
            working = working,
            onDelete = { showDeleteDialog = true }
        )

        Spacer(modifier = Modifier.height(80.dp))
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) },
            title = { Text("Xoá dữ liệu config?") },
            text = {
                Text(
                    "Toàn bộ config của tên \"${profileName.trim()}\" sẽ bị xoá khỏi " +
                        "database. Tệp trên máy không bị ảnh hưởng. Hành động này không " +
                        "thể hoàn tác."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    val name = profileName.trim()
                    scope.launch {
                        working = true
                        progressText = "Đang xoá dữ liệu…"
                        try {
                            val n = manager.deleteRemote(name)
                            remote = emptyList()
                            onShowSnackbar("Đã xoá $n tệp config khỏi database.")
                        } catch (e: Exception) {
                            onShowSnackbar("Xoá thất bại: ${e.message}")
                        } finally {
                            working = false
                            progressText = null
                        }
                    }
                }) { Text("Xoá") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Huỷ") }
            }
        )
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
                    text = "Cách hoạt động",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = "Đồng bộ config của các script trong Delta client giữa các máy. " +
                    "Nguồn dữ liệu:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "/storage/emulated/0/Delta/Workspace",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun PermissionCard(onGrant: () -> Unit) {
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
                text = "Cần quyền truy cập bộ nhớ",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = "Ứng dụng cần quyền quản lý tất cả tệp để đọc thư mục " +
                    "Workspace của Delta.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Button(onClick = onGrant) {
                Text("Cấp quyền")
            }
        }
    }
}

@Composable
private fun LocalStatusCard(exists: Boolean, count: Int, size: Long) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = "Config trên máy này",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (!exists) {
                        "Chưa tìm thấy thư mục Workspace."
                    } else {
                        "$count tệp • ${formatBytes(size)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RemoteDataSection(
    profileName: String,
    remote: List<NeonSyncManager.RemoteEntry>?,
    working: Boolean,
    onDelete: () -> Unit
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
            Text(
                text = "Dữ liệu trên database",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            when {
                profileName.isEmpty() -> {
                    Text(
                        text = "Nhập tên đồng bộ để xem dữ liệu.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                remote == null -> {
                    Text(
                        text = "Chưa có dữ liệu. Nhập tên và bấm \"Đồng bộ lên\" để tải " +
                            "config lên, hoặc \"Xem dữ liệu trên database\" để kiểm tra.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                remote.isEmpty() -> {
                    Text(
                        text = "Chưa có config nào trên database cho tên \"$profileName\". " +
                            "Bấm \"Đồng bộ lên\" để bắt đầu.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    val total = remote.sumOf { it.size }
                    Text(
                        text = "${remote.size} tệp • ${formatBytes(total)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    )
                    remote.forEach { entry ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Description,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.path,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = formatBytes(entry.size) +
                                        (if (entry.isBinary) " • nhị phân" else ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    )
                    OutlinedButton(
                        onClick = onDelete,
                        enabled = !working,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Xoá dữ liệu config khỏi database")
                    }
                }
            }
        }
    }
}

private fun hasStoragePermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }
}

private fun requestAllFilesAccess(context: android.content.Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    try {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
        intent.data = Uri.parse("package:${context.packageName}")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: Exception) {
        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(java.util.Locale.US, "%.1f MB", mb)
}
