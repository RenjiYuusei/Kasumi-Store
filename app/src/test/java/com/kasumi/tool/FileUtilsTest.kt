package com.kasumi.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FileUtilsTest {

    private val cacheDir = File("/tmp/cache")

    private fun item(url: String?) = ApkItem(
        id = "abc",
        name = "Test",
        sourceType = SourceType.URL,
        url = url,
        uri = null,
    )

    @Test
    fun `cache files live under an apks subdirectory`() {
        assertEquals(File(cacheDir, "apks"), FileUtils.getApkCacheDir(cacheDir))
    }

    @Test
    fun `known container extensions are preserved`() {
        assertEquals("abc.xapk", FileUtils.getCacheFile(item("https://x.dev/a.xapk"), cacheDir).name)
        assertEquals("abc.apks", FileUtils.getCacheFile(item("https://x.dev/a.apks"), cacheDir).name)
        assertEquals("abc.apkm", FileUtils.getCacheFile(item("https://x.dev/a.apkm"), cacheDir).name)
    }

    @Test
    fun `anything else falls back to apk`() {
        assertEquals("abc.apk", FileUtils.getCacheFile(item("https://x.dev/a.apk"), cacheDir).name)
        assertEquals("abc.apk", FileUtils.getCacheFile(item("https://x.dev/download"), cacheDir).name)
        assertEquals("abc.apk", FileUtils.getCacheFile(item(null), cacheDir).name)
    }

    @Test
    fun `extension is read from the path so query strings do not confuse it`() {
        assertEquals("abc.xapk", FileUtils.getCacheFile(item("https://x.dev/a.xapk?token=1"), cacheDir).name)
        assertEquals("abc.apks", FileUtils.getCacheFile(item("https://x.dev/a.apks#frag"), cacheDir).name)
    }

    /** A query parameter that merely mentions .xapk must not change the extension. */
    @Test
    fun `extension in the query string is ignored`() {
        assertEquals("abc.apk", FileUtils.getCacheFile(item("https://x.dev/get?name=a.xapk"), cacheDir).name)
    }

    @Test
    fun `extension matching is case insensitive`() {
        assertEquals("abc.xapk", FileUtils.getCacheFile(item("https://x.dev/A.XAPK"), cacheDir).name)
    }

    @Test
    fun `stable id is deterministic and url specific`() {
        val a = FileUtils.stableIdFromUrl("https://x.dev/a.apk")
        val b = FileUtils.stableIdFromUrl("https://x.dev/a.apk")
        val c = FileUtils.stableIdFromUrl("https://x.dev/b.apk")
        assertEquals(a, b)
        assertNotEquals(a, c)
        assertEquals("SHA-1 hex is 40 characters", 40, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
