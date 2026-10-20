package com.tom.musicplayer.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tom.musicplayer.data.SourceStore
import com.tom.musicplayer.data.webdav.WebDavClient
import com.tom.musicplayer.data.webdav.WebDavEntry
import com.tom.musicplayer.data.webdav.WebDavScanner
import com.tom.musicplayer.model.Song
import com.tom.musicplayer.model.WebDavServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 网络音乐入口：先列出配置好的 WebDAV 服务器，点进去就是远端目录浏览。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    onBack: () -> Unit,
    onPlayRemote: (List<Song>, Int) -> Unit
) {
    val context = LocalContext.current
    var servers by remember { mutableStateOf(SourceStore.load(context)) }
    var browsing by remember { mutableStateOf<WebDavServer?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    val target = browsing
    if (target != null) {
        WebDavBrowserScreen(
            server = target,
            onBack = { browsing = null },
            onPlayRemote = onPlayRemote
        )
        return
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("网络音乐") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = "添加服务器")
            }
        }
    ) { padding ->
        if (servers.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 36.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Box(modifier = Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Cloud, null, modifier = Modifier.size(52.dp))
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text("还没有网络音乐服务器", fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "添加一台 WebDAV 服务器（比如你在 VPS 上搭的那个），就能直接边听边缓存。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(22.dp))
                Button(onClick = { showAdd = true }, shape = RoundedCornerShape(14.dp)) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("添加服务器")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(servers, key = { it.id }) { server ->
                    ServerCard(
                        server = server,
                        onClick = { browsing = server },
                        onDelete = { servers = SourceStore.remove(context, server.id) }
                    )
                }
            }
        }
    }

    if (showAdd) {
        ServerEditDialog(
            onDismiss = { showAdd = false },
            onSave = { server ->
                servers = SourceStore.add(context, server)
                showAdd = false
            }
        )
    }
}

@Composable
private fun ServerCard(
    server: WebDavServer,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Cloud, null, modifier = Modifier.size(24.dp))
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(server.name, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(2.dp))
                Text(server.baseUrl, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "删除服务器")
            }
        }
    }
}

@Composable
private fun ServerEditDialog(
    onDismiss: () -> Unit,
    onSave: (WebDavServer) -> Unit
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rootPath by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    fun draft(): WebDavServer = WebDavServer(
        id = UUID.randomUUID().toString(),
        name = name.ifBlank { runCatching { java.net.URL(url).host }.getOrDefault("WebDAV") },
        baseUrl = url.trim().trimEnd('/'),
        user = user.trim(),
        password = password,
        rootPath = SourceStore.normalizePath(rootPath)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加 WebDAV 服务器") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称（便于自己辨认）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = url, onValueChange = { url = it; testResult = null },
                    label = { Text("服务器地址") },
                    placeholder = { Text("https://example.com/webdav") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = user, onValueChange = { user = it; testResult = null },
                    label = { Text("用户名（可留空）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = password, onValueChange = { password = it; testResult = null },
                    label = { Text("密码（可留空）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = rootPath, onValueChange = { rootPath = it },
                    label = { Text("音乐所在目录（可留空）") },
                    placeholder = { Text("/music") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                testResult?.let { result ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = result,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (result.startsWith("连接成功"))
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = url.isNotBlank() && !testing,
                onClick = {
                    testing = true
                    testResult = null
                    scope.launch {
                        val server = draft()
                        val result = WebDavClient.test(server)
                        testing = false
                        if (result.isSuccess) {
                            onSave(server)
                        } else {
                            testResult = result.exceptionOrNull()?.message ?: "连接失败"
                        }
                    }
                }
            ) {
                Text(if (testing) "测试中…" else "测试并保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebDavBrowserScreen(
    server: WebDavServer,
    onBack: () -> Unit,
    onPlayRemote: (List<Song>, Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stack by remember { mutableStateOf(listOf(server.rootPath)) }
    val path = stack.last()
    var entries by remember { mutableStateOf<List<WebDavEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }

    fun load(target: String) {
        scope.launch {
            loading = true
            error = null
            entries = null
            val result = WebDavClient.list(server, target)
            loading = false
            if (result.isSuccess) {
                entries = result.getOrDefault(emptyList())
                if (result.getOrDefault(emptyList()).isEmpty()) error = "这个目录是空的"
            } else {
                error = result.exceptionOrNull()?.message ?: "读取失败"
            }
        }
    }

    fun playEntireDirectory() {
        scope.launch {
            scanning = true
            val songs = withContext(Dispatchers.IO) {
                WebDavScanner.scanTree(server, path)
            }
            scanning = false
            if (songs.isEmpty()) {
                Toast.makeText(context, "这个目录里没有找到音频文件", Toast.LENGTH_SHORT).show()
            } else {
                onPlayRemote(songs, 0)
            }
        }
    }

    fun playOneFile(entry: WebDavEntry) {
        scope.launch {
            val songs = WebDavScanner.listSongs(server, path)
            val index = songs.indexOfFirst { it.remotePath == entry.path }
            if (index >= 0) {
                onPlayRemote(songs, index)
            } else {
                Toast.makeText(context, "打开失败，试试「播放整个目录」", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(stack) { load(path) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(server.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (stack.size > 1) {
                            stack = stack.dropLast(1)
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { playEntireDirectory() }, enabled = !scanning) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("播放整个目录")
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val list = entries
            when {
                loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                list == null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = error ?: "无法打开这个目录",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = { load(path) }, shape = RoundedCornerShape(14.dp)) {
                            Text("重试")
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        items(list, key = { it.path }) { entry ->
                            EntryRow(
                                entry = entry,
                                onClick = {
                                    if (entry.isDirectory) {
                                        stack = stack + entry.path
                                    } else {
                                        playOneFile(entry)
                                    }
                                }
                            )
                        }
                    }
                }
            }
            if (scanning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
}

@Composable
private fun EntryRow(entry: WebDavEntry, onClick: () -> Unit) {
    val isAudio = !entry.isDirectory
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isAudio) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (isAudio) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Box(modifier = Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isAudio) Icons.Default.AudioFile else Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium)
                if (isAudio && entry.sizeBytes > 0) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(formatSize(entry.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val mb = bytes / 1024f / 1024f
    return if (mb >= 1f) String.format("%.1f MB", mb) else String.format("%d KB", bytes / 1024)
}
