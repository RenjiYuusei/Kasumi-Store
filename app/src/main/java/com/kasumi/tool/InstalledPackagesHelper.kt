package com.kasumi.tool

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Cached package information for an installed app. */
data class InstalledInfo(val packageName: String, val versionName: String?)

/**
 * Helpers for resolving the package name of a cached APK/XAPK file and tracking
 * which apps are currently installed on the device.
 */
object InstalledPackagesHelper {

    /** Extract the package name from an APK file or split package archive (.xapk/.apks/.apkm). */
    fun extractPackageName(context: Context, file: File): String? {
        if (!file.exists() || file.length() == 0L) return null
        val nameLower = file.name.lowercase(Locale.ROOT)
        return if (nameLower.endsWith(".apks") || nameLower.endsWith(".xapk") || nameLower.endsWith(".apkm")) {
            packageNameFromManifestJson(file)
        } else {
            runCatching {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.packageName
            }.getOrNull()
        }
    }

    private fun packageNameFromManifestJson(file: File): String? = runCatching {
        java.util.zip.ZipFile(file).use { zip ->
            val entry = zip.entries().asSequence().firstOrNull {
                it.name.lowercase(Locale.ROOT).endsWith("manifest.json")
            } ?: return@use null
            val manifest = zip.getInputStream(entry).bufferedReader().readText()
            JSONObject(manifest).optString("package_name").takeIf { it.isNotBlank() }
        }
    }.getOrNull()

    /** Query the [PackageManager] for an installed app, returning null if it's not installed. */
    fun getInstalledInfo(context: Context, packageName: String): InstalledInfo? {
        return try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(packageName, 0)
            InstalledInfo(packageName, info.versionName)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    /**
     * Refresh the installed-apps state for [items]:
     * 1. Resolve package names for any item whose APK is cached but not yet known.
     * 2. Re-query [PackageManager] for every known package and update [installedInfo].
     */
    suspend fun refresh(
        context: Context,
        items: List<ApkItem>,
        cacheDir: File,
        packageNames: MutableMap<String, String>,
        installedInfo: MutableMap<String, InstalledInfo>,
    ) = coroutineScope {
        val toExtract = items.filter { it.id !in packageNames }
        val extracted = withContext(Dispatchers.IO) {
            toExtract.map { item ->
                async {
                    val file = FileUtils.getCacheFile(item, cacheDir)
                    extractPackageName(context, file)?.let { item.id to it }
                }
            }.awaitAll().filterNotNull()
        }

        val knownPackages = (packageNames.values + extracted.map { it.second }).toSet()
        val refreshed = withContext(Dispatchers.IO) {
            knownPackages.map { pkg ->
                async { pkg to getInstalledInfo(context, pkg) }
            }.awaitAll()
        }

        withContext(Dispatchers.Main.immediate) {
            for ((id, pkg) in extracted) packageNames[id] = pkg
            installedInfo.clear()
            for ((pkg, info) in refreshed) {
                if (info != null) installedInfo[pkg] = info
            }
        }
    }
}
