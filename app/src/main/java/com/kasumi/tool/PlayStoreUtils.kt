package com.kasumi.tool

import org.jsoup.Jsoup
import java.util.UUID

object PlayStoreUtils {

    fun searchPlayStore(query: String): List<ApkItem> {
        val url = "https://play.google.com/store/search?q=" + query + "&c=apps&hl=vi"
        val results = mutableListOf<ApkItem>()
        val seenPackages = mutableSetOf<String>()

        try {
            val doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .timeout(10000)
                .get()

            // Find all unique app links
            val links = doc.select("a[href^=/store/apps/details?id=]")

            for (link in links) {
                val href = link.attr("href")
                val packageName = href.substringAfter("id=").substringBefore("&")

                if (packageName in seenPackages) continue
                seenPackages.add(packageName)

                var title = link.text()
                var iconUrl = ""

                // Try to find image inside the link
                var img = link.select("img").first()
                if (img != null) {
                    iconUrl = img.attr("data-src").ifEmpty { img.attr("src") }
                } else {
                    // Try to find image in the parent container (likely a card)
                    // Go up to a container (usually div) and search down for img
                    val container = link.parent()?.parent()
                    if (container != null) {
                        val parentImg = container.select("img").first()
                        if (parentImg != null) {
                             iconUrl = parentImg.attr("data-src").ifEmpty { parentImg.attr("src") }
                        }
                    }
                }

                if (iconUrl.startsWith("//")) {
                    iconUrl = "https:" + iconUrl
                }

                // Improve title extraction
                if (title.isBlank()) {
                    title = link.attr("title")
                }
                // If title is just the package name or empty, look for siblings
                if (title.isBlank() || title == packageName) {
                     val container = link.parent()
                     if (container != null) {
                         // Find text nodes or other elements
                         val text = container.text()
                         if (text.isNotBlank()) title = text
                     }
                }

                // Final fallback
                if (title.isBlank()) title = packageName

                // Clean title if it contains newlines or is too long
                if (title.length > 50) {
                    title = title.substring(0, 50) + "..."
                }

                results.add(
                    ApkItem(
                        id = packageName,
                        name = title,
                        sourceType = SourceType.PLAY_STORE,
                        url = "market://details?id=" + packageName,
                        uri = null,
                        versionName = null,
                        versionCode = null,
                        iconUrl = if (iconUrl.isNotEmpty()) iconUrl else null
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return results
    }
}
