package com.kasumi.tool

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class FormattingTest {

    private val original = Locale.getDefault()

    @After
    fun restoreLocale() = Locale.setDefault(original)

    @Test
    fun `bytes below one kilobyte are printed raw`() {
        assertEquals("0 B", formatFileSize(0))
        assertEquals("1023 B", formatFileSize(1023))
    }

    @Test
    fun `kilobytes megabytes and gigabytes switch at the right boundaries`() {
        assertEquals("1.0 KB", formatFileSize(1024))
        assertEquals("1.0 MB", formatFileSize(1024L * 1024))
        assertEquals("1.00 GB", formatFileSize(1024L * 1024 * 1024))
    }

    @Test
    fun `negative sizes do not produce nonsense`() {
        assertEquals("0 B", formatFileSize(-1))
    }

    /**
     * Regression guard: a comma decimal separator would collide with the
     * comma-separated text the size is embedded in.
     */
    @Test
    fun `decimal separator stays a dot under a comma locale`() {
        Locale.setDefault(Locale.forLanguageTag("vi-VN"))
        assertEquals("1.5 MB", formatFileSize(1024L * 1024 * 3 / 2))
    }
}
