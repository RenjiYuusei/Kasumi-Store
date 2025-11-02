package com.kasumi.tool

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.android.material.tabs.TabLayout
import android.app.PendingIntent
import com.google.android.material.progressindicator.LinearProgressIndicator
import androidx.core.content.pm.PackageInfoCompat
import com.google.android.material.textfield.TextInputLayout
 
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.TimeUnit
import android.text.Editable
import android.text.TextWatcher
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()
    }
    private val DEFAULT_SOURCE_URL = "https://raw.githubusercontent.com/RenjiYuusei/Kasumi/main/source/apps.json"
    private val DEFAULT_SCRIPTS_URL = "https://raw.githubusercontent.com/RenjiYuusei/Kasumi/main/source/scripts.json"

    private lateinit var listView: RecyclerView
    private lateinit var scriptListView: RecyclerView
    private lateinit var adapter: ApkAdapter
    private lateinit var scriptAdapter: ScriptAdapter
    private val items = mutableListOf<ApkItem>()            // nguồn dữ liệu đầy đủ
    private val filteredItems = mutableListOf<ApkItem>()     // danh sách sau khi lọc để hiển thị
    private val preloadedIds = mutableSetOf<String>()
    private lateinit var tabLayout: TabLayout
    private lateinit var sourceBar: View
    private lateinit var searchLayout: TextInputLayout
    private lateinit var searchInput: EditText
    private lateinit var btnRefreshSource: Button
    private lateinit var logContainer: View
    private lateinit var logView: TextView
    private val loadingIds = mutableSetOf<String>()
    private val scriptItems = mutableListOf<ScriptItem>()
    private val scriptFilteredItems = mutableListOf<ScriptItem>()
    private var currentQuery: String = ""
    private var currentScriptQuery: String = ""
    private var currentTab: Int = 0 // 0: Ứng dụng, 1: Script, 2: Nhật ký
    private var suppressSearchWatcher: Boolean = false
    private lateinit var globalProgress: LinearProgressIndicator
    private lateinit var statsBar: View
    private lateinit var txtStats: TextView
    private lateinit var btnClearCache: Button
    private lateinit var btnSort: Button
    private var sortMode: SortMode = SortMode.NAME_ASC
    
    enum class SortMode {
        NAME_ASC, NAME_DESC, SIZE_DESC, DATE_DESC
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Force dark mode
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        setContentView(R.layout.activity_main)

        listView = findViewById(R.id.recycler)
        scriptListView = findViewById(R.id.recycler_installed)
        tabLayout = findViewById(R.id.tab_layout)
        sourceBar = findViewById(R.id.source_bar)
        logContainer = findViewById(R.id.log_container)
        logView = findViewById(R.id.log_view)
        searchLayout = findViewById(R.id.search_layout)
        searchInput = findViewById(R.id.search_input)
        btnRefreshSource = findViewById(R.id.btn_refresh_source)
        globalProgress = findViewById(R.id.global_progress)
        statsBar = findViewById(R.id.stats_bar)
        txtStats = findViewById(R.id.txt_stats)
        btnClearCache = findViewById(R.id.btn_clear_cache)
        btnSort = findViewById(R.id.btn_sort)
        btnRefreshSource.setOnClickListener {
            lifecycleScope.launch {
                setBusy(true)
                refreshPreloadedApps()
                setBusy(false)
                toast("Đã làm mới nguồn")
            }
        }
        btnSort.setOnClickListener { showSortMenu() }
        btnClearCache.setOnClickListener { clearCache() }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (suppressSearchWatcher) return
                val q = s?.toString() ?: ""
                if (currentTab == 0) {
                    applyFilter(q)
                } else if (currentTab == 1) {
                    applyScriptFilter(q)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        adapter = ApkAdapter(filteredItems, preloadedIds,
            isLoading = { id -> loadingIds.contains(id) },
            getCachedFile = { item -> cacheFileFor(item) },
            onInstall = { item -> onInstallClicked(item) },
            onDelete = { item ->
                items.removeAll { it.id == item.id }
                saveItems()
                applyFilter(currentQuery)
                updateStats()
                log("Đã xóa mục: ${item.name}")
            }
        )
        listView.layoutManager = LinearLayoutManager(this)
        listView.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        listView.adapter = adapter

        scriptAdapter = ScriptAdapter(scriptFilteredItems,
            onDownload = { script -> showDownloadFolderDialog(script) },
            onCopy = { script -> copyScript(script) },
            onDelete = { script -> deleteScript(script) }
        )
        scriptListView.layoutManager = LinearLayoutManager(this)
        scriptListView.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        scriptListView.adapter = scriptAdapter

        setupTabs()
        
        // Khởi tạo log với thông báo chào mừng
        logView.text = "" // Clear placeholder text
        log("=== Kasumi v${resolveAppVersionName()} ===")

        loadItems()
        // Nạp preload từ nguồn mặc định (cố định)
        lifecycleScope.launch {
            refreshPreloadedApps(initial = true)
            applyFilter("")
            updateStats()
        }
        updateCachedVersions()
        applyFilter(currentQuery)
        
        // Load scripts from online source and local folders
        lifecycleScope.launch {
            loadScriptsFromOnline()
            loadScriptsFromLocal()
            applyScriptFilter(currentScriptQuery)
        }
        
        // Log thông tin môi trường
        logEnvForDebug("STARTUP")
        
        // Yêu cầu quyền storage (cần cho OBB)
        requestStoragePermission()
    }
    
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

    private fun setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText("Ứng dụng").setIcon(R.drawable.ic_apps))
        tabLayout.addTab(tabLayout.newTab().setText("Script").setIcon(R.drawable.ic_installed))
        tabLayout.addTab(tabLayout.newTab().setText("Nhật ký").setIcon(R.drawable.ic_log))
        showAppsTab()
        syncSearchInputWithTab()
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (tab.position) {
                    0 -> { currentTab = 0; showAppsTab(); syncSearchInputWithTab() }
                    1 -> { currentTab = 1; showScriptTab(); syncSearchInputWithTab() }
                    else -> { currentTab = 2; showLogTab(); syncSearchInputWithTab() }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab) {
                if (tab.position == 2) scrollLogToBottom()
            }
        })
    }

    private fun syncSearchInputWithTab() {
        suppressSearchWatcher = true
        try {
            when (currentTab) {
                0 -> { // Apps tab
                    searchLayout.isHintEnabled = true
                    searchLayout.hint = getString(R.string.search_hint)
                    val want = currentQuery
                    if ((searchInput.text?.toString() ?: "") != want) searchInput.setText(want)
                }
                1 -> { // Script tab
                    searchLayout.isHintEnabled = true
                    searchLayout.hint = getString(R.string.search_scripts_hint)
                    val want = currentScriptQuery
                    if ((searchInput.text?.toString() ?: "") != want) searchInput.setText(want)
                }
                else -> {
                    searchLayout.isHintEnabled = false
                    if (!searchInput.text.isNullOrEmpty()) searchInput.setText("")
                }
            }
        } finally {
            suppressSearchWatcher = false
        }
    }

    private fun showAppsTab() {
        listView.visibility = View.VISIBLE
        scriptListView.visibility = View.GONE
        logContainer.visibility = View.GONE
        sourceBar.visibility = View.VISIBLE
        btnRefreshSource.visibility = View.VISIBLE
        btnSort.visibility = View.VISIBLE
        updateStats()
    }

    private fun showLogTab() {
        listView.visibility = View.GONE
        scriptListView.visibility = View.GONE
        logContainer.visibility = View.VISIBLE
        sourceBar.visibility = View.GONE
        statsBar.visibility = View.GONE
        // Ensure log is properly initialized and visible
        if (::logView.isInitialized) {
            scrollLogToBottom()
        }
    }

    private fun showScriptTab() {
        listView.visibility = View.GONE
        logContainer.visibility = View.GONE
        scriptListView.visibility = View.VISIBLE
        sourceBar.visibility = View.VISIBLE
        btnRefreshSource.visibility = View.GONE
        btnSort.visibility = View.GONE
        statsBar.visibility = View.GONE
    }

    private fun resolveAppVersionName(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName ?: "?"
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
            }
        } catch (_: Exception) {
            "?"
        }
    }

    private fun log(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logView.append("[$ts] $msg\n")
        // Giữ log gọn: giới hạn ~4000 ký tự cuối
        val txt = logView.text?.toString() ?: ""
        if (txt.length > 6000) {
            logView.text = txt.takeLast(4000)
        }
        scrollLogToBottom()
    }

    private fun logBg(msg: String) = runOnUiThread { log(msg) }

    private fun scrollLogToBottom() {
        val parent = logView.parent
        if (parent is ScrollView) {
            parent.post { parent.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun setBusy(busy: Boolean) {
        globalProgress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    // Ghi log thông tin hệ thống và môi trường root để hỗ trợ chẩn đoán
    private fun logEnvForDebug(stage: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val env = RootInstaller.probeRootEnv()
            val androidRelease = try { Build.VERSION.RELEASE } catch (_: Exception) { "?" }
            val rom = try { Build.DISPLAY } catch (_: Exception) { Build.ID }
            val device = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            val lines = listOf(
                "ENV/$stage: Android $androidRelease (SDK ${Build.VERSION.SDK_INT}), ROM=$rom, Device=$device",
                "ENV/$stage: Root provider=${env.provider}, suPath=${env.suPath ?: "-"}, suVer=${env.suVersion ?: "-"}, uid0=${env.uid0}",
                "ENV/$stage: Magisk=${env.magiskVersionName ?: "-"} (code=${env.magiskVersionCode ?: "-"}), KernelSU=${env.kernelSuVersion ?: "-"}"
            )
            withContext(Dispatchers.Main) { lines.forEach { log(it) } }
        }
    }

    private fun mergePreloaded(preloaded: List<PreloadApp>) {
        preloadedIds.clear()
        var updated = 0
        for (p in preloaded) {
            val id = stableIdFromUrl(p.url)
            preloadedIds.add(id)
            val idx = items.indexOfFirst { it.id == id }
            val normalized = normalizeUrl(p.url)
            if (idx >= 0) {
                val exist = items[idx]
                items[idx] = exist.copy(
                    name = p.name,
                    sourceType = SourceType.URL,
                    url = normalized,
                    uri = null,
                    versionName = exist.versionName ?: p.versionName,
                    versionCode = exist.versionCode ?: p.versionCode,
                    iconUrl = p.iconUrl
                )
            } else {
                items.add(
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
                )
            }
            updated++
        }
    }

    private suspend fun fetchPreloadedAppsRemote(url: String): List<PreloadApp>? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).header("User-Agent", "CloudPhoneTool/1.0").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    logBg("Nguồn online thất bại: HTTP ${resp.code}")
                    return@withContext null
                }
                val body = resp.body?.string() ?: return@withContext null
                // Tránh dùng TypeToken để không phụ thuộc generic signature khi minify
                val arr: Array<PreloadApp> = Gson().fromJson(body, Array<PreloadApp>::class.java)
                log("Đã tải ${arr.size} ứng dụng từ nguồn")
                arr.toList()
            }
        } catch (e: Exception) {
            logBg("Lỗi tải nguồn: ${e.message}")
            null
        }
    }

    private suspend fun refreshPreloadedApps(initial: Boolean = false) {
        val preloaded: List<PreloadApp>? = fetchPreloadedAppsRemote(DEFAULT_SOURCE_URL)
        if (preloaded != null) {
            mergePreloaded(preloaded)
            withContext(Dispatchers.Main) { applyFilter(currentQuery) }
        } else {
            if (!initial) logBg("Không thể nạp danh sách preload từ nguồn mặc định")
        }
    }

    private fun applyFilter(q: String) {
        currentQuery = q
        val needle = q.trim().lowercase(Locale.getDefault())
        filteredItems.clear()
        val filtered = if (needle.isEmpty()) {
            items.toList()
        } else {
            items.filter {
                it.name.lowercase(Locale.getDefault()).contains(needle)
                        || (it.url?.lowercase(Locale.getDefault())?.contains(needle) == true)
                        || (it.uri?.lowercase(Locale.getDefault())?.contains(needle) == true)
            }
        }
        
        // Áp dụng sắp xếp
        val sorted = when (sortMode) {
            SortMode.NAME_ASC -> filtered.sortedBy { it.name.lowercase() }
            SortMode.NAME_DESC -> filtered.sortedByDescending { it.name.lowercase() }
            SortMode.SIZE_DESC -> filtered.sortedByDescending { 
                val f = cacheFileFor(it)
                if (f.exists()) f.length() else 0L
            }
            SortMode.DATE_DESC -> filtered.sortedByDescending { 
                val f = cacheFileFor(it)
                if (f.exists()) f.lastModified() else 0L
            }
        }
        
        filteredItems.addAll(sorted)
        adapter.notifyDataSetChanged()
    }

    @Suppress("DEPRECATION")
    private fun extractApkVersion(file: File): Pair<String?, Long?> {
        return try {
            val pm = packageManager
            val pi = pm.getPackageArchiveInfo(file.absolutePath, 0)
            if (pi != null) {
                val name = pi.versionName
                val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
                log("Đọc phiên bản từ APK: v=$name (code $code)")
                name to code
            } else null to null
        } catch (_: Exception) {
            log("Không đọc được phiên bản từ APK")
            null to null
        }
    }

    // Đã loại bỏ nạp từ raw/preload_apps.json để cố định nguồn online mặc định

    private fun stableIdFromUrl(url: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-1")
            val bytes = md.digest(url.toByteArray())
            bytes.joinToString("") { b -> "%02x".format(b) }
        } catch (_: Exception) {
            url.hashCode().toString()
        }
    }

    private fun onInstallClicked(item: ApkItem) {
        lifecycleScope.launch {
            val idxLoading = items.indexOfFirst { it.id == item.id }
            if (idxLoading >= 0) {
                loadingIds.add(item.id)
                applyFilter(currentQuery)
                setBusy(true)
            }
            try {
                val apkFile = when (item.sourceType) {
                    SourceType.LOCAL -> copyFromUriIfNeeded(Uri.parse(item.uri!!))
                    SourceType.URL -> downloadApk(item)
                }
                if (apkFile == null) {
                    toast("Không thể chuẩn bị tệp APK")
                    log("Lỗi: Không thể chuẩn bị tệp APK cho ${item.name}")
                    return@launch
                }

                val urlLower = item.url?.lowercase(Locale.ROOT)
                val fileNameLower = apkFile.name.lowercase(Locale.ROOT)
                val isSplitPackage = (urlLower?.contains(".apks") == true || fileNameLower.endsWith(".apks"))
                        || (urlLower?.contains(".xapk") == true || fileNameLower.endsWith(".xapk"))
                if (isSplitPackage) {
                    // Xử lý cài đặt gói chia nhỏ (.apks hoặc .xapk)
                    val (splits, obbInfo) = withContext(Dispatchers.IO) { extractSplitsAndObb(apkFile) }
                    if (splits.isEmpty()) {
                        toast("Không tìm thấy APK bên trong file")
                        log("File split không chứa APK hợp lệ: ${apkFile.absolutePath}")
                        return@launch
                    }
                    
                    // Copy OBB nếu có
                    if (obbInfo != null) {
                        withContext(Dispatchers.IO) { installObbFiles(obbInfo) }
                    }
                    
                    val rooted = RootInstaller.isDeviceRooted()
                    log("Cài đặt ${splits.size} APK qua ${if (rooted) "root" else "session"}")
                    if (rooted) {
                        val resSplit: Pair<Boolean, String> = withContext(Dispatchers.IO) { RootInstaller.installApks(splits) }
                        val (ok, msg) = resSplit
                        if (ok) {
                            toast("Cài đặt thành công")
                            log("✓ Cài thành công")
                        } else {
                            log("Root thất bại: $msg. Thử session...")
                            installSplitsNormally(splits)
                        }
                    } else {
                        installSplitsNormally(splits)
                    }
                    return@launch
                }

                if (!isLikelyApk(apkFile)) {
                    log("Tệp tải về không phải APK (size=${apkFile.length()}B). Có thể là trang HTML hoặc sai link.")
                    toast("Tệp tải về không phải APK. Kiểm tra lại link tải.")
                    return@launch
                }

                val rooted = RootInstaller.isDeviceRooted()
                log("Cài đặt qua ${if (rooted) "root" else "system installer"}")
                if (rooted) {
                    val resApk: Pair<Boolean, String> = withContext(Dispatchers.IO) { RootInstaller.installApk(apkFile) }
                    val (ok, msg) = resApk
                    if (ok) {
                        toast("Cài đặt thành công")
                        log("✓ Cài thành công")
                    } else {
                        log("Root thất bại: $msg")
                        installNormally(apkFile)
                    }
                } else {
                    installNormally(apkFile)
                }
            } catch (e: Exception) {
                toast("Lỗi: ${e.message}")
                log("Lỗi: ${e.message}")
            } finally {
                if (idxLoading >= 0) {
                    loadingIds.remove(item.id)
                    applyFilter(currentQuery)
                    if (loadingIds.isEmpty()) setBusy(false)
                }
            }
        }
    }

    private fun installNormally(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                // Yêu cầu người dùng cho phép cài đặt từ nguồn không xác định
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    startActivity(intent)
                    toast("Hãy cấp quyền, sau đó bấm lại để cài đặt")
                } catch (e: ActivityNotFoundException) {
                    // ignore
                }
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
            toast("Không mở được installer: ${e.message}")
        }
    }

    private suspend fun downloadApk(item: ApkItem): File? = withContext(Dispatchers.IO) {
        val url = item.url ?: return@withContext null
        val normalized = url
        logBg("Tải: ${item.name}")
        val outFile = cacheFileFor(item)
        val req = Request.Builder()
            .url(normalized)
            .header("User-Agent", "Mozilla/5.0 (Android) Kasumi/1.0")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                logBg("Tải thất bại: HTTP ${resp.code}")
                throw IllegalStateException("HTTP ${resp.code}")
            }
            resp.body?.byteStream()?.use { input ->
                FileOutputStream(outFile).use { out ->
                    copyStreamWithProgress(input, out)
                }
            }
            logBg("Đã tải xong: ${outFile.length() / 1024 / 1024}MB")
            outFile
        }
    }

    // Đã loại bỏ toàn bộ xử lý Google Drive, chỉ sử dụng tải trực tiếp (ưu tiên Dropbox direct)

    private fun ensureApkExtension(name: String): String {
        val lower = name.lowercase(Locale.ROOT)
        return if (lower.endsWith(".apk") || lower.endsWith(".apks") || lower.endsWith(".xapk")) name else "$name.apk"
    }

    data class ObbInfo(val packageName: String, val obbFiles: List<File>)
    
    // Giải nén file .apks hoặc .xapk để lấy danh sách các APK và OBB
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
                    
                    // Đọc manifest.json để lấy package name
                    if (entryName.endsWith("manifest.json")) {
                        try {
                            val manifest = zipFile.getInputStream(entry).bufferedReader().readText()
                            packageName = JSONObject(manifest).optString("package_name")
                        } catch (e: Exception) {}
                        continue
                    }
                    
                    // Giải nén APK
                    if (entryName.endsWith(".apk")) {
                        val outFile = File(outDir, fileName)
                        outFile.parentFile?.mkdirs()
                        
                        zipFile.getInputStream(entry).use { input ->
                            FileOutputStream(outFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        
                        if (outFile.exists() && outFile.length() > 0) {
                            results.add(outFile)
                        }
                        continue
                    }
                    
                    // Giải nén OBB
                    if (entryName.endsWith(".obb")) {
                        val obbDir = File(cacheDir, "obb")
                        obbDir.mkdirs()
                        val outFile = File(obbDir, fileName)
                        
                        zipFile.getInputStream(entry).use { input ->
                            FileOutputStream(outFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        
                        if (outFile.exists() && outFile.length() > 0) {
                            obbFiles.add(outFile)
                        }
                        continue
                    }
                }
            }
        } catch (e: Exception) {
            logBg("Lỗi giải nén: ${e.message}")
        }
        
        val apkSizeMB = results.sumOf { it.length() / 1024 / 1024 }
        val obbSizeMB = obbFiles.sumOf { it.length() / 1024 / 1024 }
        logBg("Giải nén ${results.size} APK (${apkSizeMB}MB)" + 
            if (obbFiles.isNotEmpty()) ", ${obbFiles.size} OBB (${obbSizeMB}MB)" else "")
        
        // Sắp xếp APK: base.apk hoặc file APK chính trước
        val sortedApks = results.sortedWith(compareBy(
            { !it.name.startsWith("base.") && !it.name.contains("com.") },
            { it.name.startsWith("config.") || it.name.startsWith("split_") },
            { it.name }
        ))
        
        val obbInfo = if (obbFiles.isNotEmpty() && packageName != null) {
            ObbInfo(packageName, obbFiles)
        } else null
        
        return sortedApks to obbInfo
    }
    
    private fun installObbFiles(obbInfo: ObbInfo) {
        try {
            // Kiểm tra quyền storage
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
                    logBg("Cần quyền quản lý storage để copy OBB")
                    return
                }
            }
            
            val obbDir = File(Environment.getExternalStorageDirectory(), "Android/obb/${obbInfo.packageName}")
            if (!obbDir.exists()) {
                obbDir.mkdirs()
            }
            
            for (obbFile in obbInfo.obbFiles) {
                val destFile = File(obbDir, obbFile.name)
                obbFile.inputStream().use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                logBg("Copy OBB: ${obbFile.name} (${obbFile.length() / 1024 / 1024}MB)")
            }
            
            logBg("✓ Đã copy ${obbInfo.obbFiles.size} file OBB vào /Android/obb/${obbInfo.packageName}")
        } catch (e: Exception) {
            logBg("Lỗi copy OBB: ${e.message}")
        }
    }

    // Cài đặt nhiều APK (split) theo cách thường bằng PackageInstaller
    private fun installSplitsNormally(files: List<File>) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!packageManager.canRequestPackageInstalls()) {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:$packageName")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    toast("Hãy cấp quyền cài đặt ứng dụng không xác định, sau đó thử lại")
                    return
                }
            }
            val installer = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            try {
                for (f in files) {
                    FileInputStream(f).use { input ->
                        session.openWrite(f.name, 0, f.length()).use { out ->
                            val buf = ByteArray(8 * 1024)
                            while (true) {
                                val r = input.read(buf)
                                if (r == -1) break
                                out.write(buf, 0, r)
                            }
                            session.fsync(out)
                        }
                    }
                }
                val action = "${packageName}.INSTALL_COMMIT"
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        try { unregisterReceiver(this) } catch (_: Exception) {}
                        val status = intent?.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE) ?: PackageInstaller.STATUS_FAILURE
                        val msg = intent?.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                        if (status == PackageInstaller.STATUS_SUCCESS) {
                            toast("Cài đặt thành công")
                            log("Cài đặt splits (thường) thành công")
                        } else if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                            val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                                intent?.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                            } else {
                                @Suppress("DEPRECATION") intent?.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                            }
                            try { startActivity(confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                        } else {
                            toast("Cài đặt thất bại: $msg")
                            log("Cài đặt splits (thường) thất bại: $msg")
                        }
                    }
                }
                registerReceiver(receiver, IntentFilter(action))
                val pi = PendingIntent.getBroadcast(this, sessionId, Intent(action), PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
                session.commit(pi.intentSender)
                toast("Đang tiến hành cài đặt…")
            } finally {
                session.close()
            }
        } catch (e: Exception) {
            toast("Lỗi cài đặt splits: ${e.message}")
            log("Lỗi cài đặt splits (thường): ${e.message}")
        }
    }

    private fun copyStreamWithProgress(input: InputStream, out: FileOutputStream) {
        val buf = ByteArray(8 * 1024)
        while (true) {
            val r = input.read(buf)
            if (r == -1) break
            out.write(buf, 0, r)
        }
        out.flush()
    }

    private fun cacheFileFor(item: ApkItem): File {
        val dir = File(cacheDir, "apks").apply { mkdirs() }
        val ext = try {
            val u = item.url?.lowercase(Locale.ROOT)
            when {
                u != null && u.contains(".xapk") -> "xapk"
                u != null && u.contains(".apks") -> "apks"
                else -> "apk"
            }
        } catch (_: Exception) { "apk" }
        return File(dir, "${item.id}.$ext")
    }

    // Kiểm tra nhanh file có phải APK (ZIP) bằng signature 'PK'
    private fun isLikelyApk(file: File): Boolean {
        return try {
            if (!file.exists() || file.length() < 4) return false
            file.inputStream().use { ins ->
                val sig = ByteArray(2)
                val r = ins.read(sig)
                r == 2 && sig[0] == 0x50.toByte() && sig[1] == 0x4B.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun copyFromUriIfNeeded(uri: Uri): File? = withContext(Dispatchers.IO) {
        try {
            val name = queryDisplayName(uri) ?: "picked.apk"
            val dir = File(cacheDir, "apks").apply { mkdirs() }
            val outFile = File(dir, ensureApkExtension(name))
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { out ->
                    copyStreamWithProgress(input, out)
                }
            }
            outFile
        } catch (e: Exception) {
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
        } catch (_: Exception) {
            null
        }
    }

    // Chuẩn hoá Dropbox -> direct download; bỏ toàn bộ xử lý Google Drive
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

    private fun guessNameFromUrl(url: String): String {
        return try {
            val path = Uri.parse(url).lastPathSegment ?: "download.apk"
            if (path.contains('.')) path else "$path.apk"
        } catch (_: Exception) {
            "download.apk"
        }
    }

    private fun guessNameFromHeaders(disposition: String?): String? {
        if (disposition == null) return null
        val regex = Regex("filename=\\\"?(.*?)\\\"?(;|$)")
        val m = regex.find(disposition)
        return m?.groupValues?.getOrNull(1)
    }

    private fun saveItems() {
        val prefs = getSharedPreferences("apk_items", Context.MODE_PRIVATE)
        val json = ApkItem.toJsonList(items)
        prefs.edit().putString("list", json).apply()
    }

    private fun loadItems() {
        val prefs = getSharedPreferences("apk_items", Context.MODE_PRIVATE)
        val json = prefs.getString("list", null)
        val loaded = ApkItem.fromJsonList(json)
        items.clear()
        items.addAll(loaded)
    }

    private fun updateCachedVersions() {
        var changed = false
        for ((index, it) in items.withIndex()) {
            if (it.sourceType == SourceType.URL) {
                val f = cacheFileFor(it)
                if (f.exists() && (it.versionName == null || it.versionCode == null)) {
                    val (vn, vc) = extractApkVersion(f)
                    if (vn != null || vc != null) {
                        items[index] = it.copy(versionName = vn, versionCode = vc)
                        changed = true
                    }
                }
            }
        }
        if (changed) saveItems()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // Chức năng sắp xếp và thống kê
    private fun showSortMenu() {
        val options = arrayOf(
            getString(R.string.sort_by_name),
            getString(R.string.sort_by_name_desc),
            getString(R.string.sort_by_size),
            getString(R.string.sort_by_date)
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.sort))
            .setItems(options) { _, which ->
                sortMode = when (which) {
                    0 -> SortMode.NAME_ASC
                    1 -> SortMode.NAME_DESC
                    2 -> SortMode.SIZE_DESC
                    else -> SortMode.DATE_DESC
                }
                applyFilter(currentQuery)
            }
            .show()
    }
    
    private fun updateStats() {
        val cachedCount = items.count { 
            val f = cacheFileFor(it)
            f.exists()
        }
        val totalSize = items.mapNotNull { 
            val f = cacheFileFor(it)
            if (f.exists()) f.length() else null
        }.sum()
        
        val sizeStr = formatFileSize(totalSize)
        txtStats.text = getString(R.string.stats_format, items.size, "$cachedCount ($sizeStr)")
        statsBar.visibility = if (items.isNotEmpty()) View.VISIBLE else View.GONE
        btnClearCache.visibility = if (cachedCount > 0) View.VISIBLE else View.GONE
    }
    
    private fun clearCache() {
        lifecycleScope.launch(Dispatchers.IO) {
            val apkCacheDir = File(cacheDir, "apks")
            val splitsDir = File(cacheDir, "splits")
            val obbCacheDir = File(cacheDir, "obb")
            var count = 0
            var size = 0L
            
            // Xóa cache APK/APKS/XAPK
            if (apkCacheDir.exists()) {
                apkCacheDir.listFiles()?.forEach { file ->
                    size += file.length()
                    file.delete()
                    count++
                }
            }
            
            // Xóa thư mục splits đã giải nén
            if (splitsDir.exists()) {
                splitsDir.deleteRecursively()
            }
            
            // Xóa cache OBB
            if (obbCacheDir.exists()) {
                obbCacheDir.deleteRecursively()
            }
            
            withContext(Dispatchers.Main) {
                val sizeStr = formatFileSize(size)
                toast(getString(R.string.cache_cleared, count, sizeStr))
                log("Đã xóa cache: $count tệp ($sizeStr)")
                updateStats()
                applyFilter(currentQuery)
            }
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

    // Khu vực quản lý Scripts
    private suspend fun loadScriptsFromOnline() {
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(DEFAULT_SCRIPTS_URL).header("User-Agent", "CloudPhoneTool/1.0").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        logBg("Không thể tải danh sách script online: HTTP ${resp.code}")
                        return@withContext
                    }
                    val body = resp.body?.string() ?: return@withContext
                    
                    // Parse JSON thủ công để tránh lỗi ProGuard
                    val scripts = parseScriptsJson(body)
                    
                    withContext(Dispatchers.Main) {
                        for (script in scripts) {
                            val id = stableIdFromUrl(script.url)
                            
                            val existingIndex = scriptItems.indexOfFirst { it.id == id }
                            if (existingIndex == -1) {
                                scriptItems.add(
                                    ScriptItem(
                                        id = id,
                                        name = script.name,
                                        gameName = script.gameName,
                                        url = script.url
                                    )
                                )
                            }
                        }
                        log("Đã tải ${scripts.size} script từ nguồn online")
                    }
                }
            } catch (e: Exception) {
                logBg("Lỗi tải script online: ${e.message}")
            }
        }
    }
    
    private fun parseScriptsJson(json: String): List<PreloadScript> {
        val result = mutableListOf<PreloadScript>()
        try {
            val jsonArray = org.json.JSONArray(json)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                result.add(
                    PreloadScript(
                        name = obj.getString("name"),
                        gameName = obj.getString("gameName"),
                        url = obj.getString("url")
                    )
                )
            }
        } catch (e: Exception) {
            logBg("Lỗi parse scripts.json: ${e.message}")
        }
        return result
    }
    
    private suspend fun loadScriptsFromLocal() {
        withContext(Dispatchers.IO) {
            try {
                val autoExecuteDir = File("/storage/emulated/0/Delta/Autoexecute")
                val scriptsDir = File("/storage/emulated/0/Delta/Scripts")
                
                // Load auto-execute scripts
                if (autoExecuteDir.exists() && autoExecuteDir.isDirectory) {
                    autoExecuteDir.listFiles { file -> file.extension == "txt" }?.forEach { file ->
                        val id = "local_auto_${file.name}"
                        if (scriptItems.none { it.id == id }) {
                            scriptItems.add(
                                ScriptItem(
                                    id = id,
                                    name = file.nameWithoutExtension,
                                    gameName = "Local (Auto)",
                                    url = null,
                                    localPath = file.absolutePath
                                )
                            )
                        }
                    }
                }
                
                // Load manual scripts
                if (scriptsDir.exists() && scriptsDir.isDirectory) {
                    scriptsDir.listFiles { file -> file.extension == "txt" }?.forEach { file ->
                        val id = "local_manual_${file.name}"
                        if (scriptItems.none { it.id == id }) {
                            scriptItems.add(
                                ScriptItem(
                                    id = id,
                                    name = file.nameWithoutExtension,
                                    gameName = "Local (Manual)",
                                    url = null,
                                    localPath = file.absolutePath
                                )
                            )
                        }
                    }
                }
                
                withContext(Dispatchers.Main) {
                    log("Đã nạp script từ thư mục local")
                }
            } catch (e: Exception) {
                logBg("Lỗi tải script local: ${e.message}")
            }
        }
    }
    
    private fun applyScriptFilter(q: String) {
        currentScriptQuery = q
        val needle = q.trim().lowercase(Locale.getDefault())
        scriptFilteredItems.clear()
        if (needle.isEmpty()) {
            scriptFilteredItems.addAll(scriptItems)
        } else {
            scriptFilteredItems.addAll(
                scriptItems.filter {
                    it.name.lowercase(Locale.getDefault()).contains(needle)
                            || it.gameName.lowercase(Locale.getDefault()).contains(needle)
                }
            )
        }
        scriptAdapter.notifyDataSetChanged()
    }
    
    private fun getScriptFile(script: ScriptItem, folderName: String): File {
        return if (script.localPath != null) {
            File(script.localPath)
        } else {
            val dir = File("/storage/emulated/0/Delta/$folderName")
            dir.mkdirs()
            File(dir, "${script.name}.txt")
        }
    }

    private fun isSpecialScriptUrl(url: String): Boolean = url.contains("/source/hard/")

    private suspend fun fetchScriptBody(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android) Kasumi/1.0")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}")
            }
            resp.body?.string() ?: throw IllegalStateException("Empty response")
        }
    }

    private suspend fun resolveOnlineScriptContent(script: ScriptItem): String {
        val url = script.url ?: throw IllegalStateException("Script không có URL")
        return if (isSpecialScriptUrl(url)) {
            log("Tải script đặc biệt (full content): ${script.name}")
            fetchScriptBody(url)
        } else {
            log("Tạo script loadstring: ${script.name}")
            "loadstring(game:HttpGet(\"$url\"))()"
        }
    }

    private suspend fun readLocalScript(script: ScriptItem): String {
        val path = script.localPath ?: throw IllegalStateException("Script không tồn tại")
        return withContext(Dispatchers.IO) {
            val file = File(path)
            if (!file.exists()) {
                throw IllegalStateException("Script không tồn tại")
            }
            file.readText()
        }
    }

    private fun showDownloadFolderDialog(script: ScriptItem) {
        val options = arrayOf(
            "Auto-execute (Tự động chạy)",
            "Manual (Chạy thủ công)"
        )
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("${script.name} - Chọn thư mục")
            .setItems(options) { _, which ->
                val targetFolder = when (which) {
                    0 -> "Autoexecute"
                    else -> "Scripts"
                }
                downloadScript(script, targetFolder)
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun downloadScript(script: ScriptItem, targetFolder: String) {
        lifecycleScope.launch {
            try {
                if (script.url == null) {
                    toast("Script không có URL")
                    return@launch
                }

                setBusy(true)
                log("Đang tải script: ${script.name}")

                val scriptContent = resolveOnlineScriptContent(script)

                val scriptFile = getScriptFile(script, targetFolder)
                scriptFile.parentFile?.mkdirs()

                withContext(Dispatchers.IO) {
                    scriptFile.writeText(scriptContent)
                }
                
                log("✓ Đã lưu script vào: /Delta/$targetFolder/${script.name}.txt")
                toast("Đã lưu script vào $targetFolder")
                applyScriptFilter(currentScriptQuery)
                
            } catch (e: Exception) {
                toast("Lỗi tải script: ${e.message}")
                log("Lỗi tải script: ${e.message}")
            } finally {
                setBusy(false)
            }
        }
    }

    private fun copyScript(script: ScriptItem) {
        lifecycleScope.launch {
            var showingProgress = false
            try {
                val content = when {
                    script.localPath != null -> readLocalScript(script)
                    script.url != null -> {
                        showingProgress = true
                        setBusy(true)
                        resolveOnlineScriptContent(script)
                    }
                    else -> throw IllegalStateException("Script không có dữ liệu")
                }

                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(script.name, content)
                clipboard.setPrimaryClip(clip)

                toast("Đã sao chép script")
                log("Đã sao chép script: ${script.name}")
            } catch (e: Exception) {
                toast("Lỗi copy script: ${e.message}")
                log("Lỗi copy script: ${e.message}")
            } finally {
                if (showingProgress) {
                    setBusy(false)
                }
            }
        }
    }
    
    private fun deleteScript(script: ScriptItem) {
        try {
            // Check and delete from both folders
            val autoFile = getScriptFile(script, "Autoexecute")
            val manualFile = getScriptFile(script, "Scripts")
            
            var deleted = false
            if (autoFile.exists()) {
                autoFile.delete()
                log("Đã xóa script từ Autoexecute: ${script.name}")
                deleted = true
            }
            if (manualFile.exists()) {
                manualFile.delete()
                log("Đã xóa script từ Scripts: ${script.name}")
                deleted = true
            }
            
            if (deleted) {
                toast("Đã xóa script")
                applyScriptFilter(currentScriptQuery)
            } else {
                toast("Script không tồn tại")
            }
        } catch (e: Exception) {
            toast("Lỗi xóa script: ${e.message}")
            log("Lỗi xóa script: ${e.message}")
        }
    }
}

// Data & Adapter

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
        fun toJsonList(list: List<ApkItem>): String {
            // Simple manual JSON to avoid adding extra dependency
            val sb = StringBuilder()
            sb.append('[')
            list.forEachIndexed { i, it ->
                if (i > 0) sb.append(',')
                sb.append('{')
                sb.append("\"id\":\"${it.id}\",")
                sb.append("\"name\":\"${escape(it.name)}\",")
                sb.append("\"sourceType\":\"${it.sourceType}\",")
                sb.append("\"url\":${if (it.url != null) "\"${escape(it.url)}\"" else "null"},")
                sb.append("\"uri\":${if (it.uri != null) "\"${escape(it.uri)}\"" else "null"},")
                sb.append("\"versionName\":${if (it.versionName != null) "\"${escape(it.versionName)}\"" else "null"},")
                sb.append("\"versionCode\":${it.versionCode?.toString() ?: "null"},")
                sb.append("\"iconUrl\":${if (it.iconUrl != null) "\"${escape(it.iconUrl)}\"" else "null"}")
                sb.append('}')
            }
            sb.append(']')
            return sb.toString()
        }

        fun fromJsonList(json: String?): List<ApkItem> {
            if (json.isNullOrBlank()) return emptyList()
            // Very small and naive JSON parser for our fixed schema
            val list = mutableListOf<ApkItem>()
            val items = json.trim().removePrefix("[").removeSuffix("]")
            if (items.isBlank()) return emptyList()
            val parts = splitTopLevel(items)
            for (p in parts) {
                val map = parseObject(p)
                val id = map["id"] ?: UUID.randomUUID().toString()
                val name = map["name"] ?: "APK"
                val sourceType = try { SourceType.valueOf(map["sourceType"] ?: "URL") } catch (_: Exception) { SourceType.URL }
                val url = map["url"]
                val uri = map["uri"]
                val versionName = map["versionName"]
                val versionCode = map["versionCode"]?.toLongOrNull()
                val iconUrl = map["iconUrl"]
                list.add(ApkItem(id, name, sourceType, url, uri, versionName, versionCode, iconUrl))
            }
            return list
        }

        private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

        private fun splitTopLevel(s: String): List<String> {
            val res = mutableListOf<String>()
            var level = 0
            var start = 0
            for (i in s.indices) {
                when (s[i]) {
                    '{' -> if (level++ == 0) start = i
                    '}' -> if (--level == 0) res.add(s.substring(start, i + 1))
                }
            }
            return res
        }

        private fun parseObject(s: String): Map<String, String?> {
            val map = mutableMapOf<String, String?>()
            // remove { }
            var body = s.trim().removePrefix("{").removeSuffix("}")
            // split by commas not inside quotes
            val parts = mutableListOf<String>()
            val sb = StringBuilder()
            var inStr = false
            for (ch in body) {
                if (ch == '"') inStr = !inStr
                if (ch == ',' && !inStr) { parts.add(sb.toString()); sb.clear() } else sb.append(ch)
            }
            if (sb.isNotEmpty()) parts.add(sb.toString())
            for (p in parts) {
                val idx = p.indexOf(":")
                if (idx > 0) {
                    val key = p.substring(0, idx).trim().removeSurrounding("\"", "\"")
                    var valueStr = p.substring(idx + 1).trim()
                    var value: String? = if (valueStr == "null") null else valueStr.removeSurrounding("\"", "\"")
                    if (value != null) value = value.replace("\\\"", "\"").replace("\\\\", "\\")
                    map[key] = value
                }
            }
            return map
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

// RootInstaller đã được tách sang file riêng: RootInstaller.kt
