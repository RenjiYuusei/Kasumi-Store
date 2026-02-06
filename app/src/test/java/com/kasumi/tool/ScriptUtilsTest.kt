package com.kasumi.tool

import org.junit.Test
import org.junit.Assert.*

class ScriptUtilsTest {

    @Test
    fun testMergeScripts_matchFound() {
        val online = listOf(ScriptItem("1", "ScriptA", "GameA"))
        val local = listOf(ScriptItem("local1", "ScriptA", "Local", localPath = "/path/to/ScriptA"))

        val result = ScriptUtils.mergeScripts(online, local)

        assertEquals(1, result.size)
        assertEquals("/path/to/ScriptA", result[0].localPath)
        assertEquals("ScriptA", result[0].name)
    }

    @Test
    fun testMergeScripts_noMatch() {
        val online = listOf(ScriptItem("1", "ScriptA", "GameA"))
        val local = listOf(ScriptItem("local1", "ScriptB", "Local", localPath = "/path/to/ScriptB"))

        val result = ScriptUtils.mergeScripts(online, local)

        assertEquals(2, result.size)
        // Online script remains unchanged
        assertEquals("ScriptA", result[0].name)
        assertNull(result[0].localPath)
        // Local script added as unmatched
        assertEquals("ScriptB", result[1].name)
        assertEquals("/path/to/ScriptB", result[1].localPath)
    }

    @Test
    fun testMergeScripts_duplicateLocals() {
        // First local should be used for merge
        val online = listOf(ScriptItem("1", "ScriptA", "GameA"))
        val local = listOf(
            ScriptItem("local1", "ScriptA", "Local", localPath = "/path/1"),
            ScriptItem("local2", "ScriptA", "Local", localPath = "/path/2")
        )

        val result = ScriptUtils.mergeScripts(online, local)

        // Should have 2 items: 1 merged, 1 unmatched
        assertEquals(2, result.size)
        assertEquals("/path/1", result[0].localPath)
        assertEquals("/path/2", result[1].localPath)
    }
}
