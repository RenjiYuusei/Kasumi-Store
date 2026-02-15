package com.kasumi.tool

/**
 * Pure function to filter and sort apps based on query and sort mode.
 * Extracted from MainActivity to allow background execution and testing.
 */
fun filterAndSortApps(
    appsList: List<ApkItem>,
    searchQuery: String,
    sortMode: SortMode,
    fileStats: Map<String, FileStats>
): List<ApkItem> {
    val q = searchQuery.trim().lowercase()
    val filtered = if (q.isEmpty()) {
        appsList
    } else {
        appsList.filter {
            it.name.lowercase().contains(q) ||
                    (it.url?.lowercase()?.contains(q) == true)
        }
    }

    return when (sortMode) {
        SortMode.NAME_ASC -> filtered.sortedBy { it.name.lowercase() }
        SortMode.NAME_DESC -> filtered.sortedByDescending { it.name.lowercase() }
        SortMode.SIZE_DESC -> filtered.sortedByDescending {
             fileStats[it.id]?.size ?: 0L
        }
        SortMode.DATE_DESC -> filtered.sortedByDescending {
             fileStats[it.id]?.lastModified ?: 0L
        }
    }
}
