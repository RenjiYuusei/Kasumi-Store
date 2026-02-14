import re

file_path = 'app/src/main/java/com/kasumi/tool/MainActivity.kt'

with open(file_path, 'r') as f:
    content = f.read()

# Pattern to find the insertion point
pattern = r'(onShowSnackbar\("Đã xóa $\{it\.name\}"\)\s+\}\)\s+)\}'

replacement = r'''\1}

                 if (searchQuery.isNotBlank()) {
                     item {
                         Button(
                             onClick = { performOnlineSearch(searchQuery) },
                             modifier = Modifier.fillMaxWidth().padding(16.dp),
                             enabled = !isSearchingOnline
                         ) {
                             if (isSearchingOnline) {
                                 CircularProgressIndicator(modifier = Modifier.size(24.dp))
                             } else {
                                 Text("Tìm kiếm online: " + searchQuery)
                             }
                         }
                     }
                 }

                 if (playStoreResults.isNotEmpty()) {
                     item {
                         Text(
                             text = "Kết quả từ Play Store",
                             style = MaterialTheme.typography.titleMedium,
                             modifier = Modifier.padding(16.dp)
                         )
                     }
                     items(playStoreResults) { item ->
                         AppItemRow(
                             item = item,
                             cacheVersion = 0,
                             onInstall = { onInstallClicked(it, onShowSnackbar) },
                             onDelete = {}
                         )
                     }
                 }'''

new_content = re.sub(pattern, replacement, content, count=1)

with open(file_path, 'w') as f:
    f.write(new_content)
