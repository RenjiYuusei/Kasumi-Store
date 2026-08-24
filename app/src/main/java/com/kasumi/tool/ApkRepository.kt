package com.kasumi.tool

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.edit
import androidx.core.util.AtomicFile
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileReader
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Everything that reads or writes APK data: the saved catalogue, the remote
 * source list, the download cache, and split/OBB extraction.
 *
 * Holds no UI state and no Activity reference, so it survives configuration
 * changes and can be driven from [AppsViewModel] or exercised directly.
 */
class ApkRepository(
    private val context: Context,
    private val client: OkHttpClient,
    private val gson: Gson = Gson(),
) {

    data class ObbInfo(val packageName: String, val obbFiles: List<File>)

    /** Result of unpacking an .apks/.xapk/.apkm container. */
    data class SplitPackage(val apks: List<File>, val obb: ObbInfo?)

    private val saveMutex = Mutex()

    private val itemsFile: File get() = File(context.filesDir, "items.json")
    private val cacheRoot: File get() = context.cacheDir

    // --- Catalogue persistence ------------------------------------------------

    suspend fun loadItems(): List<ApkItem> = withContext(Dispatchers.IO) {
        val file = itemsFile
        if (file.exists()) {
            try {
                BufferedReader(FileReader(file)).use { ApkItem.readListFrom(it) }
            } catch (e: Exception) {
                Log.w(TAG, "items.json unreadable, starting empty", e)
                emptyList()
            }
        } else {
            migrateFromPreferences()
        }
    }

    /** One-time migration of the pre-2.0 SharedPreferences catalogue. */
    private suspend fun migrateFromPreferences(): List<ApkItem> {
        val prefs = context.getSharedPreferences("apk_items", Context.MODE_PRIVATE)
        val list = ApkItem.fromJsonList(prefs.getString("list", null))
        if (list.isNotEmpty() && writeItems(list)) {
            prefs.edit { remove("list") }
        }
        return list
    }

    suspend fun saveItems(items: List<ApkItem>) {
        withContext(Dispatchers.IO) { writeItems(items) }
    }

    private suspend fun writeItems(items: List<ApkItem>): Boolean = saveMutex.withLock {
        val atomicFile = AtomicFile(itemsFile)
        var fos: FileOutputStream? = null
        try {
            fos = atomicFile.startWrite()
            val writer = BufferedWriter(OutputStreamWriter(fos))
            ApkItem.writeListTo(items, writer)
            writer.flush()
            atomicFile.finishWrite(fos)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist items.json", e)
            fos?.let { atomicFile.failWrite(it) }
            false
        }
    }

    // --- Remote source --------------------------------------------------------

    private suspend fun fetchRemoteApps(url: String): List<PreloadApp>? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body.string()
                gson.fromJson(body, Array<PreloadApp>::class.java)?.toList()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Remote source fetch failed", e)
            null
        }
    }

    /** Returns the refreshed catalogue, or null when the source is unreachable. */
    suspend fun refreshFromRemote(): List<ApkItem>? {
        val preloaded = fetchRemoteApps(DEFAULT_SOURCE_URL) ?: return null
        val items = preloaded.map { p ->
            ApkItem(
                id = FileUtils.stableIdFromUrl(p.url),
                name = p.name,
                sourceType = SourceType.URL,
                url = normalizeDownloadUrl(p.url),
                uri = null,
                versionName = p.versionName,
                versionCode = p.versionCode,
                iconUrl = p.iconUrl,
            )
        }
        saveItems(items)
        return items
    }

    // --- Download / cache -----------------------------------------------------

    fun cachedFileFor(item: ApkItem): File = FileUtils.getCacheFile(item, cacheRoot)

    /**
     * Downloads [item] into the APK cache.
     *
     * The body is streamed into a sibling `.part` file and only renamed onto the
     * final path once [verifyDownloadedPackage] accepts it. Without this, a
     * download interrupted by a dropped connection or by the user leaving left a
     * truncated file behind that later looked cached and was handed straight to
     * the installer.
     */
    suspend fun downloadApk(item: ApkItem): File = withContext(Dispatchers.IO) {
        val url = item.url ?: throw IOException("Item has no download URL")
        val outFile = cachedFileFor(item)
        outFile.parentFile?.mkdirs()
        val partFile = File(outFile.parentFile, outFile.name + ".part")
        partFile.delete()

        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP " + resp.code)
                val body = resp.body
                val expected = body.contentLength()

                val usable = outFile.parentFile?.usableSpace ?: Long.MAX_VALUE
                if (expected > 0 && usable < expected + FREE_SPACE_MARGIN) {
                    throw IOException("Not enough free space for " + (expected / (1024 * 1024)) + " MB")
                }

                body.byteStream().use { input ->
                    FileOutputStream(partFile).use { out -> input.copyTo(out, COPY_BUFFER) }
                }
                verifyDownloadedPackage(partFile, expected)
            }
            outFile.delete()
            if (!partFile.renameTo(outFile)) {
                throw IOException("Could not move the finished download into the cache")
            }
            outFile
        } catch (e: Throwable) {
            // Never leave a partial file behind that a later run would treat as cached.
            partFile.delete()
            throw e
        }
    }

    suspend fun copyFromUri(uri: Uri): File? = withContext(Dispatchers.IO) {
        val dir = FileUtils.getApkCacheDir(cacheRoot).apply { mkdirs() }
        val outFile = File(dir, "picked_" + System.currentTimeMillis() + ".apk")
        try {
            context.contentResolver.openInputStream(uri).use { source ->
                if (source == null) return@withContext null
                FileOutputStream(outFile).use { out -> source.copyTo(out, COPY_BUFFER) }
            }
            outFile
        } catch (e: CancellationException) {
            outFile.delete()
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not copy picked file", e)
            outFile.delete()
            null
        }
    }

    /** Deletes every cached APK, split and OBB. Returns files removed to bytes freed. */
    suspend fun clearCache(): Pair<Int, Long> = withContext(Dispatchers.IO) {
        var count = 0
        var size = 0L
        val apkCacheDir = FileUtils.getApkCacheDir(cacheRoot)
        if (apkCacheDir.exists()) {
            apkCacheDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    val len = file.length()
                    if (file.delete()) {
                        count++
                        size += len
                    }
                }
            }
        }
        File(cacheRoot, "splits").takeIf { it.exists() }?.deleteRecursively()
        File(cacheRoot, "obb").takeIf { it.exists() }?.deleteRecursively()
        count to size
    }

    // --- Split / OBB packages -------------------------------------------------

    /**
     * Unpacks the APKs and OBBs inside an .apks/.xapk/.apkm container.
     *
     * Entry names are reduced to their basename before being joined onto the
     * output directory, so a crafted archive cannot escape it.
     */
    fun extractSplitsAndObb(packageFile: File): SplitPackage {
        val outDir = File(cacheRoot, "splits/" + packageFile.nameWithoutExtension)
        if (outDir.exists()) outDir.deleteRecursively()
        outDir.mkdirs()

        val apks = mutableListOf<File>()
        val obbFiles = mutableListOf<File>()
        var packageName: String? = null

        try {
            ZipFile(packageFile).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    val lowerName = entry.name.lowercase()
                    val fileName = entry.name.substringAfterLast('/')
                    if (fileName.isEmpty() || fileName == "." || fileName == "..") continue

                    when {
                        lowerName.endsWith("manifest.json") -> {
                            packageName = readPackageName(zip, entry) ?: packageName
                        }

                        lowerName.endsWith(".apk") -> {
                            extractApkEntry(zip, entry, File(outDir, fileName))?.let { apks.add(it) }
                        }

                        lowerName.endsWith(".obb") -> {
                            val obbDir = File(cacheRoot, "obb").apply { mkdirs() }
                            val outFile = File(obbDir, fileName)
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(outFile).use { out -> input.copyTo(out, COPY_BUFFER) }
                            }
                            if (outFile.length() > 0) obbFiles.add(outFile)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read package " + packageFile.name, e)
        }

        val sorted = apks
            .map {
                SortKey(
                    file = it,
                    baseLast = !it.name.startsWith("base.") && !it.name.contains("com."),
                    configLast = it.name.startsWith("config.") || it.name.startsWith("split_"),
                    name = it.name,
                )
            }
            .sortedWith(compareBy({ it.baseLast }, { it.configLast }, { it.name }))
            .map { it.file }

        val pkg = packageName
        val obb = if (obbFiles.isNotEmpty() && !pkg.isNullOrBlank()) ObbInfo(pkg, obbFiles) else null
        return SplitPackage(sorted, obb)
    }

    private fun readPackageName(zip: ZipFile, entry: ZipEntry): String? = try {
        val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
        JSONObject(text).optString("package_name").takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.w(TAG, "Unparsable manifest.json in package", e)
        null
    }

    /** Writes one APK entry out, rejecting entries that are not really ZIP/APK data. */
    private fun extractApkEntry(zip: ZipFile, entry: ZipEntry, outFile: File): File? {
        return try {
            zip.getInputStream(entry).use { input ->
                val magic = ByteArray(2)
                if (input.read(magic) != 2 || magic[0] != 0x50.toByte() || magic[1] != 0x4B.toByte()) {
                    Log.e(TAG, "Skipping encrypted or invalid APK entry " + entry.name)
                    return null
                }
                outFile.parentFile?.mkdirs()
                outFile.outputStream().buffered(COPY_BUFFER).use { out ->
                    out.write(magic, 0, 2)
                    input.copyTo(out, COPY_BUFFER)
                }
            }
            outFile.takeIf { it.length() > 0 }
        } catch (e: Exception) {
            outFile.delete()
            Log.e(TAG, "Failed to extract " + entry.name, e)
            null
        }
    }

    /** Copies extracted OBBs into /Android/obb/<package>/. Requires all-files access. */
    suspend fun installObbFiles(obbInfo: ObbInfo): Unit = coroutineScope {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            return@coroutineScope
        }
        @Suppress("DEPRECATION")
        val obbDir = File(Environment.getExternalStorageDirectory(), "Android/obb/" + obbInfo.packageName)
        if (!obbDir.exists()) obbDir.mkdirs()

        val semaphore = Semaphore(2)
        obbInfo.obbFiles.map { obbFile ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val destFile = File(obbDir, obbFile.name)
                    try {
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
                    } catch (e: CancellationException) {
                        destFile.delete()
                        throw e
                    } catch (e: Exception) {
                        destFile.delete()
                        Log.e(TAG, "Failed to copy OBB " + obbFile.name, e)
                    }
                }
            }
        }.awaitAll()
    }

    private data class SortKey(
        val file: File,
        val baseLast: Boolean,
        val configLast: Boolean,
        val name: String,
    )

    companion object {
        private const val TAG = "ApkRepository"
        private const val USER_AGENT = "Mozilla/5.0 (Android) Kasumi/2.0"
        private const val COPY_BUFFER = 128 * 1024
        private const val FREE_SPACE_MARGIN = 16L * 1024 * 1024

        const val DEFAULT_SOURCE_URL =
            "https://raw.githubusercontent.com/RenjiYuusei/Kasumi-Store/main/source/apps.json"
    }
}

