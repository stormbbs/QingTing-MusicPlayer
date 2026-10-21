package com.tom.musicplayer.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tom.musicplayer.data.BackupStore
import com.tom.musicplayer.data.FavoritesStore
import com.tom.musicplayer.data.online.LocalPlaylistStore
import com.tom.musicplayer.data.online.OnlineSourceStore
import com.tom.musicplayer.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 数据管理：把收藏、歌单、音源配置导出成一份文件，换手机 / 重装后导回来。
 *
 * 这些东西只在 App 私有目录里，卸载重装、清数据、换设备都是一起没的事后补救。
 * 导出成一个普通 .json，扔进网盘或发给自己都行 —— 不依赖任何账号体系，
 * 这也是这个 App 一直以来的原则：数据在用户自己手里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var withPlaylists by remember { mutableStateOf(true) }
    var withSources by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmSources by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<String?>(null) }

    // 计数直接读盘：这一页不是高频页面，进来读一次就够了
    var version by remember { mutableStateOf(0) }
    val favorites by PlayerController.favorites.collectAsStateWithLifecycle()
    val playlists = remember(version) { LocalPlaylistStore.load(context) }
    val sources = remember(version) { OnlineSourceStore.load(context) }
    val favCount = maxOf(favorites.size, FavoritesStore.entries(context).size)

    // 导出内容先攒着，等用户在系统文件选择器里定好位置再写
    var pendingExport by remember { mutableStateOf<String?>(null) }

    val createLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val text = pendingExport
        pendingExport = null
        if (uri == null || text == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val problem = BackupStore.writeTo(context, uri, text)
            busy = false
            if (problem == null) message = "已导出，文件里包含收藏、歌单和音源配置"
            else error = problem
        }
    }

    fun doExport() {
        if (busy) return
        busy = true
        message = null
        error = null
        scope.launch {
            val content = withContext(Dispatchers.IO) {
                runCatching { BackupStore.build(context, withPlaylists, withSources) }
            }
            busy = false
            content.onSuccess {
                pendingExport = it
                createLauncher.launch(BackupStore.suggestedFileName())
            }
            content.onFailure { error = "导出失败：${it.message ?: "未知原因"}" }
        }
    }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        message = null
        error = null
        scope.launch {
            val text = BackupStore.readFrom(context, uri)
            busy = false
            text.onSuccess { pendingImport = it }
            text.onFailure { error = "读不了这个文件：${it.message ?: "未知原因"}" }
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
                Text(
                    text = "数据管理",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "收藏、歌单、音源配置都只存在这台手机上。换手机或重装之前导一份出来，" +
                    "就能原样搬过去。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(14.dp))

            // ---- 现在有什么 ----
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("当前数据", fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(6.dp))
                    StatRow("点心的歌曲", "$favCount 首")
                    StatRow("自建歌单", "${playlists.size} 个 · 共 ${playlists.sumOf { it.songs.size }} 首")
                    StatRow("音源", "${sources.size} 个")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // ---- 导出 ----
            Text("导出", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(6.dp))
            OptionRow(
                checked = true,
                enabled = false,
                title = "收藏（我点心的歌曲）",
                subtitle = "必选"
            ) { }
            OptionRow(
                checked = withPlaylists,
                title = "自建歌单",
                subtitle = "含歌单名和曲目顺序"
            ) { withPlaylists = it }
            OptionRow(
                checked = withSources,
                title = "音源配置",
                subtitle = "相当于一并备份了密钥，请只存到自己信得过的地方"
            ) { withSources = it }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    // 带密钥导出必须再问一次：用户很可能只是想把歌单搬走
                    if (withSources) confirmSources = true else doExport()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("导出到文件")
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ---- 导入 ----
            Text("导入", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "导入是「合并」，不会清掉现有的东西：同名的歌、同一个歌单只会补齐缺的部分；" +
                    "音源按平台或地址去重，配置以导入的那份为准。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                enabled = !busy,
                onClick = { openLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("从文件导入")
            }

            Spacer(modifier = Modifier.height(12.dp))
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("处理中…", style = MaterialTheme.typography.bodySmall)
                }
            }
            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = "下载记录和曲库缓存不会导出：它们记的是这台手机上的具体文件位置，" +
                    "换到别的设备上没有意义；歌本身在「${
                        com.tom.musicplayer.data.online.Downloader.targetLocation(context)
                    }」里，用文件管理器拷过去即可。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (confirmSources) {
        AlertDialog(
            onDismissRequest = { confirmSources = false },
            title = { Text("连同音源密钥一起导出？") },
            text = {
                Text(
                    "音源配置里含实时密钥，拿到这个文件的人就能用你的额度。" +
                        "只想搬歌单的话，把「音源配置」的勾去掉再导出。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSources = false
                    doExport()
                }) { Text("仍然导出") }
            },
            dismissButton = {
                TextButton(onClick = {
                    withSources = false
                    confirmSources = false
                }) { Text("不带密钥") }
            }
        )
    }

    pendingImport?.let { text ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("导入这份备份？") },
            text = {
                Column {
                    Text("导入会把这些数据并进现有数据，不会删掉任何东西。")
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            pendingImport = null
                            busy = true
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { BackupStore.merge(context, text) }
                                }
                                busy = false
                                result.onSuccess { r ->
                                    if (!r.ok) {
                                        error = r.error
                                    } else {
                                        version++
                                        // 收藏被并进来了，界面上的心形要跟着亮
                                        PlayerController.syncFavorites(context)
                                        message = "导入完成：新增 ${r.favoritesAdded} 首点心、" +
                                            "${r.playlistsAdded} 个歌单" +
                                            (if (r.playlistsMerged > 0) "，补齐 ${r.playlistsMerged} 个" else "") +
                                            (if (r.sourcesAdded > 0) "，${r.sourcesAdded} 个音源有更新" else "")
                                    }
                                }
                                result.onFailure { error = "导入失败：${it.message ?: "文件内容不完整"}" }
                            }
                        }) { Text("开始导入") }
                        TextButton(onClick = { pendingImport = null }) { Text("取消") }
                    }
                }
            },
            confirmButton = {},
        )
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun OptionRow(
    checked: Boolean,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
