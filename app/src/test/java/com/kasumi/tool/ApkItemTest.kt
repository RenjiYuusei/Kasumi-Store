package com.kasumi.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.util.UUID

class ApkItemTest {

    @Test
    fun testSerializationAndDeserialization() {
        val listSize = 100
        val items = (1..listSize).map {
            ApkItem(
                id = UUID.randomUUID().toString(),
                name = "App $it",
                sourceType = SourceType.URL,
                url = "https://example.com/app$it.apk",
                uri = null,
                versionName = "1.0.$it",
                versionCode = it.toLong(),
                iconUrl = "https://example.com/icon$it.png"
            )
        }

        val tempFile = File.createTempFile("test_items", ".json")
        try {
            // Write
            BufferedWriter(FileWriter(tempFile)).use { writer ->
                ApkItem.writeListTo(items, writer)
            }

            // Read
            val loaded = BufferedReader(FileReader(tempFile)).use { reader ->
                ApkItem.readListFrom(reader)
            }

            assertEquals(items.size, loaded.size)
            assertEquals(items[0].id, loaded[0].id)
            assertEquals(items[0].name, loaded[0].name)

        } finally {
            tempFile.delete()
        }
    }
}
