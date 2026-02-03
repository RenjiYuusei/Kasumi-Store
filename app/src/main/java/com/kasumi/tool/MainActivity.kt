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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.kasumi.tool.ui.theme.KasumiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    private val DEFAULT_SOURCE_URL = "https://raw.githubusercontent.com/RenjiYuusei/Kasumi-Store/main/source/apps.json"
    private val DEFAULT_SCRIPTS_URL = "https://raw.githubusercontent.com/RenjiYuusei/Kasumi-Store/main/source/scripts.json"

    // Data states
    private var appsList by mutableStateOf<List<ApkItem>>(emptyList())
    // Store original online scripts separately to preserve metadata
    private var onlineScriptsList = listOf<ScriptItem>()
    private var scriptsList by mutableStateOf<List<ScriptItem>>(emptyList())
    private var isLoading by mutableStateOf(false)
    private var sortMode by mutableStateOf(SortMode.NAME_ASC)
    private var cacheVersion by mutableIntStateOf(0)
    private var fileStats by mutableStateOf<Map<String, FileStats>>(emptyMap())

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

        // OPTIMIZATION: Compute file stats in background to avoid I/O in UI
        LaunchedEffect(appsList, cacheVersion) {
            withContext(Dispatchers.IO) {
                val newStats = appsList.associate { item ->
                    val f = cacheFileFor(item)
                    if (f.exists()) {
                        item.id to FileStats(true, f.length(), f.lastModified())
                    } else {
                        item.id to FileStats(false, 0L, 0L)
                    }
                }
                fileStats = newStats
            }
        }

        if (scriptToDownload != null) {
            val script = scriptToDownload!!
            AlertDialog(
                onDismissRequest = { scriptToDownload = null },
                title = { Text("${script.name} - ${stringResource(R.string.select_folder)}") },
                text = {
                    Column {
                        TextButton(
                            onClick = {
                                scriptToDownload = null
                                downloadScript(script, "Autoexecute", { msg ->
                                    lifecycleScope.launch { snackbarHostState.showSnackbar(msg) }
                                })
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.auto_execute_desc), modifier = Modifier.fillMaxWidth())
                        }
                        TextButton(
                            onClick = {
                                scriptToDownload = null
                                downloadScript(script, "Scripts", { msg ->
                                    lifecycleScope.launch { snackbarHostState.showSnackbar(msg) }
                                })
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.manual_desc), modifier = Modifier.fillMaxWidth())
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { scriptToDownload = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        if (selectedTab == 0) {
                            IconButton(onClick = { showSortDialog = true }) {
                                Icon(Icons.Default.FilterList, contentDescription = "Sort")
                            }
                            IconButton(onClick = {
                                lifecycleScope.launch {
                                    setBusy(true)
                                    refreshPreloadedApps()
                                    setBusy(false)
                                    snackbarHostState.showSnackbar("Đã làm mới nguồn")
                                }
                            }) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                            }
                        }
                    }
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Apps, contentDescription = "Apps") },
                        label = { Text("Ứng dụng") },
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0; searchQuery = "" }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Code, contentDescription = "Script") },
                        label = { Text("Script") },
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1; searchQuery = "" }
                    )
                }
            }
        ) { innerPadding ->
            Column(modifier = Modifier.padding(innerPadding)) {
                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                SearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    hint = if (selectedTab == 0) stringResource(R.string.search_hint) else stringResource(R.string.search_scripts_hint)
                )

                if (selectedTab == 0) {
                    AppsListContent(searchQuery, onShowSnackbar = { msg ->
                        lifecycleScope.launch { snackbarHostState.showSnackbar(msg) }
                    })
                } else {
                    ScriptsListContent(searchQuery, onShowSnackbar = { msg ->
                        lifecycleScope.launch { snackbarHostState.showSnackbar(msg) }
                    }, onDownloadRequest = { script ->
                        scriptToDownload = script
                    })
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
    fun SearchBar(query: String, onQueryChange: (String) -> Unit, hint: String) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            placeholder = { Text(hint) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
    }

    @Composable
    fun SortDialog(onDismiss: () -> Unit, onSortSelected: (SortMode) -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.sort)) },
            text = {
                Column {
                    val options = listOf(
                        SortMode.NAME_ASC to R.string.sort_by_name,
                        SortMode.NAME_DESC to R.string.sort_by_name_desc,
                        SortMode.SIZE_DESC to R.string.sort_by_size,
                        SortMode.DATE_DESC to R.string.sort_by_date
                    )
                    options.forEach { (mode, labelRes) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSortSelected(mode) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = sortMode == mode, onClick = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(labelRes))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("Đóng") }
            }
        )
    }

    @Composable
    fun AppsListContent(searchQuery: String, onShowSnackbar: (String) -> Unit) {
        val filteredApps = remember(appsList, searchQuery, sortMode, fileStats) {
            val q = searchQuery.trim().lowercase()
            val filtered = if (q.isEmpty()) {
                appsList
            } else {
                appsList.filter {
                    it.name.lowercase().contains(q) ||
                            (it.url?.lowercase()?.contains(q) == true)
                }
            }

            when (sortMode) {
                SortMode.NAME_ASC -> filtered.sortedBy { it.name.lowercase() }
                SortMode.NAME_DESC -> filtered.sortedByDescending { it.name.lowercase() }
                SortMode.SIZE_DESC -> filtered.sortedByDescending {
                     fileStats[it.id]?.size ?: 0L
                }
                SortMode.DATE_DESC -> filtered.sortedByDescending {
                     fileStats[it.id]?.lastModified ?: 0L
                }
            }
        }

        // Stats
        val cachedCount = remember(filteredApps, fileStats) {
            filteredApps.count { fileStats[it.id]?.exists == true }
        }
        val totalSize = remember(filteredApps, fileStats) {
            filteredApps.sumOf {
                fileStats[it.id]?.size ?: 0L
            }
        }

        Column {
             Row(
                 modifier = Modifier
                     .fillMaxWidth()
                     .padding(horizontal = 16.dp, vertical = 4.dp),
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
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant
                 )
                 if (cachedCount > 0) {
                     TextButton(onClick = { clearCache(onShowSnackbar) }) {
                         Text(stringResource(R.string.clear_cache))
                     }
                 }
             }

             LazyColumn(
                 contentPadding = PaddingValues(bottom = 80.dp)
             ) {
                 items(filteredApps, key = { it.id }) { item ->
                     AppItemRow(item, cacheVersion, onInstall = { onInstallClicked(it, onShowSnackbar) }, onDelete = {
                         appsList = appsList.filter { x -> x.id != it.id }
                         saveItems()
                         onShowSnackbar("Đã xóa ${it.name}")
                     })
                 }
             }
        }
    }

    @Composable
    fun AppItemRow(item: ApkItem, cacheVersion: Int, onInstall: (ApkItem) -> Unit, onDelete: (ApkItem) -> Unit) {
        val context = LocalContext.current
        val stats = fileStats[item.id]
        val isCached = stats?.exists == true
        val fileSize = stats?.size ?: 0L
        
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
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
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isCached) {
                        Text(
                            text = "${formatFileSize(fileSize)} • ${stringResource(R.string.cached)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
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
                    Icon(Icons.Default.Download, contentDescription = "Download/Install")
                }
            }
        }
    }

    @Composable
    fun ScriptsListContent(searchQuery: String, onShowSnackbar: (String) -> Unit, onDownloadRequest: (ScriptItem) -> Unit) {
        val filteredScripts = remember(scriptsList, searchQuery) {
            val q = searchQuery.trim().lowercase()
             if (q.isEmpty()) {
                scriptsList
            } else {
                scriptsList.filter {
                    it.name.lowercase().contains(q) ||
                            it.gameName.lowercase().contains(q)
                }
            }
        }

        LazyColumn(
             contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            items(filteredScripts, key = { it.id }) { script ->
                ScriptItemRow(
                    script = script,
                    onDownload = { onDownloadRequest(script) },
                    onCopy = { copyScript(it, onShowSnackbar) },
                    onDelete = { deleteScript(it, onShowSnackbar) }
                )
            }
        }
    }
    
    @Composable
    fun ScriptItemRow(script: ScriptItem, onDownload: (ScriptItem) -> Unit, onCopy: (ScriptItem) -> Unit, onDelete: (ScriptItem) -> Unit) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
             elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = script.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = script.gameName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (script.localPath == null) {
                        OutlinedButton(
                            onClick = { onDownload(script) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.download))
                        }
                    }
                    Button(
                        onClick = { onCopy(script) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.copy_script))
                    }
                    if (script.localPath != null) {
                        IconButton(onClick = { onDelete(script) }) {
                             Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
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
            val prefs = getSharedPreferences("apk_items", Context.MODE_PRIVATE)
            val json = prefs.getString("list", null)
            ApkItem.fromJsonList(json)
        }
        appsList = loaded.ifEmpty { emptyList() }.toMutableList()
    }

    private fun saveItems() {
        val prefs = getSharedPreferences("apk_items", Context.MODE_PRIVATE)
        val json = ApkItem.toJsonList(appsList)
        prefs.edit().putString("list", json).apply()
    }

    private fun cacheFileFor(item: ApkItem): File {
        val dir = File(cacheDir, "apks")
        val ext = try {
            val u = item.url?.lowercase(Locale.ROOT)
            when {
                u != null && u.contains(".xapk") -> "xapk"
                u != null && u.contains(".apks") -> "apks"
                u != null && u.contains(".apkm") -> "apkm"
                else -> "apk"
            }
        } catch (_: Exception) { "apk" }
        return File(dir, "${item.id}.$ext")
    }

    private fun stableIdFromUrl(url: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-1")
            val bytes = md.digest(url.toByteArray())
            bytes.joinToString("") { b -> "%02x".format(b) }
        } catch (_: Exception) {
            url.hashCode().toString()
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
                val arr: Array<PreloadApp> = Gson().fromJson(body, Array<PreloadApp>::class.java)
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
                val id = stableIdFromUrl(p.url)
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
                val apkFile = when (item.sourceType) {
                    SourceType.LOCAL -> if (item.uri != null) copyFromUriIfNeeded(Uri.parse(item.uri)) else null
                    SourceType.URL -> downloadApk(item)
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
        val outFile = cacheFileFor(item)
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
            withContext(Dispatchers.Main) {
                cacheVersion++
            }
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
         lifecycleScope.launch(Dispatchers.IO) {
            val apkCacheDir = File(cacheDir, "apks")
            val splitsDir = File(cacheDir, "splits")
            val obbCacheDir = File(cacheDir, "obb")
            var count = 0
            var size = 0L

            if (apkCacheDir.exists()) {
                apkCacheDir.listFiles()?.forEach { file ->
                    size += file.length()
                    file.delete()
                    count++
                }
            }
             if (splitsDir.exists()) splitsDir.deleteRecursively()
             if (obbCacheDir.exists()) obbCacheDir.deleteRecursively()

             withContext(Dispatchers.Main) {
                 val sizeStr = formatFileSize(size)
                 onShowSnackbar("Đã xóa cache: $count tệp ($sizeStr)")
                 // Refresh list to update UI state (re-calculate cache existence)
                 appsList = appsList.toList()
                 cacheVersion++
             }
         }
    }

    // --- Scripts Logic ---

    private suspend fun loadScriptsFromOnline() {
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(DEFAULT_SCRIPTS_URL).header("User-Agent", "CloudPhoneTool/1.0").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext
                    val body = resp.body?.string() ?: return@withContext
                    val jsonArray = org.json.JSONArray(body)
                    val newScripts = mutableListOf<ScriptItem>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val url = obj.getString("url")
                        newScripts.add(
                            ScriptItem(
                                id = stableIdFromUrl(url),
                                name = obj.getString("name"),
                                gameName = obj.getString("gameName"),
                                url = url
                            )
                        )
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
                // Merge: Start with all known online scripts and check if they exist locally
                // OPTIMIZATION: Use Map for O(1) lookup (O(N+M) complexity)
                val localMap = HashMap<String, ScriptItem>()
                for (local in newLocals) {
                    // newLocals.find uses first match, so we only add if not present
                    if (!localMap.containsKey(local.name)) {
                        localMap[local.name] = local
                    }
                }

                val mergedList = onlineScriptsList.map { onlineScript ->
                    val match = localMap[onlineScript.name]
                    if (match != null) {
                        onlineScript.copy(localPath = match.localPath)
                    } else {
                        onlineScript
                    }
                }

                // Efficiently identify used paths to filter unmatched locals
                val usedLocalPaths = HashSet<String>()
                for (item in mergedList) {
                    if (item.localPath != null) {
                        usedLocalPaths.add(item.localPath)
                    }
                }

                val unmatchedLocals = newLocals.filter { local ->
                    local.localPath != null && !usedLocalPaths.contains(local.localPath)
                }

                scriptsList = mergedList + unmatchedLocals
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
                dir.mkdirs()
                // Save with .txt extension as requested
                val fileName = if (script.name.lowercase().endsWith(".txt")) script.name else "${script.name}.txt"
                File(dir, fileName).writeText(content)
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
                val content = if (script.localPath != null) {
                    File(script.localPath).readText()
                } else if (script.url != null) {
                    if (script.url.contains("/source/hard/")) fetchScriptBody(script.url)
                    else "loadstring(game:HttpGet(\"${script.url}\"))()"
                } else ""

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
            File(script.localPath).delete()
            onShowSnackbar(getString(R.string.deleted_script))
            lifecycleScope.launch { loadScriptsFromLocal() }
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
                obbFile.inputStream().use { input -> FileOutputStream(destFile).use { output -> input.copyTo(output) } }
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

// Data models
enum class SortMode { NAME_ASC, NAME_DESC, SIZE_DESC, DATE_DESC }
data class FileStats(val exists: Boolean, val size: Long, val lastModified: Long)
data class ScriptItem(val id: String, val name: String, val gameName: String, val url: String? = null, val localPath: String? = null)

data class ApkItem(
    val id: String,
    val name: String,
    val sourceType: SourceType,
    val url: String?,
    val uri: String?,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val iconUrl: String? = null
) {
    companion object {
        private val gson = GsonBuilder()
            .registerTypeAdapter(ApkItem::class.java, object : TypeAdapter<ApkItem>() {
                override fun write(out: JsonWriter, value: ApkItem?) {
                    if (value == null) {
                        out.nullValue()
                        return
                    }
                    out.beginObject()
                    out.name("id").value(value.id)
                    out.name("name").value(value.name)
                    out.name("sourceType").value(value.sourceType.name)
                    out.name("url").value(value.url)
                    out.name("uri").value(value.uri)
                    out.name("versionName").value(value.versionName)
                    out.name("versionCode")
                    if (value.versionCode == null) out.nullValue() else out.value(value.versionCode)
                    out.name("iconUrl").value(value.iconUrl)
                    out.endObject()
                }

                override fun read(input: JsonReader): ApkItem {
                    if (input.peek() == JsonToken.NULL) {
                        input.nextNull()
                        throw IllegalStateException("ApkItem cannot be null")
                    }

                    var id: String? = null
                    var name: String? = null
                    var sourceType: SourceType = SourceType.URL
                    var url: String? = null
                    var uri: String? = null
                    var versionName: String? = null
                    var versionCode: Long? = null
                    var iconUrl: String? = null

                    input.beginObject()
                    while (input.hasNext()) {
                        val propertyName = input.nextName()
                        if (input.peek() == JsonToken.NULL) {
                            input.nextNull()
                            continue
                        }
                        when (propertyName) {
                            "id" -> id = input.nextString()
                            "name" -> name = input.nextString()
                            "sourceType" -> {
                                sourceType = try {
                                    SourceType.valueOf(input.nextString())
                                } catch (_: Exception) {
                                    SourceType.URL
                                }
                            }
                            "url" -> url = input.nextString()
                            "uri" -> uri = input.nextString()
                            "versionName" -> versionName = input.nextString()
                            "versionCode" -> versionCode = input.nextLong()
                            "iconUrl" -> iconUrl = input.nextString()
                            else -> input.skipValue()
                        }
                    }
                    input.endObject()

                    return ApkItem(
                        id = id ?: UUID.randomUUID().toString(),
                        name = name ?: "APK",
                        sourceType = sourceType,
                        url = url,
                        uri = uri,
                        versionName = versionName,
                        versionCode = versionCode,
                        iconUrl = iconUrl
                    )
                }
            })
            .create()

        fun toJsonList(list: List<ApkItem>): String {
            return gson.toJson(list)
        }

        fun fromJsonList(json: String?): List<ApkItem> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                gson.fromJson(json, Array<ApkItem>::class.java)?.toList() ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}

enum class SourceType { URL, LOCAL }

data class PreloadApp(
    val name: String,
    val url: String,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val iconUrl: String? = null
)