package com.kasumi.tool

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade

private val CACHED_DOT_COLOR = Color(0xFF66BB6A)

/** Aggregate numbers shown above the list. */
private data class CacheSummary(val count: Int, val size: Long)

private fun summarise(apps: List<ApkItem>, stats: Map<String, FileStats>): CacheSummary {
    var count = 0
    var size = 0L
    for (app in apps) {
        val s = stats[app.id] ?: continue
        if (s.exists) {
            count++
            size += s.size
        }
    }
    return CacheSummary(count, size)
}

/** The apps tab: catalogue list, cache summary and the clear-cache action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(viewModel: AppsViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val filteredApps by viewModel.filteredApps.collectAsStateWithLifecycle()
    var showClearCacheConfirm by rememberSaveable { mutableStateOf(false) }

    // Both summaries walk the whole list, so they are cached until their inputs
    // change instead of being recomputed on every recomposition.
    val visibleSummary = remember(filteredApps, uiState.fileStats) {
        summarise(filteredApps, uiState.fileStats)
    }
    // clearCache() wipes every cached APK, not just the filtered subset, so the
    // confirmation dialog has to quote the full totals.
    val totalSummary = remember(uiState.apps, uiState.fileStats) {
        summarise(uiState.apps, uiState.fileStats)
    }

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            contentPadding = PaddingValues(top = 4.dp, bottom = 80.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "summary", contentType = "summary") {
                SummaryRow(
                    total = filteredApps.size,
                    summary = visibleSummary,
                    showClearCache = totalSummary.count > 0,
                    onClearCache = { showClearCacheConfirm = true },
                )
            }

            if (filteredApps.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    EmptyState(
                        icon = Icons.Default.SearchOff,
                        title = stringResource(R.string.no_apps_title),
                        subtitle = stringResource(R.string.no_apps_subtitle),
                    )
                }
            } else {
                items(
                    items = filteredApps,
                    key = { it.id },
                    contentType = { "app" },
                ) { item ->
                    AppItemRow(
                        item = item,
                        stats = uiState.fileStats[item.id],
                        onInstall = viewModel::install,
                        // Animates reordering after a sort or a filter change,
                        // instead of the old per-index staggered entrance that
                        // replayed on every scroll.
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }

    if (showClearCacheConfirm) {
        ClearCacheDialog(
            summary = totalSummary,
            onDismiss = { showClearCacheConfirm = false },
            onConfirm = {
                showClearCacheConfirm = false
                viewModel.clearCache()
            },
        )
    }
}

@Composable
private fun SummaryRow(
    total: Int,
    summary: CacheSummary,
    showClearCache: Boolean,
    onClearCache: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val statsText = if (summary.count > 0) {
            stringResource(
                R.string.stats_format,
                total,
                "${summary.count} (${formatFileSize(summary.size)})",
            )
        } else {
            stringResource(R.string.stats_format_no_cache, total)
        }

        Text(
            text = statsText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (showClearCache) {
            TextButton(
                onClick = onClearCache,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.clear_cache), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ClearCacheDialog(
    summary: CacheSummary,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(24.dp),
        icon = {
            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        },
        title = { Text(stringResource(R.string.clear_cache_confirm_title), fontWeight = FontWeight.Bold) },
        text = {
            Text(
                stringResource(
                    R.string.clear_cache_confirm_message,
                    summary.count,
                    formatFileSize(summary.size),
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.clear_cache))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@Composable
private fun AppItemRow(
    item: ApkItem,
    stats: FileStats?,
    onInstall: (ApkItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val isCached = stats?.exists == true
    val fileSize = stats?.size ?: 0L

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        onClick = { onInstall(item) },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(
            width = 1.dp,
            color = if (isCached) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (item.iconUrl != null) {
                AsyncImage(
                    model = remember(item.iconUrl) {
                        ImageRequest.Builder(context)
                            .data(item.iconUrl)
                            .crossfade(true)
                            .build()
                    },
                    contentDescription = stringResource(R.string.app_icon_desc, item.name),
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(brandGradient()),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Apps,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                if (isCached) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(CACHED_DOT_COLOR)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${formatFileSize(fileSize)} • ${stringResource(R.string.cached)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (item.versionName != null) {
                    Text(
                        text = "v${item.versionName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            IconButton(onClick = { onInstall(item) }) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = stringResource(R.string.download),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
internal fun EmptyState(
    icon: ImageVector = Icons.Default.SearchOff,
    title: String,
    subtitle: String,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}
