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
        val neededIds = appsList.map { it.id }.toSet()
        val currentKeys = currentStats.keys.toSet()

        // Items in appsList but not in currentStats need to be computed
        val newItems = appsList.filter { it.id !in currentKeys }

        // Compute stats for new items on IO thread
        val newStats = withContext(Dispatchers.IO) {
            newItems.associate { item ->
                val file = FileUtils.getCacheFile(item, cacheDir)
                if (file.exists()) {
                    item.id to FileStats(true, file.length(), file.lastModified())
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
            appsList.associate { item ->
                val file = FileUtils.getCacheFile(item, cacheDir)
                if (file.exists()) {
                    item.id to FileStats(true, file.length(), file.lastModified())
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
