package com.kasumi.tool

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import android.os.Build
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

object FileStatsHelper {
    private fun getFileStatsFromListing(
        item: ApkItem,
        existingFiles: Map<String, File>,
        cacheDir: File
    ): Pair<String, FileStats> {
        val file = FileUtils.getCacheFile(item, cacheDir)
        val listedFile = existingFiles[file.name] ?: return item.id to FileStats(false, 0L, 0L)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = Files.readAttributes(listedFile.toPath(), BasicFileAttributes::class.java)
                item.id to FileStats(true, attrs.size(), attrs.lastModifiedTime().toMillis())
            } else {
                val lastMod = listedFile.lastModified()
                if (lastMod == 0L) {
                    item.id to FileStats(false, 0L, 0L)
                } else {
                    item.id to FileStats(true, listedFile.length(), lastMod)
                }
            }
        } catch (e: Exception) {
            item.id to FileStats(false, 0L, 0L)
        }
    }

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

            newItems.map { item ->
                async { getFileStatsFromListing(item, existingFiles, cacheDir) }
            }.awaitAll().toMap()
        }

        // Apply updates on Main thread
        withContext(Dispatchers.Main.immediate) {
            // Remove stale entries
            currentStats.keys.retainAll(neededIds)

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
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
                    FileStats(true, attrs.size(), attrs.lastModifiedTime().toMillis())
                } else {
                    val lastMod = file.lastModified()
                    if (lastMod == 0L) {
                        FileStats(false, 0L, 0L)
                    } else {
                        FileStats(true, file.length(), lastMod)
                    }
                }
            } catch (e: Exception) {
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

            appsList.map { item ->
                async { getFileStatsFromListing(item, existingFiles, cacheDir) }
            }.awaitAll().toMap()
        }

        // Clear and update on Main thread
        withContext(Dispatchers.Main.immediate) {
            currentStats.clear()
            currentStats.putAll(allStats)
        }
    }
}
