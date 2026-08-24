package com.kasumi.tool

import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.io.File

private const val DISCORD_URL = "https://discord.gg/CuNhcJpWC7"

private data class NavDestination(val index: Int, val title: String, val icon: ImageVector)

/**
 * Root of the UI: navigation drawer, top bar, and the five feature tabs.
 *
 * Install effects are routed back out to the Activity through [onInstallApk] /
 * [onInstallSplits] because they need Activity-scoped APIs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KasumiApp(
    viewModel: AppsViewModel,
    onInstallApk: (File, (UiText) -> Unit) -> Unit,
    onInstallSplits: suspend (List<File>, (UiText) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // rememberSaveable: the selected tab and the query survive rotation together
    // with the ViewModel state behind them.
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showSortDialog by rememberSaveable { mutableStateOf(false) }
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val showMessage: (UiText) -> Unit = remember(snackbarHostState, context) {
        { text ->
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(text.asString(context))
            }
        }
    }
    val showRawMessage: (String) -> Unit = remember(showMessage) {
        { message -> showMessage(UiText.Raw(message)) }
    }

    KasumiEvents(viewModel, showMessage, onInstallApk, onInstallSplits)

    val destinations = listOf(
        NavDestination(0, stringResource(R.string.tab_apps), Icons.Default.Apps),
        NavDestination(1, stringResource(R.string.tab_roblox_login), Icons.AutoMirrored.Filled.Login),
        NavDestination(2, stringResource(R.string.tab_auto_rejoin), Icons.Default.Replay),
        NavDestination(3, stringResource(R.string.tab_sync), Icons.Default.Sync),
        NavDestination(4, stringResource(R.string.tab_bypass_delta), Icons.Default.VpnKey),
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(24.dp),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                Spacer(Modifier.height(8.dp))
                destinations.forEach { dest ->
                    NavigationDrawerItem(
                        icon = { Icon(dest.icon, contentDescription = null) },
                        label = { Text(dest.title) },
                        selected = selectedTab == dest.index,
                        onClick = {
                            selectedTab = dest.index
                            viewModel.setSearchQuery("")
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp),
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                LargeTopAppBar(
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.menu))
                        }
                    },
                    title = { Text(destinations[selectedTab].title, fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    actions = {
                        if (selectedTab == 0) {
                            IconButton(onClick = { showSortDialog = true }) {
                                Icon(Icons.Default.FilterList, contentDescription = stringResource(R.string.sort))
                            }
                        }
                        IconButton(onClick = { showAboutDialog = true }) {
                            Icon(Icons.Default.Info, contentDescription = stringResource(R.string.about))
                        }
                    },
                )
            },
            snackbarHost = {
                SnackbarHost(snackbarHostState) { data ->
                    Snackbar(
                        snackbarData = data,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        actionColor = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(16.dp),
                    )
                }
            },
        ) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding)) {
                AnimatedVisibility(visible = uiState.isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }

                if (selectedTab == 0) {
                    KasumiSearchBar(
                        query = uiState.searchQuery,
                        onQueryChange = viewModel::setSearchQuery,
                        hint = stringResource(R.string.search_hint),
                    )
                }

                when (selectedTab) {
                    0 -> AppsScreen(viewModel = viewModel)
                    1 -> RobloxLoginScreen(onShowSnackbar = showRawMessage)
                    2 -> AutoRejoinScreen(onShowSnackbar = showRawMessage)
                    3 -> SyncScreen(onShowSnackbar = showRawMessage)
                    else -> BypassKeyDeltaScreen(onShowSnackbar = showRawMessage)
                }
            }
        }
    }

    if (showSortDialog) {
        SortDialog(
            current = uiState.sortMode,
            onDismiss = { showSortDialog = false },
            onSortSelected = {
                viewModel.setSortMode(it)
                showSortDialog = false
            },
        )
    }

    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }
}

/** Bridges one-shot [AppsEvent]s to the snackbar and the Activity installers. */
@Composable
private fun KasumiEvents(
    viewModel: AppsViewModel,
    showMessage: (UiText) -> Unit,
    onInstallApk: (File, (UiText) -> Unit) -> Unit,
    onInstallSplits: suspend (List<File>, (UiText) -> Unit) -> Unit,
) {
    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is AppsEvent.Message -> showMessage(event.text)
                is AppsEvent.InstallApk -> onInstallApk(event.file, showMessage)
                is AppsEvent.InstallSplits -> onInstallSplits(event.files, showMessage)
            }
        }
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val versionName = remember(context) {
        runCatching {
            val info = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0L),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            info.versionName.orEmpty()
        }.getOrDefault("")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(24.dp),
        icon = {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(brandGradient()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Apps,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp),
                )
            }
        },
        title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(R.string.about_version, versionName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.about_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { uriHandler.openUri(DISCORD_URL) }) {
                Text(stringResource(R.string.about_discord), color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@Composable
private fun KasumiSearchBar(query: String, onQueryChange: (String) -> Unit, hint: String) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp)),
        placeholder = {
            Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        },
        leadingIcon = {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        },
        trailingIcon = {
            AnimatedVisibility(
                visible = query.isNotEmpty(),
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
            ) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.clear_search),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = MaterialTheme.colorScheme.primary,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
private fun SortDialog(
    current: SortMode,
    onDismiss: () -> Unit,
    onSortSelected: (SortMode) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.sort), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SORT_OPTIONS.forEach { (mode, labelRes) ->
                    val isSelected = current == mode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                } else {
                                    Color.Transparent
                                }
                            )
                            .clickable { onSortSelected(mode) }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = MaterialTheme.colorScheme.primary,
                                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            stringResource(labelRes),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close), color = MaterialTheme.colorScheme.primary)
            }
        },
    )
}

private val SORT_OPTIONS = listOf(
    SortMode.NAME_ASC to R.string.sort_by_name,
    SortMode.NAME_DESC to R.string.sort_by_name_desc,
    SortMode.SIZE_DESC to R.string.sort_by_size,
    SortMode.DATE_DESC to R.string.sort_by_date,
)

/** The primary-to-secondary gradient used behind app and dialog icons. */
@Composable
internal fun brandGradient(): Brush {
    val primary = MaterialTheme.colorScheme.primaryContainer
    val secondary = MaterialTheme.colorScheme.secondaryContainer
    return remember(primary, secondary) { Brush.linearGradient(listOf(primary, secondary)) }
}
