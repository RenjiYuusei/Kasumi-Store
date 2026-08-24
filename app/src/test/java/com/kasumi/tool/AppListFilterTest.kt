package com.kasumi.tool

import org.junit.Assert.assertEquals
import org.junit.Test

class AppListFilterTest {

    private fun item(id: String, name: String, url: String? = null) = ApkItem(
        id = id,
        name = name,
        sourceType = SourceType.URL,
        url = url,
        uri = null,
    )

    private val delta = item("1", "Delta", "https://example.com/delta.apk")
    private val roblox = item("2", "Roblox", "https://example.com/roblox.xapk")
    private val arceus = item("3", "arceus", "https://cdn.example.com/arceus.apk")

    private val all = listOf(delta, roblox, arceus)

    private val stats = mapOf(
        "1" to FileStats(exists = true, size = 300L, lastModified = 100L),
        "2" to FileStats(exists = true, size = 100L, lastModified = 300L),
        "3" to FileStats(exists = true, size = 200L, lastModified = 200L),
    )

    @Test
    fun `empty query keeps every item`() {
        val result = filterAndSortApps(all, "", SortMode.NAME_ASC, stats)
        assertEquals(3, result.size)
    }

    @Test
    fun `blank query is treated as empty`() {
        val result = filterAndSortApps(all, "   ", SortMode.NAME_ASC, stats)
        assertEquals(3, result.size)
    }

    @Test
    fun `name match is case insensitive`() {
        val result = filterAndSortApps(all, "ARCEUS", SortMode.NAME_ASC, stats)
        assertEquals(listOf(arceus), result)
    }

    @Test
    fun `query also matches the url`() {
        val result = filterAndSortApps(all, "cdn.example", SortMode.NAME_ASC, stats)
        assertEquals(listOf(arceus), result)
    }

    @Test
    fun `no match yields an empty list`() {
        assertEquals(emptyList<ApkItem>(), filterAndSortApps(all, "nothing", SortMode.NAME_ASC, stats))
    }

    @Test
    fun `name ascending ignores case so lowercase names are not pushed to the end`() {
        val result = filterAndSortApps(all, "", SortMode.NAME_ASC, stats)
        assertEquals(listOf("arceus", "Delta", "Roblox"), result.map { it.name })
    }

    @Test
    fun `name descending is the reverse of ascending`() {
        val result = filterAndSortApps(all, "", SortMode.NAME_DESC, stats)
        assertEquals(listOf("Roblox", "Delta", "arceus"), result.map { it.name })
    }

    @Test
    fun `size descending orders by cached size`() {
        val result = filterAndSortApps(all, "", SortMode.SIZE_DESC, stats)
        assertEquals(listOf("1", "3", "2"), result.map { it.id })
    }

    @Test
    fun `date descending orders by last modified`() {
        val result = filterAndSortApps(all, "", SortMode.DATE_DESC, stats)
        assertEquals(listOf("2", "3", "1"), result.map { it.id })
    }

    @Test
    fun `items without stats sort as zero rather than dropping out`() {
        val result = filterAndSortApps(all, "", SortMode.SIZE_DESC, emptyMap())
        assertEquals(3, result.size)
    }

    @Test
    fun `filtering does not mutate the input list`() {
        val input = all.toList()
        filterAndSortApps(input, "delta", SortMode.SIZE_DESC, stats)
        assertEquals(all, input)
    }
}
