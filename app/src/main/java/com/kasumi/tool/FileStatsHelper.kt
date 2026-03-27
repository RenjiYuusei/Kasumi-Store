package com.kasumi.tool

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.os.Build
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

object FileStatsHelper {

    suspend fun updateFileStats(
        appsList: List<ApkItem>,
        currentStats: MutableMap<String, FileStats>,
        cacheDir: File
    ) {
        val neededIds = appsList.map { it.id }.toSet()
        val currentKeys = currentStats.keys.toSet()

        // Items in appsList but not in currentStats need to be computed
        val newItems = appsList.filter { it.id !in currentKeys }

        // Compute stats for new items on IO thread
        val newStats = withContext(Dispatchers.IO) {
            val apksDir = FileUtils.getApkCacheDir(cacheDir)
            val existingFiles = apksDir.listFiles()?.associateBy { it.name } ?: emptyMap()

            newItems.associate { item ->
                val file = FileUtils.getCacheFile(item, cacheDir)
                val listedFile = existingFiles[file.name]
                if (listedFile != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val attrs = Files.readAttributes(listedFile.toPath(), BasicFileAttributes::class.java)
                        item.id to FileStats(true, attrs.size(), attrs.lastModifiedTime().toMillis())
                    } else {
                        item.id to FileStats(true, listedFile.length(), listedFile.lastModified())
                    }
                } else {
                    item.id to FileStats(false, 0L, 0L)
                }
            }
        }

        // Apply updates on Main thread
        withContext(Dispatchers.Main.immediate) {
            // Remove stale entries
            val iterator = currentStats.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key !in neededIds) {
                    iterator.remove()
                }
            }
            // Add new computed entries
            currentStats.putAll(newStats)
        }
    }

    suspend fun updateItemFileStats(
        item: ApkItem,
        currentStats: MutableMap<String, FileStats>,
        cacheDir: File
    ) {
        // Compute on IO thread
        val stats = withContext(Dispatchers.IO) {
            val file = FileUtils.getCacheFile(item, cacheDir)
            if (file.exists()) {
                FileStats(true, file.length(), file.lastModified())
            } else {
                FileStats(false, 0L, 0L)
            }
        }

        // Update state on Main thread
        withContext(Dispatchers.Main.immediate) {
            currentStats[item.id] = stats
        }
    }

    suspend fun refreshAll(
        appsList: List<ApkItem>,
        currentStats: MutableMap<String, FileStats>,
        cacheDir: File
    ) {
        // Compute ALL stats on IO thread
        val allStats = withContext(Dispatchers.IO) {
            val apksDir = FileUtils.getApkCacheDir(cacheDir)
            val existingFiles = apksDir.listFiles()?.associateBy { it.name } ?: emptyMap()

            appsList.associate { item ->
                val file = FileUtils.getCacheFile(item, cacheDir)
                val listedFile = existingFiles[file.name]
                if (listedFile != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val attrs = Files.readAttributes(listedFile.toPath(), BasicFileAttributes::class.java)
                        item.id to FileStats(true, attrs.size(), attrs.lastModifiedTime().toMillis())
                    } else {
                        item.id to FileStats(true, listedFile.length(), listedFile.lastModified())
                    }
                } else {
                    item.id to FileStats(false, 0L, 0L)
                }
            }
        }

        // Clear and update on Main thread
        withContext(Dispatchers.Main.immediate) {
            currentStats.clear()
            currentStats.putAll(allStats)
        }
    }
}
