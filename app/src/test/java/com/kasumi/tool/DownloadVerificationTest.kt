package com.kasumi.tool

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DownloadVerificationTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** A real, complete ZIP — the shape every .apk/.apks/.apkm/.xapk has. */
    private fun completePackage(payload: Int = 512): File {
        val f = temp.newFile()
        ZipOutputStream(f.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(ByteArray(payload))
            zip.closeEntry()
        }
        return f
    }

    /** The same archive with its tail lost, as a dropped connection would leave it. */
    private fun truncatedPackage(keepFraction: Double = 0.5): File {
        val whole = completePackage().readBytes()
        val cut = temp.newFile()
        cut.writeBytes(whole.copyOf((whole.size * keepFraction).toInt()))
        return cut
    }

    private fun expectRejected(file: File, expectedLength: Long, because: String) {
        try {
            verifyDownloadedPackage(file, expectedLength)
            fail("Expected verification to reject the download: $because")
        } catch (e: IOException) {
            assertTrue("message should say something useful", e.message!!.isNotBlank())
        }
    }

    @Test
    fun `a complete package with a matching length is accepted`() {
        val f = completePackage()
        verifyDownloadedPackage(f, f.length())
    }

    @Test
    fun `a complete package is accepted when the server sent no length`() {
        verifyDownloadedPackage(completePackage(), -1L)
    }

    @Test
    fun `a short read against a known length is rejected`() {
        val f = truncatedPackage()
        expectRejected(f, f.length() + 1000L, "fewer bytes arrived than advertised")
    }

    /**
     * The case that matters most: no Content-Length to compare against, and the
     * stream stopped after the first local-file header. The bytes still start
     * with PK, so only the missing end-of-central-directory record gives it away.
     */
    @Test
    fun `a truncated archive is rejected when the length is unknown`() {
        expectRejected(truncatedPackage(), -1L, "the archive has no end-of-central-directory")
    }

    @Test
    fun `an archive cut just before its central directory is rejected`() {
        expectRejected(truncatedPackage(keepFraction = 0.95), -1L, "the tail is missing")
    }

    /**
     * Regression guard for the old `expected > 0` condition: a zero-length body
     * skipped the check entirely and was renamed into the cache as a valid APK.
     */
    @Test
    fun `an empty download is rejected even when Content-Length agrees`() {
        expectRejected(temp.newFile(), 0L, "the body was empty")
    }

    @Test
    fun `an empty download is rejected when the length is unknown`() {
        expectRejected(temp.newFile(), -1L, "the body was empty")
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
        expectRejected(f, html.size.toLong(), "it is HTML, not a package")
    }

    @Test
    fun `a non-package payload is rejected when the length is unknown`() {
        val f = temp.newFile()
        f.writeBytes("not an apk".toByteArray())
        expectRejected(f, -1L, "it does not start with the ZIP signature")
    }

    @Test
    fun `a file too short to hold a signature is rejected`() {
        val f = temp.newFile()
        f.writeBytes(byteArrayOf(0x50))
        expectRejected(f, 1L, "one byte cannot carry the PK signature")
    }

    /** A comment after the EOCD is legal, so the record must still be found. */
    @Test
    fun `a package whose archive comment follows the record is accepted`() {
        val f = temp.newFile()
        ZipOutputStream(f.outputStream()).use { zip ->
            zip.setComment("built by CI")
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(ByteArray(64))
            zip.closeEntry()
        }
        verifyDownloadedPackage(f, f.length())
    }
}
