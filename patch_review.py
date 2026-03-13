with open("app/src/main/java/com/kasumi/tool/MainActivity.kt", "r") as f:
    content = f.read()

# Remove unused imports
content = content.replace("import androidx.compose.material.icons.filled.Refresh\n", "")
content = content.replace("import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState\n", "")

# Fix race condition in setBusy
old_state = "    private var isLoading by mutableStateOf(false)"
new_state = "    private var activeTasksCount by mutableIntStateOf(0)\n    private val isLoading: Boolean get() = activeTasksCount > 0"
content = content.replace(old_state, new_state)

old_set_busy = """    private fun setBusy(busy: Boolean) {
        isLoading = busy
    }"""
new_set_busy = """    private fun setBusy(busy: Boolean) {
        if (busy) activeTasksCount++ else activeTasksCount = (activeTasksCount - 1).coerceAtLeast(0)
    }"""
content = content.replace(old_set_busy, new_set_busy)

with open("app/src/main/java/com/kasumi/tool/MainActivity.kt", "w") as f:
    f.write(content)
