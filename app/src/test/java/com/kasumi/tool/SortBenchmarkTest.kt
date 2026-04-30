package com.kasumi.tool

import org.junit.Test
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

        val time1 = measureTimeMillis {
            for (i in 0..100) {
                results.sortedWith(compareBy(
                    { !it.name.startsWith("base.") && !it.name.contains("com.") },
                    { it.name.startsWith("config.") || it.name.startsWith("split_") },
                    { it.name }
                ))
            }
        }

        class SortKey(val file: File, val c1: Boolean, val c2: Boolean, val name: String)
        val time2 = measureTimeMillis {
            for (i in 0..100) {
                // Schwartzian transform caching the name and derived attributes
                results.map {
                    val name = it.name
                    val c1 = !name.startsWith("base.") && !name.contains("com.")
                    val c2 = name.startsWith("config.") || name.startsWith("split_")
                    SortKey(it, c1, c2, name)
                }.sortedWith(compareBy(
                    { it.c1 },
                    { it.c2 },
                    { it.name }
                )).map { it.file }
            }
        }

        val time3 = measureTimeMillis {
            for (i in 0..100) {
                results.sortedWith(Comparator { a, b ->
                    val nameA = a.name
                    val nameB = b.name

                    val c1A = !nameA.startsWith("base.") && !nameA.contains("com.")
                    val c1B = !nameB.startsWith("base.") && !nameB.contains("com.")
                    if (c1A != c1B) return@Comparator c1A.compareTo(c1B)

                    val c2A = nameA.startsWith("config.") || nameA.startsWith("split_")
                    val c2B = nameB.startsWith("config.") || nameB.startsWith("split_")
                    if (c2A != c2B) return@Comparator c2A.compareTo(c2B)

                    nameA.compareTo(nameB)
                })
            }
        }

        val time4 = measureTimeMillis {
            for (i in 0..100) {
                results.associateBy { it }.entries.map {
                    val name = it.key.name
                    val c1 = !name.startsWith("base.") && !name.contains("com.")
                    val c2 = name.startsWith("config.") || name.startsWith("split_")
                    SortKey(it.key, c1, c2, name)
                }.sortedWith(compareBy(
                    { it.c1 },
                    { it.c2 },
                    { it.name }
                )).map { it.file }
            }
        }

        println("Original: $time1 ms")
        println("Schwartzian: $time2 ms")
        println("Custom Comparator: $time3 ms")
        println("Associate by: $time4 ms")
    }
}
