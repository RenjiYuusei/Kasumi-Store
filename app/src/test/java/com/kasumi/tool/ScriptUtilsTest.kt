package com.kasumi.tool

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.system.measureTimeMillis

@OptIn(ExperimentalCoroutinesApi::class)
class ScriptUtilsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun benchmarkFileWrite() = runTest {
        val content = "A".repeat(1024 * 1024 * 5) // 5MB
        val directory = tempFolder.newFolder("benchmark")

        val timeBlocking = measureTimeMillis {
            val file = File(directory, "blocking.txt")
            file.writeText(content)
        }
        println("Blocking write took: ${timeBlocking}ms")

        val timeIO = measureTimeMillis {
            ScriptUtils.saveScriptToFile(directory, "async.txt", content)
        }
        println("ScriptUtils.saveScriptToFile took: ${timeIO}ms")
    }

    @Test
    fun saveScriptToFile_writesContentCorrectly() = runTest {
        val content = "print('Hello World')"
        val fileName = "test_script.txt"
        val directory = tempFolder.newFolder("scripts")

        val savedFile = ScriptUtils.saveScriptToFile(directory, fileName, content)

        assertTrue(savedFile.exists())
        assertEquals(content, savedFile.readText())
        assertEquals(fileName, savedFile.name)
    }
}
