package com.kasumi.tool

/**
 * Rewrites a share link into something that actually serves the file.
 *
 * A Dropbox share URL returns an HTML preview page unless `dl=1` is set, so
 * downloading it verbatim yields a "APK" that is really a web page. Everything
 * that is not a Dropbox link is returned untouched.
 */
fun normalizeDownloadUrl(raw: String): String {
    if (!raw.contains("dropbox.com")) return raw
    val direct = raw
        .replace("://www.dropbox.com", "://dl.dropboxusercontent.com")
        .replace("://dropbox.com", "://dl.dropboxusercontent.com")
    return when {
        direct.contains("dl=0") -> direct.replace("dl=0", "dl=1")
        direct.contains("dl=") -> direct
        direct.contains("?") -> "$direct&dl=1"
        else -> "$direct?dl=1"
    }
}
