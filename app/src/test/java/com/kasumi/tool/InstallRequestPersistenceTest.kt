package com.kasumi.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The pending install has to survive the process being killed while the user is
 * in Settings granting "install unknown apps" — that trip is exactly when the
 * system is most likely to reclaim the app.
 */
class InstallRequestPersistenceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun apk(name: String): File = temp.newFile(name).apply { writeBytes(ByteArray(64)) }

    /** Save and reload the way SavedStateHandle would across process death. */
    private fun roundTrip(request: InstallRequest?): InstallRequest? {
        val (kind, paths) = encodeInstallRequest(request)
        return decodeInstallRequest(kind, paths)
    }

    @Test
    fun `a single apk request survives the round trip`() {
        val request = InstallRequest.Single(apk("base.apk"))
        assertEquals(request, roundTrip(request))
    }

    @Test
    fun `a split request keeps every file and their order`() {
        val request = InstallRequest.Splits(
            listOf(apk("base.apk"), apk("config.arm64_v8a.apk"), apk("config.xxhdpi.apk")),
        )
        assertEquals(request, roundTrip(request))
    }

    @Test
    fun `no request saves nothing`() {
        val (kind, paths) = encodeInstallRequest(null)
        assertNull(kind)
        assertNull(paths)
        assertNull(roundTrip(null))
    }

    /**
     * The packages sit in the cache directory, which the system may clear while
     * the process is gone. Replaying the request then would hand the installer a
     * path with nothing behind it.
     */
    @Test
    fun `a request whose file was evicted from the cache is dropped`() {
        val file = apk("base.apk")
        val (kind, paths) = encodeInstallRequest(InstallRequest.Single(file))
        file.delete()
        assertNull(decodeInstallRequest(kind, paths))
    }

    @Test
    fun `a split request is dropped when any one of its files is gone`() {
        val kept = apk("base.apk")
        val evicted = apk("config.arm64_v8a.apk")
        val (kind, paths) = encodeInstallRequest(InstallRequest.Splits(listOf(kept, evicted)))
        evicted.delete()
        assertNull(decodeInstallRequest(kind, paths))
    }

    /** A half-written leftover is no more installable than a missing one. */
    @Test
    fun `a request whose file is empty is dropped`() {
        val file = temp.newFile("base.apk")
        val (kind, paths) = encodeInstallRequest(InstallRequest.Single(file))
        assertNull(decodeInstallRequest(kind, paths))
    }

    @Test
    fun `partial or unknown saved state decodes to nothing`() {
        val file = apk("base.apk")
        assertNull(decodeInstallRequest(null, listOf(file.path)))
        assertNull(decodeInstallRequest("single", null))
        assertNull(decodeInstallRequest("single", emptyList()))
        assertNull(decodeInstallRequest("something-else", listOf(file.path)))
    }
}
