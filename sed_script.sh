sed -i '/onShowSnackbar("Đã xóa ${it.name}")/{n;n;a \
                 if (searchQuery.isNotBlank()) {\
                     item {\
                         Button(\
                             onClick = { performOnlineSearch(searchQuery) },\
                             modifier = Modifier.fillMaxWidth().padding(16.dp),\
                             enabled = !isSearchingOnline\
                         ) {\
                             if (isSearchingOnline) {\
                                 CircularProgressIndicator(modifier = Modifier.size(24.dp))\
                             } else {\
                                 Text("Tìm kiếm online: " + searchQuery)\
                             }\
                         }\
                     }\
                 }\
                 if (playStoreResults.isNotEmpty()) {\
                     item {\
                         Text(\
                             text = "Kết quả từ Play Store",\
                             style = MaterialTheme.typography.titleMedium,\
                             modifier = Modifier.padding(16.dp)\
                         )\
                     }\
                     items(playStoreResults) { item ->\
                         AppItemRow(\
                             item = item,\
                             cacheVersion = 0,\
                             onInstall = { onInstallClicked(it, onShowSnackbar) },\
                             onDelete = {}\
                         )\
                     }\
                 }
}' app/src/main/java/com/kasumi/tool/MainActivity.kt
