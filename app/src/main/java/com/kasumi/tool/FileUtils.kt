package com.kasumi.tool

import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

object FileUtils {
    fun getCacheFile(item: ApkItem, contextCacheDir: File): File {
        val dir = File(contextCacheDir, "apks")

        val ext = try {
            val urlStr = item.url
            if (urlStr != null) {
                // Parse path to avoid query params
                val path = URI(urlStr).path?.lowercase(Locale.ROOT) ?: ""
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

    fun stableIdFromUrl(url: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-1")
            val bytes = md.digest(url.toByteArray())
            val hexChars = "0123456789abcdef"
            val result = StringBuilder(bytes.size * 2)
            for (b in bytes) {
                val i = b.toInt()
                val hexChar1 = hexChars[(i shr 4) and 0x0f]
                val hexChar2 = hexChars[i and 0x0f]
                result.append(hexChar1)
                result.append(hexChar2)
            }
            result.toString()
        } catch (_: Exception) {
            url.hashCode().toString()
        }
    }
}
