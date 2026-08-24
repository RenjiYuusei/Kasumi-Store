package com.kasumi.tool

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlUtilsTest {

    @Test
    fun `non dropbox urls are untouched`() {
        val url = "https://github.com/x/y/releases/download/v1/app.apk"
        assertEquals(url, normalizeDownloadUrl(url))
    }

    @Test
    fun `www host is rewritten to the direct content host`() {
        assertEquals(
            "https://dl.dropboxusercontent.com/s/abc/app.apk?dl=1",
            normalizeDownloadUrl("https://www.dropbox.com/s/abc/app.apk"),
        )
    }

    @Test
    fun `bare host is rewritten too`() {
        assertEquals(
            "https://dl.dropboxusercontent.com/s/abc/app.apk?dl=1",
            normalizeDownloadUrl("https://dropbox.com/s/abc/app.apk"),
        )
    }

    @Test
    fun `dl zero is flipped to dl one`() {
        assertEquals(
            "https://dl.dropboxusercontent.com/s/abc/app.apk?dl=1",
            normalizeDownloadUrl("https://www.dropbox.com/s/abc/app.apk?dl=0"),
        )
    }

    @Test
    fun `an existing dl one is left alone`() {
        assertEquals(
            "https://dl.dropboxusercontent.com/s/abc/app.apk?dl=1",
            normalizeDownloadUrl("https://www.dropbox.com/s/abc/app.apk?dl=1"),
        )
    }

    @Test
    fun `dl is appended to an existing query string`() {
        assertEquals(
            "https://dl.dropboxusercontent.com/s/abc/app.apk?rlkey=z&dl=1",
            normalizeDownloadUrl("https://www.dropbox.com/s/abc/app.apk?rlkey=z"),
        )
    }
}
