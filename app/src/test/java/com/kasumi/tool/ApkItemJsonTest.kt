package com.kasumi.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter

class ApkItemJsonTest {

    private val sample = ApkItem(
        id = "id-1",
        name = "Delta",
        sourceType = SourceType.URL,
        url = "https://x.dev/a.apk",
        uri = null,
        versionName = "1.2.3",
        versionCode = 42L,
        iconUrl = "https://x.dev/i.png",
    )

    @Test
    fun `list survives a json round trip`() {
        val restored = ApkItem.fromJsonList(ApkItem.toJsonList(listOf(sample)))
        assertEquals(listOf(sample), restored)
    }

    @Test
    fun `writer and reader round trip matches the string round trip`() {
        val writer = StringWriter()
        ApkItem.writeListTo(listOf(sample), writer)
        assertEquals(listOf(sample), ApkItem.readListFrom(StringReader(writer.toString())))
    }

    @Test
    fun `null and blank input yield an empty list rather than throwing`() {
        assertEquals(emptyList<ApkItem>(), ApkItem.fromJsonList(null))
        assertEquals(emptyList<ApkItem>(), ApkItem.fromJsonList(""))
        assertEquals(emptyList<ApkItem>(), ApkItem.fromJsonList("   "))
    }

    @Test
    fun `malformed json yields an empty list rather than throwing`() {
        assertEquals(emptyList<ApkItem>(), ApkItem.fromJsonList("{not json"))
    }

    @Test
    fun `null fields survive the round trip`() {
        val bare = sample.copy(versionName = null, versionCode = null, iconUrl = null, url = null)
        val restored = ApkItem.fromJsonList(ApkItem.toJsonList(listOf(bare))).single()
        assertNull(restored.versionName)
        assertNull(restored.versionCode)
        assertNull(restored.iconUrl)
        assertNull(restored.url)
    }

    @Test
    fun `unknown fields are skipped instead of breaking parsing`() {
        val json = """[{"id":"x","name":"N","sourceType":"URL","somethingNew":{"a":1}}]"""
        val restored = ApkItem.fromJsonList(json).single()
        assertEquals("x", restored.id)
        assertEquals("N", restored.name)
    }

    @Test
    fun `an unrecognised source type falls back to URL`() {
        val json = """[{"id":"x","name":"N","sourceType":"CARRIER_PIGEON"}]"""
        assertEquals(SourceType.URL, ApkItem.fromJsonList(json).single().sourceType)
    }

    @Test
    fun `a missing id is replaced by a generated one`() {
        val restored = ApkItem.fromJsonList("""[{"name":"N","sourceType":"LOCAL"}]""").single()
        assertTrue(restored.id.isNotBlank())
        assertEquals(SourceType.LOCAL, restored.sourceType)
    }
}
