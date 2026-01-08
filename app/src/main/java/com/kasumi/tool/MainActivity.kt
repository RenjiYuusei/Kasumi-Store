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
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
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
import java.util.*
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

    // State for Compose
    private val items = mutableStateListOf<ApkItem>()
    private val scriptItems = mutableStateListOf<ScriptItem>()
    private val loadingIds = mutableSetOf<String>()
    
    // Simple way to trigger recomposition for loading state, though better to use a map in state
    private fun isLoading(id: String): Boolean = loadingIds.contains(id)

    private var isBusy: Boolean by androidx.compose.runtime.mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            KasumiTheme {
                MainScreen(
                    appVersion = resolveAppVersionName(),
                    items = items,
                    scriptItems = scriptItems,
                    isLoading = { id -> isLoading(id) },
                    onRefresh = {
                        lifecycleScope.launch {
                            isBusy = true
                            refreshPreloadedApps()
                            isBusy = false
                            toast("Đã làm mới nguồn")
                        }
                    },
                    onSort = { showSortMenu() }, // Re-implement sort later or move logic
                    onInstall = { item -> onInstallClicked(item) },
                    onDeleteApp = { item ->
                        items.removeAll { it.id == item.id }
                        saveItems()
                        toast("Đã xóa mục: ${item.name}")
                    },
                    onDownloadScript = { script -> showDownloadFolderDialog(script) },
                    onCopyScript = { script -> copyScript(script) },
                    onDeleteScript = { script -> deleteScript(script) },
                    isBusy = isBusy
                )
            }
        }

        loadItems()

        lifecycleScope.launch {
            refreshPreloadedApps(initial = true)
        }
        updateCachedVersions()
        
        lifecycleScope.launch {
            loadScriptsFromOnline()
            loadScriptsFromLocal()
        }
        
        requestStoragePermission()
    }
    
    private fun showSortMenu() {
        // Simple sort implementation for now
        val sorted = items.sortedBy { it.name.lowercase() }
        items.clear()
        items.addAll(sorted)
        toast("Đã sắp xếp theo tên (A-Z)")
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

    private fun logBg(msg: String) {
        // Simplified logging since we removed the log tab
        println(msg)
    }

    private fun log(msg: String) {
        println(msg)
    }

    private fun mergePreloaded(preloaded: List<PreloadApp>) {
        // preloadedIds.clear() // No longer tracking IDs separately needed for adapter
        var updated = 0
        for (p in preloaded) {
            val id = stableIdFromUrl(p.url)
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
                val arr: Array<PreloadApp> = Gson().fromJson(body, Array<PreloadApp>::class.java)
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
        } else {
            if (!initial) logBg("Không thể nạp danh sách preload từ nguồn mặc định")
        }
    }

    @Suppress("DEPRECATION")
    private fun extractApkVersion(file: File): Pair<String?, Long?> {
        return try {
            val pm = packageManager
            val pi = pm.getPackageArchiveInfo(file.absolutePath, 0)
            if (pi != null) {
                val name = pi.versionName
                val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
                name to code
            } else null to null
        } catch (_: Exception) {
            null to null
        }
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

    private fun onInstallClicked(item: ApkItem) {
        lifecycleScope.launch {
            loadingIds.add(item.id)
            isBusy = true

            try {
                val apkFile = when (item.sourceType) {
                    SourceType.LOCAL -> copyFromUriIfNeeded(Uri.parse(item.uri!!))
                    SourceType.URL -> downloadApk(item)
                }
                if (apkFile == null) {
                    toast("Không thể chuẩn bị tệp APK")
                    return@launch
                }

                val urlLower = item.url?.lowercase(Locale.ROOT)
                val fileNameLower = apkFile.name.lowercase(Locale.ROOT)
                val isSplitPackage = (urlLower?.contains(".apks") == true || fileNameLower.endsWith(".apks"))
                        || (urlLower?.contains(".xapk") == true || fileNameLower.endsWith(".xapk"))
                if (isSplitPackage) {
                    val (splits, obbInfo) = withContext(Dispatchers.IO) { extractSplitsAndObb(apkFile) }
                    if (splits.isEmpty()) {
                        toast("Không tìm thấy APK bên trong file")
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
                            toast("Cài đặt thành công")
                        } else {
                            installSplitsNormally(splits)
                        }
                    } else {
                        installSplitsNormally(splits)
                    }
                    return@launch
                }

                if (!isLikelyApk(apkFile)) {
                    toast("Tệp tải về không phải APK. Kiểm tra lại link tải.")
                    return@launch
                }

                val rooted = RootInstaller.isDeviceRooted()
                if (rooted) {
                    val resApk: Pair<Boolean, String> = withContext(Dispatchers.IO) { RootInstaller.installApk(apkFile) }
                    val (ok, msg) = resApk
                    if (ok) {
                        toast("Cài đặt thành công")
                    } else {
                        installNormally(apkFile)
                    }
                } else {
                    installNormally(apkFile)
                }
            } catch (e: Exception) {
                toast("Lỗi: ${e.message}")
            } finally {
                loadingIds.remove(item.id)
                if (loadingIds.isEmpty()) isBusy = false
            }
        }
    }

    private fun installNormally(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
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
            outFile
        }
    }

    private fun ensureApkExtension(name: String): String {
        val lower = name.lowercase(Locale.ROOT)
        return if (lower.endsWith(".apk") || lower.endsWith(".apks") || lower.endsWith(".xapk")) name else "$name.apk"
    }

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
        
        // Sắp xếp APK
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!Environment.isExternalStorageManager()) {
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
            }
        } catch (e: Exception) {
            logBg("Lỗi copy OBB: ${e.message}")
        }
    }

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
                        } else if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                            val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                                intent?.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                            } else {
                                @Suppress("DEPRECATION") intent?.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                            }
                            try { startActivity(confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                        } else {
                            toast("Cài đặt thất bại: $msg")
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

    // Khu vực quản lý Scripts
    private suspend fun loadScriptsFromOnline() {
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder().url(DEFAULT_SCRIPTS_URL).header("User-Agent", "CloudPhoneTool/1.0").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext
                    val body = resp.body?.string() ?: return@withContext
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
                    }
                }
            } catch (e: Exception) {
                // ignore
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
            // ignore
        }
        return result
    }
    
    private suspend fun loadScriptsFromLocal() {
        withContext(Dispatchers.IO) {
            try {
                val autoExecuteDir = File("/storage/emulated/0/Delta/Autoexecute")
                val scriptsDir = File("/storage/emulated/0/Delta/Scripts")
                
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
            } catch (e: Exception) {
                // ignore
            }
        }
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
            fetchScriptBody(url)
        } else {
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

                isBusy = true
                val scriptContent = resolveOnlineScriptContent(script)

                val scriptFile = getScriptFile(script, targetFolder)
                scriptFile.parentFile?.mkdirs()

                withContext(Dispatchers.IO) {
                    scriptFile.writeText(scriptContent)
                }
                
                toast("Đã lưu script vào $targetFolder")
                
            } catch (e: Exception) {
                toast("Lỗi tải script: ${e.message}")
            } finally {
                isBusy = false
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
                        isBusy = true
                        resolveOnlineScriptContent(script)
                    }
                    else -> throw IllegalStateException("Script không có dữ liệu")
                }

                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(script.name, content)
                clipboard.setPrimaryClip(clip)

                toast("Đã sao chép script")
            } catch (e: Exception) {
                toast("Lỗi copy script: ${e.message}")
            } finally {
                if (showingProgress) {
                    isBusy = false
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
                deleted = true
            }
            if (manualFile.exists()) {
                manualFile.delete()
                deleted = true
            }
            
            if (deleted) {
                toast("Đã xóa script")
                // Need to remove from list? The list is from memory/online merged with local.
                // Re-loading from local might be needed or just removing from list if it was a local item.
                // If it's an online item, we just deleted the local copy (if any).
                // Re-scanning local scripts logic is separate.
            } else {
                toast("Script không tồn tại")
            }
        } catch (e: Exception) {
            toast("Lỗi xóa script: ${e.message}")
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
