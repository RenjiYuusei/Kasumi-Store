package com.kasumi.tool

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class DownloadVerificationTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** A minimal file that starts like every ZIP-based APK container. */
    private fun apkLike(size: Int = 64): File {
        val f = temp.newFile()
        val bytes = ByteArray(size)
        bytes[0] = 0x50 // 'P'
        bytes[1] = 0x4B // 'K'
        bytes[2] = 0x03
        bytes[3] = 0x04
        f.writeBytes(bytes)
        return f
    }

    private fun expectFailure(file: File, expectedLength: Long, because: String) {
        try {
            verifyDownloadedPackage(file, expectedLength)
            fail("Expected verification to reject the download: $because")
        } catch (e: IOException) {
            assertTrue("message should say something useful", e.message!!.isNotBlank())
        }
    }

    @Test
    fun `a complete package with a matching length is accepted`() {
        val f = apkLike(64)
        verifyDownloadedPackage(f, 64L)
    }

    @Test
    fun `a complete package is accepted when the server sent no length`() {
        verifyDownloadedPackage(apkLike(64), -1L)
    }

    @Test
    fun `a short read against a known length is rejected`() {
        expectFailure(apkLike(10), 64L, "10 of 64 bytes arrived")
    }

    /**
     * Regression guard for the old `expected > 0` condition: a zero-length body
     * skipped the check entirely and was renamed into the cache as a valid APK.
     */
    @Test
    fun `an empty download is rejected even when Content-Length agrees`() {
        expectFailure(temp.newFile(), 0L, "the body was empty")
    }

    @Test
    fun `an empty download is rejected when the length is unknown`() {
        expectFailure(temp.newFile(), -1L, "the body was empty")
    }

    /**
     * A mirror whose link expired often answers 200 with an HTML notice. That is
     * a complete response, so only the content itself gives it away.
     */
    @Test
    fun `an html error page served as 200 is rejected`() {
        val f = temp.newFile()
        val html = "<!DOCTYPE html><html><body>Link expired</body></html>".toByteArray()
        f.writeBytes(html)
        expectFailure(f, html.size.toLong(), "it is HTML, not a package")
    }

    /** Close-delimited responses give no length to compare against at all. */
    @Test
    fun `a non-package payload is rejected when the length is unknown`() {
        val f = temp.newFile()
        f.writeBytes("not an apk".toByteArray())
        expectFailure(f, -1L, "it does not start with the ZIP signature")
    }

    @Test
    fun `a file too short to hold a signature is rejected`() {
        val f = temp.newFile()
        f.writeBytes(byteArrayOf(0x50))
        expectFailure(f, 1L, "one byte cannot carry the PK signature")
    }
}
