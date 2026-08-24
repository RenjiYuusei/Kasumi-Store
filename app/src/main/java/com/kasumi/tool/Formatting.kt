package com.kasumi.tool

import java.util.Locale

private const val KB = 1024.0
private const val MB = KB * 1024
private const val GB = MB * 1024

/**
 * Human-readable byte size.
 *
 * Always formatted with [Locale.US] so the decimal separator is a dot: on a
 * Vietnamese locale "1.5 MB" would otherwise render as "1,5 MB" and collide
 * with the comma-separated text it is embedded in.
 */
fun formatFileSize(bytes: Long): String = when {
    bytes < 0 -> "0 B"
    bytes < KB -> "$bytes B"
    bytes < MB -> String.format(Locale.US, "%.1f KB", bytes / KB)
    bytes < GB -> String.format(Locale.US, "%.1f MB", bytes / MB)
    else -> String.format(Locale.US, "%.2f GB", bytes / GB)
}
