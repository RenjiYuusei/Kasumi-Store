package com.kasumi.tool

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
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

    private fun storedEntry(name: String, content: ByteArray) = ZipEntry(name).apply {
        method = ZipEntry.STORED
        size = content.size.toLong()
        compressedSize = content.size.toLong()
        crc = CRC32().apply { update(content) }.value
    }

    /** An archive comment carrying the EOCD signature is legal and must not confuse the scan. */
    private fun packageWithDecoyInComment(): File {
        val f = temp.newFile()
        ZipOutputStream(f.outputStream()).use { zip ->
            // 'PK' encodes to the four EOCD bytes in UTF-8.
            zip.setComment("A".repeat(50) + "PK" + "A".repeat(146))
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(ByteArray(256))
            zip.closeEntry()
        }
        return f
    }

    @Test
    fun `a complete package is accepted even when its comment contains the signature`() {
        val f = packageWithDecoyInComment()
        verifyDownloadedPackage(f, f.length())
    }

    /**
     * The record has to end exactly at EOF. Cutting inside the comment leaves the
     * decoy signature as the last match, and the real record now claims more
     * bytes than the file has — neither may be accepted.
     */
    @Test
    fun `an archive cut inside a comment containing the signature is rejected`() {
        val whole = packageWithDecoyInComment().readBytes()
        val cut = temp.newFile()
        cut.writeBytes(whole.copyOf(whole.size - 80))
        expectRejected(cut, -1L, "the comment is only half there")
    }

    /**
     * An XAPK stores whole APKs, so a half-received container really does end
     * with a complete inner EOCD. Only the central-directory cross-check tells
     * the two apart.
     */
    @Test
    fun `a container truncated after a complete inner package is rejected`() {
        val inner = completePackage(payload = 128).readBytes()
        val outer = temp.newFile()
        ZipOutputStream(outer.outputStream()).use { zip ->
            for (name in listOf("base.apk", "config.arm64_v8a.apk")) {
                // STORED so the inner archive lands in the container byte for
                // byte, exactly as a real XAPK holds its split APKs.
                zip.putNextEntry(storedEntry(name, inner))
                zip.write(inner)
                zip.closeEntry()
            }
        }
        val bytes = outer.readBytes()
        // Keep the first stored APK whole and drop everything from the middle of
        // the second one onwards, so the outer central directory never arrives.
        val cut = temp.newFile()
        cut.writeBytes(bytes.copyOf(inner.size + inner.size / 2))
        expectRejected(cut, -1L, "the outer archive never finished")
    }

    // --- Zip64 --------------------------------------------------------------

    private fun le16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())

    private fun le32(v: Long) = ByteArray(4) { ((v shr (it * 8)) and 0xFF).toByte() }

    private fun le64(v: Long) = ByteArray(8) { ((v shr (it * 8)) and 0xFF).toByte() }

    /** Offset of the plain EOCD in an archive that has no archive comment. */
    private fun eocdStartOf(bytes: ByteArray) = bytes.size - 22

    /**
     * Rebuilds a normal archive with a Zip64 tail: the central directory is kept
     * where it is, then a Zip64 EOCD record, its locator, and an EOCD carrying
     * the 0xFFFFFFFF sentinels. Producing a genuine Zip64 file would need more
     * than 4 GB or 65535 entries, which a unit test cannot afford.
     *
     * @param corruptLocator writes a wrong locator signature, standing in for the
     *   Zip64 structures being absent or damaged.
     */
    private fun zip64Package(
        corruptLocator: Boolean = false,
        corruptRecord: Boolean = false,
        withZip64Tail: Boolean = true,
        // false = only the entry counts are sentinels, the 32-bit size/offset
        // still carry real values. That is what an archive with more than 65535
        // entries but under 4 GB looks like.
        sentinelSizeAndOffset: Boolean = true,
        sentinelCounts: Boolean = false,
    ): File {
        val normal = completePackage(payload = 256).readBytes()
        val eocdStart = eocdStartOf(normal)
        val cdSize = readLe32(normal, eocdStart + 12)
        val cdOffset = readLe32(normal, eocdStart + 16)

        val out = java.io.ByteArrayOutputStream()
        out.write(normal, 0, eocdStart) // local headers + central directory

        val zip64RecordOffset = eocdStart.toLong()
        if (withZip64Tail) {
            out.write(le32(if (corruptRecord) 0x06064b51L else 0x06064b50L))
            out.write(le64(44L)) // size of the record that follows
            out.write(le16(45)); out.write(le16(45)) // version made by / needed
            out.write(le32(0L)); out.write(le32(0L)) // this disk / disk with CD
            out.write(le64(1L)); out.write(le64(1L)) // entries here / entries total
            out.write(le64(cdSize))
            out.write(le64(cdOffset))

            out.write(le32(if (corruptLocator) 0x07064b51L else 0x07064b50L))
            out.write(le32(0L))
            out.write(le64(zip64RecordOffset))
            out.write(le32(1L))
        }

        val count = if (sentinelCounts) 0xFFFF else 1
        out.write(le32(0x06054b50L)) // EOCD
        out.write(le16(0)); out.write(le16(0))
        out.write(le16(count)); out.write(le16(count))
        out.write(le32(if (sentinelSizeAndOffset) 0xFFFFFFFFL else cdSize))
        out.write(le32(if (sentinelSizeAndOffset) 0xFFFFFFFFL else cdOffset))
        out.write(le16(0)) // no comment

        val f = temp.newFile()
        f.writeBytes(out.toByteArray())
        return f
    }

    private fun readLe32(bytes: ByteArray, at: Int): Long =
        (bytes[at].toLong() and 0xFF) or
            ((bytes[at + 1].toLong() and 0xFF) shl 8) or
            ((bytes[at + 2].toLong() and 0xFF) shl 16) or
            ((bytes[at + 3].toLong() and 0xFF) shl 24)

    @Test
    fun `a complete zip64 archive is accepted`() {
        val f = zip64Package()
        verifyDownloadedPackage(f, f.length())
    }

    /**
     * The sentinel used to be taken as "cannot check, assume complete". An
     * archive that claims Zip64 but has no usable Zip64 record is not complete.
     */
    @Test
    fun `a zip64 sentinel without a valid locator is rejected`() {
        val f = zip64Package(corruptLocator = true)
        expectRejected(f, f.length(), "the Zip64 locator is not there")
    }

    /**
     * More than 65535 entries but under 4 GB: only the counts are sentinels, the
     * 32-bit size and offset are real. The Zip64 chain still has to be honoured.
     */
    @Test
    fun `a zip64 archive flagged only by its entry counts is accepted`() {
        val f = zip64Package(sentinelSizeAndOffset = false, sentinelCounts = true)
        verifyDownloadedPackage(f, f.length())
    }

    /**
     * The count sentinel used to be ignored outright, so a broken Zip64 chain
     * behind it went unnoticed and the archive took the ordinary path.
     */
    @Test
    fun `an entry-count sentinel with a damaged zip64 record is rejected`() {
        val f = zip64Package(
            sentinelSizeAndOffset = false,
            sentinelCounts = true,
            corruptRecord = true,
        )
        expectRejected(f, f.length(), "the Zip64 record behind the locator is damaged")
    }

    /**
     * 0xFFFF is also the honest count of an archive holding exactly 65535
     * entries, which needs no Zip64 record at all. Demanding one here would
     * reject a perfectly installable package, so the 32-bit fields stand.
     */
    @Test
    fun `an entry-count sentinel with no zip64 tail falls back to the 32-bit fields`() {
        val f = zip64Package(
            sentinelSizeAndOffset = false,
            sentinelCounts = true,
            withZip64Tail = false,
        )
        verifyDownloadedPackage(f, f.length())
    }

    /** A plain archive whose EOCD was patched to claim Zip64 has no Zip64 tail at all. */
    @Test
    fun `a sentinel on an archive with no zip64 structures is rejected`() {
        val bytes = completePackage(payload = 256).readBytes()
        val eocdStart = eocdStartOf(bytes)
        le32(0xFFFFFFFFL).copyInto(bytes, eocdStart + 16)
        val f = temp.newFile()
        f.writeBytes(bytes)
        expectRejected(f, f.length(), "it claims Zip64 but carries none")
    }
}
