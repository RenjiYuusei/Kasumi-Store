package com.kasumi.tool

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
fun KasumiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFBB86FC),
            secondary = Color(0xFF03DAC6),
            tertiary = Color(0xFF3700B3)
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    appVersion: String,
    items: List<ApkItem>,
    scriptItems: List<ScriptItem>,
    isLoading: (String) -> Boolean,
    onRefresh: () -> Unit,
    onSort: () -> Unit,
    onInstall: (ApkItem) -> Unit,
    onDeleteApp: (ApkItem) -> Unit,
    onDownloadScript: (ScriptItem) -> Unit,
    onCopyScript: (ScriptItem) -> Unit,
    onDeleteScript: (ScriptItem) -> Unit,
    isBusy: Boolean
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Kasumi-Store v$appVersion") },
                actions = {
                    if (selectedTab == 0) {
                        IconButton(onClick = onSort) {
                            Icon(Icons.Default.Sort, contentDescription = "Sắp xếp")
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Apps, contentDescription = null) },
                    label = { Text("Ứng dụng") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Code, contentDescription = null) },
                    label = { Text("Script") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            if (isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            SearchBar(
                query = searchQuery,
                onQueryChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            )

            when (selectedTab) {
                0 -> AppList(
                    items = items.filter { it.name.contains(searchQuery, ignoreCase = true) },
                    isLoading = isLoading,
                    onInstall = onInstall,
                    onDelete = onDeleteApp
                )
                1 -> ScriptList(
                    items = scriptItems.filter { it.name.contains(searchQuery, ignoreCase = true) },
                    onDownload = onDownloadScript,
                    onCopy = onCopyScript,
                    onDelete = onDeleteScript
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = { Text("Tìm kiếm...") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
        colors = TextFieldDefaults.textFieldColors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        )
    )
}

@Composable
fun AppList(
    items: List<ApkItem>,
    isLoading: (String) -> Boolean,
    onInstall: (ApkItem) -> Unit,
    onDelete: (ApkItem) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(items, key = { it.id }) { item ->
            AppItemCard(
                item = item,
                loading = isLoading(item.id),
                onInstall = { onInstall(item) },
                onDelete = { onDelete(item) }
            )
        }
    }
}

@Composable
fun AppItemCard(
    item: ApkItem,
    loading: Boolean,
    onInstall: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(item.iconUrl)
                    .crossfade(true)
                    .build(),
                placeholder = painterResource(R.drawable.ic_kasumi),
                error = painterResource(R.drawable.ic_kasumi),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(64.dp)
                    .background(Color.Gray, shape = MaterialTheme.shapes.small)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.versionName != null) {
                    Text(
                        text = "v${item.versionName}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                IconButton(onClick = onInstall) {
                    Icon(Icons.Default.InstallMobile, contentDescription = "Cài đặt")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Xóa")
                }
            }
        }
    }
}

@Composable
fun ScriptList(
    items: List<ScriptItem>,
    onDownload: (ScriptItem) -> Unit,
    onCopy: (ScriptItem) -> Unit,
    onDelete: (ScriptItem) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(items, key = { it.id }) { item ->
            ScriptItemCard(
                item = item,
                onDownload = { onDownload(item) },
                onCopy = { onCopy(item) },
                onDelete = { onDelete(item) }
            )
        }
    }
}

@Composable
fun ScriptItemCard(
    item: ScriptItem,
    onDownload: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = item.gameName,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            IconButton(onClick = onDownload) {
                Icon(Icons.Default.Download, contentDescription = "Tải về")
            }
            IconButton(onClick = onCopy) {
                Icon(Icons.Default.FileCopy, contentDescription = "Sao chép")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Xóa")
            }
        }
    }
}