/**
 * Accepts a finished download only if it can actually be an APK container.
 *
 * Comparing against `Content-Length` is not enough on its own:
 *
 *  - a server may answer without a length at all (HTTP/1.0 style, delimited by
 *    closing the connection), and then a transfer cut short mid-way reaches us
 *    as a clean end-of-stream with nothing to compare against;
 *  - `Content-Length: 0` used to slip past the old `expected > 0` guard, so an
 *    empty file was renamed into the cache and later handed to the installer;
 *  - a mirror that has expired the link often answers 200 with an HTML notice,
 *    which is a perfectly complete response and simply is not an APK.
 *
 * Every format this app installs (.apk, .apks, .apkm, .xapk) is a ZIP, so the
 * two-byte `PK` signature is a cheap check that rules all three out — the same
 * check [ApkRepository.extractApkEntry] already applies to entries inside a
 * container.
 *
 * @param expectedLength `Content-Length`, or a negative value when unknown.
 * @throws IOException when the file cannot be a usable package.
 */
internal fun verifyDownloadedPackage(file: File, expectedLength: Long) {
    val actual = file.length()
    if (actual == 0L) {
        throw IOException("Download is empty")
    }
    if (expectedLength >= 0 && actual != expectedLength) {
        throw IOException("Truncated download: $actual of $expectedLength bytes")
    }
    val magic = ByteArray(2)
    val read = FileInputStream(file).use { it.read(magic) }
    if (read < 2 || magic[0] != 0x50.toByte() || magic[1] != 0x4B.toByte()) {
        throw IOException("Downloaded file is not an APK package")
    }
}
