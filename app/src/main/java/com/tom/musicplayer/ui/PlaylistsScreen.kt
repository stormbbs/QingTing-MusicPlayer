package com.tom.musicplayer.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tom.musicplayer.data.ResolvedRef
import com.tom.musicplayer.data.SongRefResolver
import com.tom.musicplayer.data.online.LocalPlaylistStore
import com.tom.musicplayer.model.Song
import com.tom.musicplayer.player.PlayerController
import com.tom.musicplayer.ui.components.AlbumArt
import com.tom.musicplayer.util.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 我的歌单。
 *
 * 本地歌单：每首歌只存身份和展示信息，不存播放地址，换个音源、直链失效都还在。
 * 曲目可以是三种来源 —— 在线搜到的、已经下载的、手机音乐文件夹里本来就有的；
 * 打开详情时按 [SongRefResolver] 统一还原成能播的歌。
 *
 * 列表第一条是固定的「我点心的歌曲」——它不是自建歌单，而是收藏本身，
 * 不允许改名和删除（要删就去里面把心取消掉），所以单独占了最上面一行。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    library: List<Song>,
    /** 当前打开的歌单 id。放在父级，去选歌页再回来时还能停在这个歌单里。 */
    openId: String?,
    onOpenIdChange: (String?) -> Unit,
    onOpenFavorites: () -> Unit,
    onBack: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    /** 打开「往这个歌单里添加歌曲」，参数是歌单 id（父级负责切页和返回）。 */
    onAddSongs: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val playlists = remember(version) { LocalPlaylistStore.load(context) }
    // 收藏可能在别处变（播放页、通知栏），这里跟着变，计数才是真的
    val favorites by PlayerController.favorites.collectAsStateWithLifecycle()
    var createOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }

    val opened = openId?.let { id -> playlists.firstOrNull { it.id == id } }

    // 歌单里存的是「身份」，播之前要认回文件 / 向音源要直链
    var rows by remember { mutableStateOf<List<ResolvedRef>>(emptyList()) }
    LaunchedEffect(opened, library) {
        val entries = opened?.songs ?: emptyList()
        rows = withContext(Dispatchers.IO) {
            SongRefResolver.resolveAll(context, entries, library)
        }
    }
    val playable = rows.mapNotNull { it.song }
    // 每一行在「可播列表」里的下标；文件不在的那行是 -1，点了也不该跳到别的歌
    val playableIndex = ArrayList<Int>(rows.size)
    var cursor = 0
    rows.forEach { r ->
        if (r.song != null) { playableIndex.add(cursor); cursor++ }
        else playableIndex.add(-1)
    }
    val missing = rows.size - playable.size

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (opened != null) onOpenIdChange(null) else onBack() }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
                Text(
                    text = opened?.name ?: "我的歌单",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (opened == null) {
                    IconButton(onClick = { createOpen = true }) {
                        Icon(Icons.Default.Add, contentDescription = "新建歌单")
                    }
                } else {
                    IconButton(onClick = { onAddSongs(opened.id) }) {
                        Icon(Icons.Default.PlaylistAdd, contentDescription = "添加歌曲")
                    }
                    IconButton(onClick = { renameTarget = opened.id; newName = opened.name }) {
                        Icon(Icons.Default.Edit, contentDescription = "重命名")
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // 这里放进来的歌由父级选歌页写入，回来时 version 已经 +1，
            // playlists / opened / rows 会依次重算，不需要额外的刷新信号
            if (opened != null) {
                val entries = opened.songs
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when {
                            entries.isEmpty() -> "还是空的"
                            missing > 0 -> "${entries.size} 首 · $missing 首找不到文件"
                            else -> "${entries.size} 首"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (playable.isNotEmpty()) {
                        TextButton(onClick = { onPlay(playable, 0) }) { Text("播放全部") }
                    }
                    TextButton(onClick = {
                        LocalPlaylistStore.remove(context, opened.id)
                        onOpenIdChange(null)
                        version++
                    }) { Text("删除歌单") }
                }
                Spacer(modifier = Modifier.height(6.dp))

                if (entries.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.PlaylistAdd,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "把歌加进「${opened.name}」",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "已下载的和手机里的歌都能加；\n也可以在「我的下载」或「我的音乐」里长按某首歌。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        TextButton(onClick = { onAddSongs(opened.id) }) {
                            Text("添加歌曲")
                        }
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                        // 解析在 IO 线程跑，首帧 rows 还没就位，别闪一下「文件已不在原处」
                        val resolving = rows.size != entries.size
                        itemsIndexed(entries, key = { _, e -> e.id }) { index, entry ->
                            val r = rows.getOrNull(index)
                            PlaylistSongRow(
                                entry = entry,
                                song = r?.song,
                                where = r?.where.orEmpty(),
                                resolving = resolving,
                                onClick = {
                                    val at = playableIndex.getOrNull(index) ?: -1
                                    if (at >= 0) onPlay(playable, at)
                                },
                                onRemove = {
                                    LocalPlaylistStore.removeSong(context, opened.id, entry.id)
                                    version++
                                }
                            )
                        }
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    // 固定第一条：点心（收藏）本身就是一份歌单，不用先新建再往里加
                    item(key = "favorites") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onOpenFavorites)
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(44.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Favorite,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "我点心的歌曲",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = if (favorites.isEmpty()) "点过心的歌会自动进来"
                                    else "${favorites.size} 首 · 在线和本地都在这里",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (playlists.isEmpty()) {
                        item(key = "empty") {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "还没有自建歌单",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "右上角 + 新建一个，再往里加已下载或手机里的歌",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    items(playlists, key = { it.id }) { p ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenIdChange(p.id) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(44.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = p.name.take(1),
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = p.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${p.songs.size} 首",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = {
                                LocalPlaylistStore.remove(context, p.id)
                                version++
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除歌单")
                            }
                        }
                    }
                }
            }
        }
    }

    if (createOpen) {
        NameDialog(
            title = "新建歌单",
            value = newName,
            onValueChange = { newName = it },
            onDismiss = { createOpen = false; newName = "" },
            onConfirm = {
                LocalPlaylistStore.create(context, it)
                version++
                createOpen = false
                newName = ""
            }
        )
    }

    renameTarget?.let { id ->
        NameDialog(
            title = "重命名",
            value = newName,
            onValueChange = { newName = it },
            onDismiss = { renameTarget = null },
            onConfirm = {
                LocalPlaylistStore.rename(context, id, it)
                version++
                renameTarget = null
            }
        )
    }
}

@Composable
private fun PlaylistSongRow(
    entry: com.tom.musicplayer.data.FavoriteSong,
    song: Song?,
    where: String,
    resolving: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val available = song != null && !resolving
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = available, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumArt(song = song ?: entry.toSong(), size = 46.dp, cornerRadius = 10.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title.ifBlank { entry.fileName.ifBlank { "未知歌曲" } },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (available) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = when {
                    resolving -> "正在找文件…"
                    available -> listOf(entry.artist, where).filter { it.isNotBlank() }
                        .joinToString(" · ").ifBlank { "本地歌曲" }
                    else -> "文件已不在原处，重新扫描曲库后再试"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (available || resolving) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (entry.durationMs > 0) {
            Text(
                text = formatDuration(entry.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, contentDescription = "从歌单移除")
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                placeholder = { Text("歌单名字") },
                shape = RoundedCornerShape(12.dp)
            )
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = { onConfirm(value.trim()) }) {
                Text("确定")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
