import re

with open('app/src/main/java/com/kasumi/tool/MainActivity.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# Modify ScriptsListContent to call the correct methods.
# Wait, loadScriptsFromOnline and loadScriptsFromLocal are private methods of MainActivity.
# ScriptsListContent is a @Composable method inside MainActivity, so it has access to them.
search_text = """        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = {
                scope.launch {
                    setBusy(true)
                    refreshPreloadedApps()
                    setBusy(false)
                    onShowSnackbar("Đã làm mới nguồn")
                }
            },"""

replace_text = """        PullToRefreshBox(
            isRefreshing = isLoading,
            onRefresh = {
                scope.launch {
                    setBusy(true)
                    loadScriptsFromOnline()
                    loadScriptsFromLocal()
                    setBusy(false)
                    onShowSnackbar("Đã làm mới script")
                }
            },"""

content = content.replace(search_text, replace_text)

with open('app/src/main/java/com/kasumi/tool/MainActivity.kt', 'w', encoding='utf-8') as f:
    f.write(content)
