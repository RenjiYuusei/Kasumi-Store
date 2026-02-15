package com.kasumi.tool

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object FileStatsHelper {
    suspend fun updateFileStats(
        appsList: List<ApkItem>,
        currentStats: MutableMap<String, FileStats>,
        cacheDir: File
    ) {
        withContext(Dispatchers.IO) {
            val currentIds = appsList.map { it.id }.toSet()

            // Remove stale entries
            val iterator = currentStats.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key !in currentIds) {
                    iterator.remove()
                }
            }

            // Add missing entries
            for (item in appsList) {
                if (!currentStats.containsKey(item.id)) {
                    val file = FileUtils.getCacheFile(item, cacheDir)
                    if (file.exists()) {
                        currentStats[item.id] = FileStats(true, file.length(), file.lastModified())
                    } else {
                        currentStats[item.id] = FileStats(false, 0L, 0L)
                    }
                }
            }
        }
    }

    suspend fun updateItemFileStats(
        item: ApkItem,
        currentStats: MutableMap<String, FileStats>,
        cacheDir: File
    ) {
        withContext(Dispatchers.IO) {
            val file = FileUtils.getCacheFile(item, cacheDir)
            if (file.exists()) {
                currentStats[item.id] = FileStats(true, file.length(), file.lastModified())
            } else {
                currentStats[item.id] = FileStats(false, 0L, 0L)
            }
        }
    }

    // For refreshing all (e.g. clear cache)
    suspend fun refreshAll(
        appsList: List<ApkItem>,
        currentStats: MutableMap<String, FileStats>,
        cacheDir: File
    ) {
        withContext(Dispatchers.IO) {
            currentStats.clear()
            for (item in appsList) {
                val file = FileUtils.getCacheFile(item, cacheDir)
                if (file.exists()) {
                    currentStats[item.id] = FileStats(true, file.length(), file.lastModified())
                } else {
                    currentStats[item.id] = FileStats(false, 0L, 0L)
                }
            }
        }
    }
}
