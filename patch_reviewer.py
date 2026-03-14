import sys

def modify_file(file_path):
    with open(file_path, 'r', encoding='utf-8') as f:
        content = f.read()

    # 1. Remove unused import
    target_import = "import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState\n"
    if target_import in content:
        content = content.replace(target_import, "")

    # 2. Add isRefreshing state
    target_state = "var selectedTab by remember { mutableIntStateOf(0) }"
    replacement_state = "var selectedTab by remember { mutableIntStateOf(0) }\n    var isRefreshing by remember { mutableStateOf(false) }"
    if target_state in content:
        content = content.replace(target_state, replacement_state)

    # 3. Update PullToRefreshBox and AnimatedVisibility
    target_loading = """            Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                AnimatedVisibility(visible = isLoading) {"""
    replacement_loading = """            Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                AnimatedVisibility(visible = isLoading && !isRefreshing) {"""
    if target_loading in content:
        content = content.replace(target_loading, replacement_loading)

    # 4. Update the onRefresh logic
    target_refresh = """                PullToRefreshBox(
                    isRefreshing = isLoading,
                    onRefresh = {
                        lifecycleScope.launch {
                            setBusy(true)
                            try {
                                if (selectedTab == 0) {
                                    refreshPreloadedApps()
                                } else {
                                    loadScriptsFromOnline()
                                    loadScriptsFromLocal()
                                }
                            } finally {
                                setBusy(false)
                            }
                            snackbarHostState.showSnackbar("Đã làm mới nguồn")
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {"""

    replacement_refresh = """                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = {
                        lifecycleScope.launch {
                            isRefreshing = true
                            try {
                                if (selectedTab == 0) {
                                    refreshPreloadedApps()
                                } else {
                                    loadScriptsFromOnline()
                                    loadScriptsFromLocal()
                                }
                                snackbarHostState.showSnackbar("Đã làm mới nguồn")
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("Lỗi làm mới: ${e.message}")
                            } finally {
                                isRefreshing = false
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {"""

    if target_refresh in content:
        content = content.replace(target_refresh, replacement_refresh)

    with open(file_path, 'w', encoding='utf-8') as f:
        f.write(content)
    print("Success fixing code review comments")

modify_file('app/src/main/java/com/kasumi/tool/MainActivity.kt')
