package com.kasumi.tool

import org.junit.Test
import org.junit.Assert.assertEquals
import java.io.File
import kotlin.system.measureTimeMillis

class SortBenchmarkTest {
    @Test
    fun benchmarkSort() {
        val results = mutableListOf<File>()
        for (i in 0..1000) {
            results.add(File("split_config.xxhdpi_$i.apk"))
            results.add(File("base.apk"))
            results.add(File("com.example.app_$i.apk"))
            results.add(File("config.$i.apk"))
        }
        results.shuffle()

        // Warm up
        repeat(10) {
            results.sortedWith(compareBy(
                { !it.name.startsWith("base.") && !it.name.contains("com.") },
                { it.name.startsWith("config.") || it.name.startsWith("split_") },
                { it.name }
            ))
        }

        var sortedOriginal: List<File> = emptyList()
        val time1 = measureTimeMillis {
            for (i in 0 until 100) {
                sortedOriginal = results.sortedWith(compareBy(
                    { !it.name.startsWith("base.") && !it.name.contains("com.") },
                    { it.name.startsWith("config.") || it.name.startsWith("split_") },
                    { it.name }
                ))
            }
        }

        var sortedOptimized: List<File> = emptyList()
        val time2 = measureTimeMillis {
            for (i in 0 until 100) {
                // Schwartzian transform caching the name and derived attributes
                sortedOptimized = results.map {
                    val name = it.name
                    val c1 = !name.startsWith("base.") && !name.contains("com.")
                    val c2 = name.startsWith("config.") || name.startsWith("split_")
                    // Note: SortKey is now a file-level class in MainActivity.kt
                    SortKey(it, c1, c2, name)
                }.sortedWith(compareBy(
                    { it.c1 },
                    { it.c2 },
                    { it.name }
                )).map { it.file }
            }
        }

        // Verify the outputs are identical
        assertEquals(sortedOriginal.size, sortedOptimized.size)
        assertEquals(sortedOriginal, sortedOptimized)

        println("Original: $time1 ms")
        println("Schwartzian: $time2 ms")
    }
}
