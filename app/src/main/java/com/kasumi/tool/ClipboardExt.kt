package com.kasumi.tool

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard

/**
 * Plain-text helpers over the [Clipboard] API.
 *
 * The old `LocalClipboardManager.setText/getText` pair is deprecated in favour
 * of [Clipboard], whose accessors are suspend functions so they can wait for
 * the platform clipboard. These two wrappers keep the ClipData plumbing out of
 * the screens.
 */
suspend fun Clipboard.setPlainText(label: String, text: String) {
    setClipEntry(ClipEntry(ClipData.newPlainText(label, text)))
}

/** Current clipboard contents as text, or null when it holds nothing usable. */
suspend fun Clipboard.readPlainText(): String? {
    val clip = getClipEntry()?.clipData ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0)?.text?.toString()
}
