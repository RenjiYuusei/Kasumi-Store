package com.kasumi.tool

import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FileStatsTest {

    @Test
    fun benchmarkFileStatsLogic() = runTest {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "kasumi_test_${UUID.randomUUID()}")
        tempDir.mkdirs()
        val cacheDir = File(tempDir, "cache")
        cacheDir.mkdirs()
        // Ensure cacheDir/apks exists for FileUtils logic
        val apksDir = File(cacheDir, "apks")
        apksDir.mkdirs()

        val items = (1..5000).map { i ->
            ApkItem(
                id = "item_$i",
                name = "App $i",
                sourceType = SourceType.URL,
                url = "https://example.com/app$i.apk",
                uri = null
            )
        }

        items.take(2500).forEach { item ->
            val f = FileUtils.getCacheFile(item, cacheDir)
            f.parentFile?.mkdirs()
            f.createNewFile()
        }

        // --- Baseline ---
        val baselineStart = System.nanoTime()
        val baselineStats = mutableMapOf<String, FileStats>()
        items.forEach { item ->
             val f = FileUtils.getCacheFile(item, cacheDir)
             if (f.exists()) {
                 baselineStats[item.id] = FileStats(true, f.length(), f.lastModified())
             } else {
                 baselineStats[item.id] = FileStats(false, 0L, 0L)
             }
        }
        val baselineTime = (System.nanoTime() - baselineStart) / 1_000_000.0
        println("Baseline: ${String.format("%.2f", baselineTime)} ms")

        // --- Optimized (using Helper) ---
        val currentStats = HashMap(baselineStats)
        val targetItem = items[2999]
        val targetFile = FileUtils.getCacheFile(targetItem, cacheDir)
        targetFile.createNewFile()

        val optimizedStart = System.nanoTime()
        FileStatsHelper.updateItemFileStats(targetItem, currentStats, cacheDir)
        val optimizedTime = (System.nanoTime() - optimizedStart) / 1_000_000.0
        println("Optimized: ${String.format("%.4f", optimizedTime)} ms")

        tempDir.deleteRecursively()

        // Safety check
        assert(currentStats[targetItem.id]!!.exists)
        assert(optimizedTime < baselineTime)
    }
}
