package com.kasumi.tool

import android.content.Intent
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.launch

/**
 * Tab "Đồng bộ" — sao lưu/khôi phục toàn bộ dữ liệu Delta client
 * (thư mục /storage/emulated/0/Delta: Workspace, Scripts, Autoexecute, …)
 * qua database Neon, giúp mang script và config từ máy này sang máy khác.
 *
 * Người dùng nhập tên đồng bộ (profile), bấm "Đồng bộ lên" để đẩy dữ liệu lên
 * DB, hoặc "Tải về máy" ở máy khác để khôi phục. Phần dưới hiển thị các tệp
 * đang lưu trên DB kèm nút cập nhật và nút xoá.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    onShowSnackbar: (String) -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val manager = remember {
        NeonSyncManager(
            context.applicationContext,
            (context.applicationContext as KasumiApplication).okHttpClient,
        )
    }

    var hasStorage by remember { mutableStateOf(hasStoragePermission()) }
    var profileName by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf<String?>(null) }

    var localCount by remember { mutableIntStateOf(0) }
    var localSize by remember { mutableLongStateOf(0L) }
    var deltaExists by remember { mutableStateOf(true) }

    // null = chưa tải; danh sách rỗng = đã tải nhưng DB không có dữ liệu.
    var remote by remember { mutableStateOf<List<NeonSyncManager.RemoteEntry>?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    fun refreshLocal() {
        scope.launch {
            val scan = manager.scanLocal()
            localCount = scan.files.size
            localSize = scan.files.sumOf { it.size }
            deltaExists = NeonSyncManager.deltaDir.exists()
        }
    }

    fun refreshRemote() {
        val name = profileName.trim()
        if (name.isEmpty()) return
        scope.launch {
            working = true
            progressText = resources.getString(R.string.sync_progress_fetch_remote)
            try {
                remote = manager.fetchRemote(name)
            } catch (e: Exception) {
                onShowSnackbar(resources.getString(R.string.sync_error_fetch, e.message ?: ""))
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
            text = stringResource(R.string.sync_title),
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
                exists = deltaExists,
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
                    text = stringResource(R.string.sync_profile_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.sync_profile_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = profileName,
                    onValueChange = { profileName = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.sync_profile_label)) },
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
                                        progressText = resources.getString(R.string.sync_progress_upload, done, total, fileName)
                                    }
                                    onShowSnackbar(resources.getString(R.string.sync_upload_success, n))
                                    refreshLocal()
                                    remote = manager.fetchRemote(name)
                                } catch (e: Exception) {
                                    onShowSnackbar(resources.getString(R.string.sync_upload_failed, e.message ?: ""))
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
                        Text(stringResource(if (remote.isNullOrEmpty()) R.string.sync_button_upload else R.string.sync_button_update))
                    }
                    OutlinedButton(
                        onClick = {
                            val name = profileName.trim()
                            scope.launch {
                                working = true
                                try {
                                    val n = manager.syncDown(name) { done, total, fileName ->
                                        progressText = resources.getString(R.string.sync_progress_download, done, total, fileName)
                                    }
                                    if (n == 0) {
                                        onShowSnackbar(resources.getString(R.string.sync_download_empty))
                                    } else {
                                        onShowSnackbar(resources.getString(R.string.sync_download_success, n))
                                    }
                                    refreshLocal()
                                } catch (e: Exception) {
                                    onShowSnackbar(resources.getString(R.string.sync_download_failed, e.message ?: ""))
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
                        Text(stringResource(R.string.sync_button_download))
                    }
                }

                TextButton(
                    onClick = { refreshRemote() },
                    enabled = nameOk && !working
                ) {
                    Icon(Icons.Default.Sync, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sync_button_view_remote))
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
                        text = progressText ?: stringResource(R.string.sync_progress_default),
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
            title = { Text(stringResource(R.string.sync_delete_title)) },
            text = {
                Text(
                    stringResource(R.string.sync_delete_message, profileName.trim())
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    val name = profileName.trim()
                    scope.launch {
                        working = true
                        progressText = resources.getString(R.string.sync_progress_delete)
                        try {
                            val n = manager.deleteRemote(name)
                            remote = emptyList()
                            onShowSnackbar(resources.getString(R.string.sync_delete_success, n))
                        } catch (e: Exception) {
                            onShowSnackbar(resources.getString(R.string.sync_delete_failed, e.message ?: ""))
                        } finally {
                            working = false
                            progressText = null
                        }
                    }
                }) { Text(stringResource(R.string.sync_button_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.cancel)) }
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
                    text = stringResource(R.string.sync_info_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = stringResource(R.string.sync_info_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "/storage/emulated/0/Delta",
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
                text = stringResource(R.string.sync_permission_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                text = stringResource(R.string.sync_permission_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Button(onClick = onGrant) {
                Text(stringResource(R.string.sync_permission_button))
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
                    text = stringResource(R.string.sync_local_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (!exists) {
                        stringResource(R.string.sync_local_missing)
                    } else {
                        stringResource(R.string.sync_files_summary, count, formatFileSize(size))
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
                text = stringResource(R.string.sync_remote_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            when {
                profileName.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.sync_remote_enter_name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                remote == null -> {
                    Text(
                        text = stringResource(R.string.sync_remote_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                remote.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.sync_remote_none_for_name, profileName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    val total = remote.sumOf { it.size }
                    Text(
                        text = stringResource(R.string.sync_files_summary, remote.size, formatFileSize(total)),
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
                                    text = formatFileSize(entry.size) +
                                        (if (entry.isBinary) " • " + stringResource(R.string.sync_entry_binary) else ""),
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
                        Text(stringResource(R.string.sync_button_delete_remote))
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
        intent.data = "package:${context.packageName}".toUri()
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: Exception) {
        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}


