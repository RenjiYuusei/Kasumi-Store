package com.kasumi.tool

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.util.AtomicFile
import androidx.lifecycle.lifecycleScope
import coil3.compose.AsyncImage
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.kasumi.tool.ui.theme.KasumiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.FileReader
import java.io.FileWriter
import java.io.Reader
import java.io.Writer
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val saveMutex = Mutex()
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()
    }
    private val gson = Gson()

    private val DEFAULT_SOURCE_URL = "https://raw.githubusercontent.com/RenjiYuusei/Kasumi-Store/main/source/apps.json"
    private val DEFAULT_SCRIPTS_URL = "https://raw.githubusercontent.com/RenjiYuusei/Kasumi-Store/main/source/scripts.json"

    // Data states
    private var appsList by mutableStateOf<List<ApkItem>>(emptyList())
    // Store original online scripts separately to preserve metadata
    private var onlineScriptsList = listOf<ScriptItem>()
    private var scriptsList by mutableStateOf<List<ScriptItem>>(emptyList())
    private var isLoading by mutableStateOf(false)
    private var sortMode by mutableStateOf(SortMode.NAME_ASC)
    private val fileStats = mutableStateMapOf<String, FileStats>()
    private var statsVersion by mutableIntStateOf(0)

    private val installReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if ("${context.packageName}.INSTALL_COMMIT" == intent.action) {
                val status = intent.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, android.content.pm.PackageInstaller.STATUS_FAILURE)
                val message = intent.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE)
                when (status) {
                    android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirmIntent = if (Build.VERSION.SDK_INT >= 33) {
                             intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        } else {
                             @Suppress("DEPRECATION")
                             intent.getParcelableExtra(Intent.EXTRA_INTENT)
                        }
                        confirmIntent?.let {
                            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            try {
                                context.startActivity(it)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Không thể mở hộp thoại xác nhận: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    android.content.pm.PackageInstaller.STATUS_SUCCESS -> {
                        Toast.makeText(context, "Cài đặt thành công", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        // Attempt to show more detailed error
                        val errorMessage = message ?: "Lỗi không xác định ($status)"
                        Toast.makeText(context, "Cài đặt thất bại: $errorMessage", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    /**
     * Initializes the activity: configures edge-to-edge UI, registers the install broadcast receiver,
     * starts background loading of app/script data, requests storage permission, and sets the Compose UI.
     *
     * Performs setup required before the UI is shown, including registering the install commit receiver
     * (with exported flag on Android 33+), launching a lifecycle-scoped coroutine to load persisted items,
     * preloaded apps, and scripts, and applying the app theme and main composable.
     *
     * @param savedInstanceState If non-null, contains the activity's previously saved state.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(installReceiver, android.content.IntentFilter("${packageName}.INSTALL_COMMIT"), Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(installReceiver, android.content.IntentFilter("${packageName}.INSTALL_COMMIT"))
        }

        lifecycleScope.launch {
            loadItems()
            setBusy(true)
            refreshPreloadedApps(initial = true)
            loadScriptsFromOnline()
            loadScriptsFromLocal()
            setBusy(false)
        }

        requestStoragePermission()

        setContent {
            KasumiTheme {
                MainScreen()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(installReceiver)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainScreen() {
        var selectedTab by remember { mutableIntStateOf(0) }
        var searchQuery by remember { mutableStateOf("") }
        var showSortDialog by remember { mutableStateOf(false) }
        var scriptToDownload by remember { mutableStateOf<ScriptItem?>(null) }

        val snackbarHostState = remember { SnackbarHostState() }

        // Compute file stats in background to avoid I/O in UI
        LaunchedEffect(appsList) {
            FileStatsHelper.updateFileStats(appsList, fileStats, cacheDir)
            statsVersion++
        }

        if (scriptToDownload != null) {
            val script = scriptToDownload!!
            AlertDialog(
                onDismissRequest = { scriptToDownload = null },
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(24.dp),
                title = {
                    Column {
                        Text(
                            script.name,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            stringResource(R.string.select_folder),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                scriptToDownload = null
                                downloadScript(script, "Autoexecute", { msg ->
                                    lifecycleScope.launch { snackbarHostState.showSnackbar(msg) }
                                })
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                        ) {
                            Text(
                                stringResource(R.string.auto_execute_desc),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                scriptToDownload = null
                                downloadScript(script, "Scripts", { msg ->
                                    lifecycleScope.launch { snackbarHostState.showSnackbar(msg) }
                                })
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f))
                        ) {
                            Text(
                                stringResource(R.string.manual_desc),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { scriptToDownload = null }) {
                        Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            )
        }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.app_name),
                            fontWeight = FontWeight.Bold
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    actions = {
                        if (selectedTab == 0) {
                            IconButton(onClick = { showSortDialog = true }) {
                                Icon(Icons.Default.FilterList, contentDescription = "Sort")
                            }

                        }
                    }
                )
            },
            snackbarHost = {
                SnackbarHost(snackbarHostState) { data ->
                    Snackbar(
                        snackbarData = data,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        actionColor = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(16.dp)
                    )
                }
            },
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    tonalElevation = 0.dp
                ) {
                    NavigationBarItem(
                        icon = {
                            Icon(
                                if (selectedTab == 0) Icons.Default.Apps else Icons.Outlined.Apps,
                                contentDescription = "Apps"
                            )
                        },
                        label = { Text("Ứng dụng", fontWeight = if (selectedTab == 0) FontWeight.SemiBold else FontWeight.Normal) },
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0; searchQuery = "" },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    NavigationBarItem(
                        icon = {
                            Icon(
                                if (selectedTab == 1) Icons.Default.Code else Icons.Outlined.Code,
                                contentDescription = "Script"
                            )
                        },
                        label = { Text("Script", fontWeight = if (selectedTab == 1) FontWeight.SemiBold else FontWeight.Normal) },
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1; searchQuery = "" },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        ) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding)) {


                KasumiSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    hint = if (selectedTab == 0) stringResource(R.string.search_hint) else stringResource(R.string.search_scripts_hint)
                )

                if (selectedTab == 0) {
                    PullToRefreshBox(
                        isRefreshing = isLoading,
                        onRefresh = {
                            lifecycleScope.launch {
                                setBusy(true)
                                refreshPreloadedApps()
                                setBusy(false)
                                snackbarHostState.showSnackbar("Đã làm mới nguồn")
                            }
                        }
                    ) {
                        AppsListContent(searchQuery, onShowSnackbar = { msg ->
                            lifecycleScope.launch { snackbarHostState.showSnackbar(msg) }
                        })
                    }
                } else {
                    PullToRefreshBox(
                        isRefreshing = isLoading,
                        onRefresh = {
                            lifecycleScope.launch {
                                setBusy(true)
                                loadScriptsFromOnline()
                                loadScriptsFromLocal()
                                setBusy(false)
                                snackbarHostState.showSnackbar("Đã làm mới nguồn")
                            }
                        }
                    ) {
                        ScriptsListContent(searchQuery, onShowSnackbar = { msg ->
                            lifecycleScope.launch { snackbarHostState.showSnackbar(msg) }
                        }, onDownloadRequest = { script ->
                            scriptToDownload = script
                        })
                    }
                }
            }

            if (showSortDialog) {
                SortDialog(
                    onDismiss = { showSortDialog = false },
                    onSortSelected = { mode ->
                        sortMode = mode
                        showSortDialog = false
                    }
                )
            }
        }
    }

    @Composable
    fun KasumiSearchBar(query: String, onQueryChange: (String) -> Unit, hint: String) {
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(20.dp)),
            placeholder = {
                Text(
                    hint,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.clear_search),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
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
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface
            )
        )
    }

    @Composable
    fun SortDialog(onDismiss: () -> Unit, onSortSelected: (SortMode) -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    stringResource(R.string.sort),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val options = listOf(
                        SortMode.NAME_ASC to R.string.sort_by_name,
                        SortMode.NAME_DESC to R.string.sort_by_name_desc,
                        SortMode.SIZE_DESC to R.string.sort_by_size,
                        SortMode.DATE_DESC to R.string.sort_by_date
                    )
                    options.forEach { (mode, labelRes) ->
                        val isSelected = sortMode == mode
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                    else Color.Transparent
                                )
                                .clickable { onSortSelected(mode) }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = MaterialTheme.colorScheme.primary,
                                    unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                stringResource(labelRes),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("Đóng", color = MaterialTheme.colorScheme.primary)
                }
            }
        )
    }

    @Composable
    fun AppsListContent(searchQuery: String, onShowSnackbar: (String) -> Unit) {
        val filteredApps by produceState(initialValue = emptyList(), appsList, searchQuery, sortMode, statsVersion) {
            snapshotFlow {
                Triple(appsList, searchQuery, sortMode) to fileStats.toMap()
            }.collect { (params, stats) ->
                val (list, query, mode) = params
                value = withContext(Dispatchers.Default) {
                    filterAndSortApps(list, query, mode, stats)
                }
            }
        }

        // Stats
        val cachedCount = filteredApps.count { fileStats[it.id]?.exists == true }
        val totalSize = filteredApps.sumOf { fileStats[it.id]?.size ?: 0L }

        Column {
             Row(
                 modifier = Modifier
                     .fillMaxWidth()
                     .padding(horizontal = 16.dp, vertical = 8.dp),
                 horizontalArrangement = Arrangement.SpaceBetween,
                 verticalAlignment = Alignment.CenterVertically
             ) {
                 val statsText = if (cachedCount > 0) {
                     stringResource(R.string.stats_format, filteredApps.size, "$cachedCount (${formatFileSize(totalSize)})")
                 } else {
                     stringResource(R.string.stats_format_no_cache, filteredApps.size)
                 }

                 Text(
                     text = statsText,
                     style = MaterialTheme.typography.labelMedium,
                     color = MaterialTheme.colorScheme.onSurfaceVariant
                 )
                 if (cachedCount > 0) {
                     TextButton(
                         onClick = { clearCache(onShowSnackbar) },
                         colors = ButtonDefaults.textButtonColors(
                             contentColor = MaterialTheme.colorScheme.error
                         )
                     ) {
                         Text(
                             stringResource(R.string.clear_cache),
                             style = MaterialTheme.typography.labelMedium
                         )
                     }
                 }
             }

            if (filteredApps.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.SearchOff,
                    title = stringResource(R.string.no_apps_title),
                    subtitle = stringResource(R.string.no_apps_subtitle)
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(top = 4.dp, bottom = 80.dp)
                ) {
                    itemsIndexed(filteredApps, key = { _, item -> item.id }) { index, item ->
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn(tween(300, delayMillis = index.coerceAtMost(15) * 30)) +
                                    slideInVertically(tween(300, delayMillis = index.coerceAtMost(15) * 30)) { it / 3 }
                        ) {
                            AppItemRow(item, stats = fileStats[item.id], onInstall = { onInstallClicked(it, onShowSnackbar) })
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun AppItemRow(item: ApkItem, stats: FileStats?, onInstall: (ApkItem) -> Unit) {
        val context = LocalContext.current
        val isCached = stats?.exists == true
        val fileSize = stats?.size ?: 0L
        
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            border = BorderStroke(
                width = 1.dp,
                color = if (isCached) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.iconUrl != null) {
                    AsyncImage(
                        model = item.iconUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        MaterialTheme.colorScheme.secondaryContainer
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Apps,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(28.dp)
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
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    if (isCached) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF66BB6A))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${formatFileSize(fileSize)} • ${stringResource(R.string.cached)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (item.versionName != null) {
                        Text(
                            text = "v${item.versionName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(onClick = { onInstall(item) }) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = stringResource(R.string.download),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }

    @Composable
    fun EmptyState(
        icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.SearchOff,
        title: String,
        subtitle: String
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }

    @Composable
    fun ScriptsListContent(searchQuery: String, onShowSnackbar: (String) -> Unit, onDownloadRequest: (ScriptItem) -> Unit) {
        val filteredScripts = remember(scriptsList, searchQuery) {
            val q = searchQuery.trim()
             if (q.isEmpty()) {
                scriptsList
            } else {
                scriptsList.filter {
                    it.name.contains(q, ignoreCase = true) ||
                            it.gameName.contains(q, ignoreCase = true)
                }
            }
        }

        Column {
            Text(
                text = stringResource(R.string.scripts_count, filteredScripts.size),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (filteredScripts.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.Code,
                    title = stringResource(R.string.no_scripts_title),
                    subtitle = stringResource(R.string.no_scripts_subtitle)
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(top = 4.dp, bottom = 80.dp)
                ) {
                    itemsIndexed(filteredScripts, key = { _, script -> script.id }) { index, script ->
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn(tween(300, delayMillis = index.coerceAtMost(15) * 30)) +
                                    slideInVertically(tween(300, delayMillis = index.coerceAtMost(15) * 30)) { it / 3 }
                        ) {
                            ScriptItemRow(
                                script = script,
                                onDownload = { onDownloadRequest(script) },
                                onCopy = { copyScript(it, onShowSnackbar) },
                                onDelete = { deleteScript(it, onShowSnackbar) }
                            )
                        }
                    }
                }
            }
        }
    }
    
    @Composable
    fun ScriptItemRow(script: ScriptItem, onDownload: (ScriptItem) -> Unit, onCopy: (ScriptItem) -> Unit, onDelete: (ScriptItem) -> Unit) {
        val isLocal = script.localPath != null
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            border = BorderStroke(
                width = 1.dp,
                color = if (isLocal) MaterialTheme.colorScheme.secondary.copy(alpha = 0.3f)
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        MaterialTheme.colorScheme.tertiaryContainer
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Code,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = script.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = script.gameName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!isLocal) {
                        OutlinedButton(
                            onClick = { onDownload(script) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.download))
                        }
                    }
                    Button(
                        onClick = { onCopy(script) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.copy_script))
                    }
                    if (isLocal) {
                        FilledIconButton(
                            onClick = { onDelete(script) },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }


    // --- Logic functions migrated from old MainActivity ---

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permissions = arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            requestPermissions(permissions, 100)
        }
    }

    private fun setBusy(busy: Boolean) {
        isLoading = busy
    }

    private fun log(msg: String) {
        Log.d("Kasumi", msg)
    }

    /**
 * Logs a message intended for background or non-UI contexts.
 *
 * @param msg The message to log.
 */
private fun logBg(msg: String) = log(msg)

    /**
     * Loads persisted APK items from shared preferences and updates `appsList`.
     *
     * Reads JSON stored under the SharedPreferences file "apk_items" with key "list", parses it into `ApkItem`
     * objects, and replaces `appsList` with a mutable list of the parsed items. If no stored list exists or
     * parsing yields no items, `appsList` is set to an empty list.
     */
    private suspend fun loadItems() {
        val loaded = withContext(Dispatchers.IO) {
            val file = File(filesDir, "items.json")
            if (file.exists()) {
                try {
                    BufferedReader(FileReader(file)).use { reader ->
                        ApkItem.readListFrom(reader)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    emptyList()
                }
            } else {
                val prefs = getSharedPreferences("apk_items", Context.MODE_PRIVATE)
                val json = prefs.getString("list", null)
                val list = ApkItem.fromJsonList(json)
                if (list.isNotEmpty()) {
                    saveMutex.withLock {
                        val atomicFile = AtomicFile(File(filesDir, "items.json"))
                        var fos: FileOutputStream? = null
                        try {
                            fos = atomicFile.startWrite()
                            val writer = BufferedWriter(java.io.OutputStreamWriter(fos))
                            ApkItem.writeListTo(list, writer)
                            writer.flush()
                            atomicFile.finishWrite(fos)
                            prefs.edit().remove("list").apply()
                        } catch (e: Exception) {
                            e.printStackTrace()
                            if (fos != null) {
                                atomicFile.failWrite(fos)
                            }
                        }
                    }
                }
                list
            }
        }
        appsList = loaded.ifEmpty { emptyList() }.toMutableList()
    }

    private suspend fun saveItems() {
        saveMutex.withLock {
            withContext(Dispatchers.IO) {
                val atomicFile = AtomicFile(File(filesDir, "items.json"))
                var fos: FileOutputStream? = null
                try {
                    fos = atomicFile.startWrite()
                    val writer = BufferedWriter(java.io.OutputStreamWriter(fos))
                    ApkItem.writeListTo(appsList, writer)
                    writer.flush()
                    atomicFile.finishWrite(fos)
                } catch (e: Exception) {
                    e.printStackTrace()
                    if (fos != null) {
                        atomicFile.failWrite(fos)
                    }
                }
            }
        }
    }


    private fun normalizeUrl(raw: String): String {
        var url = raw
        if (url.contains("dropbox.com")) {
            var u2 = url
            u2 = u2.replace("://www.dropbox.com", "://dl.dropboxusercontent.com")
            u2 = u2.replace("://dropbox.com", "://dl.dropboxusercontent.com")
            u2 = if (u2.contains("dl=0")) u2.replace("dl=0", "dl=1") else if (u2.contains("dl=")) u2 else u2 + (if (u2.contains("?")) "&dl=1" else "?dl=1")
            return u2
        }
        return url
    }

    private suspend fun fetchPreloadedAppsRemote(url: String): List<PreloadApp>? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).header("User-Agent", "CloudPhoneTool/1.0").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val arr: Array<PreloadApp> = gson.fromJson(body, Array<PreloadApp>::class.java)
                arr.toList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun refreshPreloadedApps(initial: Boolean = false) {
        val preloaded: List<PreloadApp>? = fetchPreloadedAppsRemote(DEFAULT_SOURCE_URL)
        if (preloaded != null) {
            val newItems = preloaded.map { p ->
                val normalized = normalizeUrl(p.url)
                val id = FileUtils.stableIdFromUrl(p.url)
                ApkItem(
                    id = id,
                    name = p.name,
                    sourceType = SourceType.URL,
                    url = normalized,
                    uri = null,
                    versionName = p.versionName,
                    versionCode = p.versionCode,
                    iconUrl = p.iconUrl
                )
            }
            appsList = newItems
            saveItems()
        }
    }

    private fun onInstallClicked(item: ApkItem, onShowSnackbar: (String) -> Unit) {
        lifecycleScope.launch {
            setBusy(true)
            try {
                val cachedFile = FileUtils.getCacheFile(item, cacheDir)
                val apkFile = if (item.sourceType == SourceType.URL && cachedFile.exists() && cachedFile.length() > 0) {
                     FileStatsHelper.updateItemFileStats(item, fileStats, cacheDir)
                     statsVersion++
                     cachedFile
                } else {
                    when (item.sourceType) {
                        SourceType.LOCAL -> if (item.uri != null) copyFromUriIfNeeded(Uri.parse(item.uri)) else null
                        SourceType.URL -> downloadApk(item)
                    }
                }

                if (apkFile == null) {
                    onShowSnackbar("Lỗi chuẩn bị tệp APK")
                    return@launch
                }

                val urlLower = item.url?.lowercase(Locale.ROOT)
                val fileNameLower = apkFile.name.lowercase(Locale.ROOT)
                val isSplitPackage = (urlLower?.contains(".apks") == true || fileNameLower.endsWith(".apks"))
                        || (urlLower?.contains(".xapk") == true || fileNameLower.endsWith(".xapk"))
                        || (urlLower?.contains(".apkm") == true || fileNameLower.endsWith(".apkm"))

                if (isSplitPackage) {
                    // Logic for split APKs/XAPKs
                     val (splits, obbInfo) = withContext(Dispatchers.IO) { extractSplitsAndObb(apkFile) }
                     if (splits.isEmpty()) {
                        onShowSnackbar("Không tìm thấy APK bên trong file")
                        return@launch
                    }
                    if (obbInfo != null) {
                        withContext(Dispatchers.IO) { installObbFiles(obbInfo) }
                    }
                    
                    val rooted = RootInstaller.isDeviceRooted()
                     if (rooted) {
                        val resSplit: Pair<Boolean, String> = withContext(Dispatchers.IO) { RootInstaller.installApks(splits) }
                        val (ok, msg) = resSplit
                        if (ok) {
                            onShowSnackbar("Cài đặt thành công")
                        } else {
                            installSplitsNormally(splits, onShowSnackbar)
                        }
                    } else {
                        installSplitsNormally(splits, onShowSnackbar)
                    }
                    return@launch
                }

                 val rooted = RootInstaller.isDeviceRooted()
                if (rooted) {
                    val resApk: Pair<Boolean, String> = withContext(Dispatchers.IO) { RootInstaller.installApk(apkFile) }
                    val (ok, msg) = resApk
                    if (ok) {
                        onShowSnackbar("Cài đặt thành công")
                    } else {
                        installNormally(apkFile, onShowSnackbar)
                    }
                } else {
                    installNormally(apkFile, onShowSnackbar)
                }
            } catch (e: Exception) {
                onShowSnackbar("Lỗi: ${e.message}")
                e.printStackTrace()
            } finally {
                setBusy(false)
            }
        }
    }

    private suspend fun downloadApk(item: ApkItem): File? = withContext(Dispatchers.IO) {
        val url = item.url ?: return@withContext null
        val outFile = FileUtils.getCacheFile(item, cacheDir)
        outFile.parentFile?.mkdirs()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android) Kasumi/1.0")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            resp.body?.byteStream()?.use { input ->
                FileOutputStream(outFile).use { out ->
                    input.copyTo(out)
                }
            }
            FileStatsHelper.updateItemFileStats(item, fileStats, cacheDir)
            withContext(Dispatchers.Main) { statsVersion++ }
            outFile
        }
    }

    private suspend fun copyFromUriIfNeeded(uri: Uri): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(cacheDir, "apks").apply { mkdirs() }
            val outFile = File(dir, "picked_${System.currentTimeMillis()}.apk")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { out ->
                    input.copyTo(out)
                }
            }
            outFile
        } catch (e: Exception) {
            null
        }
    }

    private fun installNormally(file: File, onShowSnackbar: (String) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    startActivity(intent)
                    onShowSnackbar("Hãy cấp quyền, sau đó thử lại")
                } catch (e: Exception) { }
            }
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            onShowSnackbar("Không mở được installer: ${e.message}")
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    private fun clearCache(onShowSnackbar: (String) -> Unit) {
         lifecycleScope.launch {
            val (count, size) = withContext(Dispatchers.IO) {
            val apkCacheDir = File(cacheDir, "apks")
            val splitsDir = File(cacheDir, "splits")
            val obbCacheDir = File(cacheDir, "obb")
            var count = 0
            var size = 0L

            // Clean up all cached apk files
            if (apkCacheDir.exists()) {
                apkCacheDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.exists()) {
                        size += file.length()
                        if (file.delete()) count++
                    }
                }
            }
             if (splitsDir.exists()) splitsDir.deleteRecursively()
             if (obbCacheDir.exists()) obbCacheDir.deleteRecursively()

             count to size
            }

            FileStatsHelper.refreshAll(appsList, fileStats, cacheDir)
            statsVersion++
            // Force recomposition in case list content stays the same but file stats changed.
            appsList = appsList.toList()

            val sizeStr = formatFileSize(size)
            onShowSnackbar("Đã xóa cache: $count tệp ($sizeStr)")
         }
    }

    // --- Scripts Logic ---

    private suspend fun loadScriptsFromOnline() {
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(DEFAULT_SCRIPTS_URL).header("User-Agent", "CloudPhoneTool/1.0").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext
                    val stream = resp.body?.byteStream() ?: return@withContext
                    val newScripts = mutableListOf<ScriptItem>()
                    try {
                        com.google.gson.stream.JsonReader(java.io.InputStreamReader(stream)).use { reader ->
                            reader.beginArray()
                            while (reader.hasNext()) {
                                reader.beginObject()
                                var name = ""
                                var gameName = ""
                                var url = ""
                                while (reader.hasNext()) {
                                    when (reader.nextName()) {
                                        "name" -> name = if (reader.peek() == com.google.gson.stream.JsonToken.NULL) { reader.nextNull(); "" } else { reader.nextString() }
                                        "gameName" -> gameName = if (reader.peek() == com.google.gson.stream.JsonToken.NULL) { reader.nextNull(); "" } else { reader.nextString() }
                                        "url" -> url = if (reader.peek() == com.google.gson.stream.JsonToken.NULL) { reader.nextNull(); "" } else { reader.nextString() }
                                        else -> reader.skipValue()
                                    }
                                }
                                reader.endObject()
                                if (url.isNotBlank()) {
                                    newScripts.add(
                                        ScriptItem(
                                            id = FileUtils.stableIdFromUrl(url),
                                            name = name,
                                            gameName = gameName,
                                            url = url
                                        )
                                    )
                                }
                            }
                            reader.endArray()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    withContext(Dispatchers.Main) {
                        onlineScriptsList = newScripts
                        // Initial merge (will be refined by loadScriptsFromLocal)
                        scriptsList = newScripts
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }


    private val PATH_DELTA_LEGACY = "/storage/emulated/0/Delta"
    private val PATH_DELTA_VNG = "/storage/emulated/0/Android/data/com.roblox.client.vnggames/files/gloop/external/Delta"

    private fun getDeltaDir(): File {
        val vng = File(PATH_DELTA_VNG)
        if (vng.exists()) return vng
        return File(PATH_DELTA_LEGACY)
    }

private suspend fun loadScriptsFromLocal() {
        val context = this@MainActivity
        withContext(Dispatchers.IO) {
            val newLocals = mutableListOf<ScriptItem>()
            val autoExecuteDir = File(getDeltaDir(), "Autoexecute")
            val scriptsDir = File(getDeltaDir(), "Scripts")
             if (autoExecuteDir.exists()) {
                autoExecuteDir.listFiles()?.forEach {
                    if (it.isFile) {
                        newLocals.add(ScriptItem("local_auto_${it.name}", it.nameWithoutExtension, context.getString(R.string.local_auto), null, it.absolutePath))
                    }
                }
            }
             if (scriptsDir.exists()) {
                scriptsDir.listFiles()?.forEach {
                    if (it.isFile) {
                        newLocals.add(ScriptItem("local_manual_${it.name}", it.nameWithoutExtension, context.getString(R.string.local_manual), null, it.absolutePath))
                    }
                }
            }
            withContext(Dispatchers.Main) {
                scriptsList = ScriptUtils.mergeScripts(onlineScriptsList, newLocals)
            }
        }
    }

    private fun downloadScript(script: ScriptItem, targetFolder: String, onShowSnackbar: (String) -> Unit) {
        lifecycleScope.launch {
            setBusy(true)
            try {
                val url = script.url ?: return@launch
                val content = if (url.contains("/source/hard/")) {
                    fetchScriptBody(url)
                } else {
                    "loadstring(game:HttpGet(\"$url\"))()"
                }

                val dir = File(getDeltaDir(), targetFolder)
                // Save with .txt extension as requested
                val fileName = if (script.name.lowercase().endsWith(".txt")) script.name else "${script.name}.txt"
                ScriptUtils.saveScriptToFile(dir, fileName, content)
                onShowSnackbar(getString(R.string.saved_to, targetFolder))
                loadScriptsFromLocal() // Refresh
            } catch (e: Exception) {
                onShowSnackbar(getString(R.string.error_prefix, e.message))
            } finally {
                setBusy(false)
            }
        }
    }

    private suspend fun fetchScriptBody(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android) Kasumi/1.0").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            resp.body?.string() ?: ""
        }
    }

    private fun copyScript(script: ScriptItem, onShowSnackbar: (String) -> Unit) {
         lifecycleScope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    if (script.localPath != null) {
                        File(script.localPath).readText()
                    } else if (script.url != null) {
                        if (script.url.contains("/source/hard/")) fetchScriptBody(script.url)
                        else "loadstring(game:HttpGet(\"${script.url}\"))()"
                    } else ""
                }

                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(script.name, content))
                onShowSnackbar(getString(R.string.copied_script))
            } catch (e: Exception) {
                onShowSnackbar(getString(R.string.error_prefix, e.message))
            }
         }
    }

    private fun deleteScript(script: ScriptItem, onShowSnackbar: (String) -> Unit) {
        if (script.localPath != null) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    File(script.localPath).delete()
                }
                onShowSnackbar(getString(R.string.deleted_script))
                loadScriptsFromLocal()
            }
        }
    }

    // Copied from old Main for splitting/OBB
    data class ObbInfo(val packageName: String, val obbFiles: List<File>)
    private fun extractSplitsAndObb(packageFile: File): Pair<List<File>, ObbInfo?> {
        val outDir = File(cacheDir, "splits/${packageFile.nameWithoutExtension}")
        if (outDir.exists()) outDir.deleteRecursively()
        outDir.mkdirs()
        val results = mutableListOf<File>()
        val obbFiles = mutableListOf<File>()
        var packageName: String? = null
        
        try {
            java.util.zip.ZipFile(packageFile).use { zipFile ->
                val entries = zipFile.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val entryName = entry.name.lowercase()
                    val fileName = entry.name.substringAfterLast('/')
                    
                    if (entryName.endsWith("manifest.json")) {
                        try {
                            val manifest = zipFile.getInputStream(entry).bufferedReader().readText()
                            packageName = JSONObject(manifest).optString("package_name")
                        } catch (e: Exception) {}
                        continue
                    }
                    if (entryName.endsWith(".apk")) {
                        // Check for encryption/invalidity by peeking 2 magic bytes: 'PK' (0x50 0x4B)
                        // APK is a ZIP, so it must start with PK signature.
                        val input = zipFile.getInputStream(entry)
                        val buf = ByteArray(2)
                        val read = input.read(buf)
                        if (read == 2 && buf[0] == 0x50.toByte() && buf[1] == 0x4B.toByte()) {
                             val outFile = File(outDir, fileName)
                             outFile.parentFile?.mkdirs()
                             // Re-open stream or just continue reading?
                             // ZipFile.getInputStream returns a new stream usually, but let's be safe:
                             // We already read 2 bytes, need to prepend them or re-open.
                             // Simpler: re-open.
                             zipFile.getInputStream(entry).use { inp ->
                                 FileOutputStream(outFile).use { output ->
                                     inp.copyTo(output)
                                 }
                             }
                             if (outFile.exists() && outFile.length() > 0) results.add(outFile)
                        } else {
                            // Encrypted or invalid APK entry
                             Log.e("Kasumi", "Skipping invalid/encrypted APK entry: $fileName")
                        }
                        continue
                    }
                    if (entryName.endsWith(".obb")) {
                        val obbDir = File(cacheDir, "obb")
                        obbDir.mkdirs()
                        val outFile = File(obbDir, fileName)
                        zipFile.getInputStream(entry).use { input -> FileOutputStream(outFile).use { output -> input.copyTo(output) } }
                        if (outFile.exists() && outFile.length() > 0) obbFiles.add(outFile)
                        continue
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        
        val sortedApks = results.sortedWith(compareBy(
            { !it.name.startsWith("base.") && !it.name.contains("com.") },
            { it.name.startsWith("config.") || it.name.startsWith("split_") },
            { it.name }
        ))
        val obbInfo = if (obbFiles.isNotEmpty() && packageName != null) ObbInfo(packageName, obbFiles) else null
        return sortedApks to obbInfo
    }
    
    private fun installObbFiles(obbInfo: ObbInfo) {
         try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) return
            }
            val obbDir = File(Environment.getExternalStorageDirectory(), "Android/obb/${obbInfo.packageName}")
            if (!obbDir.exists()) obbDir.mkdirs()
            for (obbFile in obbInfo.obbFiles) {
                val destFile = File(obbDir, obbFile.name)
                FileInputStream(obbFile).use { fis ->
                    FileOutputStream(destFile).use { fos ->
                        val src = fis.channel
                        val dest = fos.channel
                        var position = 0L
                        val size = src.size()
                        while (position < size) {
                            position += src.transferTo(position, size - position, dest)
                        }
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun installSplitsNormally(files: List<File>, onShowSnackbar: (String) -> Unit) {
         try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!packageManager.canRequestPackageInstalls()) {
                     val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:$packageName")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    onShowSnackbar("Hãy cấp quyền, sau đó thử lại")
                    return
                }
            }
            val installer = packageManager.packageInstaller
            val params = android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            try {
                for (f in files) {
                    FileInputStream(f).use { input ->
                        session.openWrite(f.name, 0, f.length()).use { out ->
                            input.copyTo(out)
                            session.fsync(out)
                        }
                    }
                }
                val intent = Intent("$packageName.INSTALL_COMMIT").apply {
                     setPackage(packageName)
                }
                val pi = android.app.PendingIntent.getBroadcast(
                    this, sessionId,
                    intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0)
                )
                session.commit(pi.intentSender)
                onShowSnackbar("Đang tiến hành cài đặt…")
            } finally {
                session.close()
            }
        } catch (e: Exception) {
            onShowSnackbar("Lỗi cài đặt splits: ${e.message}")
        }
    }
}
