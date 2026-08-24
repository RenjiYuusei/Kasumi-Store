package com.kasumi.tool

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/**
 * Reads cache metadata (present / size / mtime) for catalogue entries.
 *
 * Returns plain immutable maps instead of mutating shared state from several
 * dispatchers, which is what the previous version did — that required a manual
 * `statsVersion++` counter in the UI to notice the mutation and raced whenever a
 * download finished while a refresh was in flight.
 */
object FileStatsHelper {

    private val MISSING = FileStats(exists = false, size = 0L, lastModified = 0L)

    /**
     * Stats for one entry, resolved against an already-listed directory.
     *
     * Pure apart from the [File] reads, and independent of Android APIs on the
     * hot path, so it is directly unit-testable.
     */
    fun statsFor(item: ApkItem, existingFiles: Map<String, File>, cacheDir: File): FileStats {
        val expected = FileUtils.getCacheFile(item, cacheDir)
        val listed = existingFiles[expected.name] ?: return MISSING
        return readStats(listed)
    }

    private fun readStats(file: File): FileStats = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            FileStats(true, attrs.size(), attrs.lastModifiedTime().toMillis())
        } else {
            val lastMod = file.lastModified()
            if (lastMod == 0L) MISSING else FileStats(true, file.length(), lastMod)
        }
    } catch (e: Exception) {
        MISSING
    }

    /**
     * Stats for the whole catalogue from a single directory listing, rather than
     * one stat syscall per item.
     */
    suspend fun computeAll(apps: List<ApkItem>, cacheDir: File): Map<String, FileStats> =
        withContext(Dispatchers.IO) {
            if (apps.isEmpty()) return@withContext emptyMap()
            val existingFiles = FileUtils.getApkCacheDir(cacheDir)
                .listFiles()
                ?.associateBy { it.name }
                ?: emptyMap()
            apps.associate { it.id to statsFor(it, existingFiles, cacheDir) }
        }

    suspend fun computeOne(item: ApkItem, cacheDir: File): FileStats = withContext(Dispatchers.IO) {
        val file = FileUtils.getCacheFile(item, cacheDir)
        if (file.exists()) readStats(file) else MISSING
    }
}
