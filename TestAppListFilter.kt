import java.util.Comparator

class ApkItem(val name: String, val url: String?)

fun filterAndSortApps(appsList: List<ApkItem>): List<ApkItem> {
    val q = "test"
    val filtered = appsList.filter {
        it.name.contains(q, ignoreCase = true) ||
                (it.url?.contains(q, ignoreCase = true) == true)
    }

    val asc = filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    val desc = filtered.sortedWith(compareBy<ApkItem>(String.CASE_INSENSITIVE_ORDER) { it.name }.reversed())

    return asc + desc
}
