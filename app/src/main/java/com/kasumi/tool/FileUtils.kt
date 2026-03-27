package com.kasumi.tool

import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

object FileUtils {
    fun getApkCacheDir(contextCacheDir: File): File {
        return File(contextCacheDir, "apks")
    }

    fun getCacheFile(item: ApkItem, contextCacheDir: File): File {
        val dir = getApkCacheDir(contextCacheDir)

        val ext = try {
            val urlStr = item.url
            if (urlStr != null) {
                // Parse path to avoid query params
                val qIdx = urlStr.indexOf("?")
                val hIdx = urlStr.indexOf("#")
                val qEnd = if (qIdx == -1) urlStr.length else qIdx
                val hEnd = if (hIdx == -1) urlStr.length else hIdx
                val endIdx = kotlin.math.min(qEnd, hEnd)
                val path = urlStr.substring(0, endIdx).lowercase(java.util.Locale.ROOT)
                when {
                    path.endsWith(".xapk") -> "xapk"
                    path.endsWith(".apks") -> "apks"
                    path.endsWith(".apkm") -> "apkm"
                    else -> "apk"
                }
            } else {
                "apk"
            }
        } catch (e: Exception) {
            "apk"
        }

        return File(dir, "${item.id}.$ext")
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun stableIdFromUrl(url: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-1")
            val bytes = md.digest(url.toByteArray())
            bytes.toHexString()
        } catch (_: Exception) {
            url.hashCode().toString()
        }
    }
}
