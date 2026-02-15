package com.kasumi.tool

import java.io.File
import java.security.MessageDigest
import java.util.Locale

object FileUtils {
    fun getCacheFile(item: ApkItem, contextCacheDir: File): File {
        val dir = File(contextCacheDir, "apks")
        val u = item.url?.lowercase(Locale.ROOT)
        // Simple extension detection
        val ext = when {
            u != null && u.contains(".xapk") -> "xapk"
            u != null && u.contains(".apks") -> "apks"
            u != null && u.contains(".apkm") -> "apkm"
            else -> "apk"
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
