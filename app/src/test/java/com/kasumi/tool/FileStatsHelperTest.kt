package com.kasumi.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileStatsHelperTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun item(id: String, url: String) = ApkItem(
        id = id,
        name = id,
        sourceType = SourceType.URL,
        url = url,
        uri = null,
    )

    private fun listing(dir: File): Map<String, File> =
        dir.listFiles()?.associateBy { it.name } ?: emptyMap()

    @Test
    fun `a missing cache file reports as absent with zero size`() {
        val cacheDir = temp.newFolder("cache")
        val stats = FileStatsHelper.statsFor(item("a", "https://x.dev/a.apk"), emptyMap(), cacheDir)
        assertFalse(stats.exists)
        assertEquals(0L, stats.size)
        assertEquals(0L, stats.lastModified)
    }

    @Test
    fun `a present cache file reports its real size`() {
        val cacheDir = temp.newFolder("cache")
        val apks = File(cacheDir, "apks").apply { mkdirs() }
        val target = item("a", "https://x.dev/a.apk")
        File(apks, "a.apk").writeBytes(ByteArray(1234))

        val stats = FileStatsHelper.statsFor(target, listing(apks), cacheDir)
        assertTrue(stats.exists)
        assertEquals(1234L, stats.size)
        assertTrue(stats.lastModified > 0L)
    }

    /** The listing is keyed by filename, so an unrelated file must not match. */
    @Test
    fun `an unrelated file in the cache does not count as a hit`() {
        val cacheDir = temp.newFolder("cache")
        val apks = File(cacheDir, "apks").apply { mkdirs() }
        File(apks, "someone-else.apk").writeBytes(ByteArray(8))

        val stats = FileStatsHelper.statsFor(item("a", "https://x.dev/a.apk"), listing(apks), cacheDir)
        assertFalse(stats.exists)
    }

    @Test
    fun `the container extension is taken into account when looking up the cache`() {
        val cacheDir = temp.newFolder("cache")
        val apks = File(cacheDir, "apks").apply { mkdirs() }
        val target = item("a", "https://x.dev/a.xapk")
        // Written with the plain .apk name, which must NOT satisfy an .xapk item.
        File(apks, "a.apk").writeBytes(ByteArray(10))

        assertFalse(FileStatsHelper.statsFor(target, listing(apks), cacheDir).exists)

        File(apks, "a.xapk").writeBytes(ByteArray(20))
        val stats = FileStatsHelper.statsFor(target, listing(apks), cacheDir)
        assertTrue(stats.exists)
        assertEquals(20L, stats.size)
    }
}
