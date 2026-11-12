package com.tom.musicplayer.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.tom.musicplayer.data.FavoritesStore
import com.tom.musicplayer.data.online.DownloadStore
import com.tom.musicplayer.data.online.LocalPlaylistStore
import com.tom.musicplayer.model.Song
import com.tom.musicplayer.ui.components.AlbumArt
import com.tom.musicplayer.util.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 往一个自建歌单里「添加歌曲」。
 *
 * 歌单以前只能收在线搜到的歌，可用户真正想攒的往往是已经下到手机里、
 * 或者本来就在音乐文件夹里的那些。这里把两份来源合在一起：
 * - 「我的下载」里的记录（文件还在的才算）；
 * - 本地曲库（用户授权过的那个音乐文件夹）。
 *
 * 同一首歌如果既下过、又被曲库扫到，只显示一行，标「已下载」——
 * 按 [FavoritesStore.keyOf] 归一，和点心、歌单去重用的是同一套身份。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSongsScreen(
    playlistId: String,
    playlistName: String,
    library: List<Song>,
    onBack: () -> Unit,
    onDone: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var downloaded by remember { mutableStateOf<List<Song>>(emptyList()) }
    val already = remember(playlistId) { LocalPlaylistStore.keysOf(context, playlistId) }

    // 下载记录里有 SAF 落点，拼 Uri 要读目录树，放 IO 线程免得首帧卡一下
    LaunchedEffect(Unit) {
        downloaded = withContext(Dispatchers.IO) {
            DownloadStore.load(context).mapNotNull { d ->
                DownloadStore.toSong(context, d)
            }
        }
    }

    val downloadedKeys = remember(downloaded) {
        downloaded.mapTo(HashSet()) { FavoritesStore.keyOf(it) }
    }
    // 身份归一靠音源 id；但文件名、Uri 可能因为落点不同而对不上（公共目录 vs SAF），
    // 再拿 Uri 和文件名兜一层，避免同一份文件在「已下载」和「本地」各占一行
    val downloadedUris = remember(downloaded) {
        downloaded.mapTo(HashSet()) { it.uri.toString() }
    }
    val downloadedNames = remember(downloaded) {
        downloaded.mapNotNullTo(HashSet()) {
            it.fileName.takeIf { n -> n.isNotBlank() }?.lowercase()
        }
    }
    val local = remember(library, downloadedKeys, downloadedUris, downloadedNames) {
        library.filter { s ->
            FavoritesStore.keyOf(s) !in downloadedKeys &&
                s.uri.toString() !in downloadedUris &&
                s.fileName.lowercase() !in downloadedNames
        }
    }
    val source: List<Pair<Song, String>> = when (tab) {
        1 -> downloaded.map { it to "已下载" }
        2 -> local.map { it to "本地" }
        else -> downloaded.map { it to "已下载" } + local.map { it to "本地" }
    }
    val visible = remember(source, query) {
        if (query.isBlank()) source
        else source.filter { (s, _) ->
            s.title.contains(query, ignoreCase = true) ||
                s.artist.contains(query, ignoreCase = true) ||
                s.album.contains(query, ignoreCase = true) ||
                s.fileName.contains(query, ignoreCase = true)
        }
    }
    val selectedSongs = remember(picked, source) {
        source.map { it.first }.filter { FavoritesStore.keyOf(it) in picked }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Surface(shadowElevation = 8.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (picked.isEmpty()) "选几首要加的歌" else "已选 ${picked.size} 首",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        enabled = picked.isNotEmpty(),
                        onClick = {
                            val added = LocalPlaylistStore.addSongs(context, playlistId, selectedSongs)
                            onDone(added)
                        }
                    ) { Text(if (picked.isEmpty()) "加入歌单" else "加入 ${picked.size} 首") }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "添加歌曲",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "加到「$playlistName」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜歌名、歌手或文件名") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = tab == 0, onClick = { tab = 0 },
                    label = { Text("全部 ${downloaded.size + local.size}") })
                FilterChip(selected = tab == 1, onClick = { tab = 1 },
                    label = { Text("已下载 ${downloaded.size}") })
                FilterChip(selected = tab == 2, onClick = { tab = 2 },
                    label = { Text("本地 ${local.size}") })
            }
            Spacer(modifier = Modifier.height(6.dp))

            if (visible.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.LibraryMusic,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (query.isBlank()) "这里还没有歌" else "没有匹配的歌曲",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (query.isBlank())
                            "在「在线」页下载几首歌，或者到「我的音乐」里选一个音乐文件夹，\n它们就会出现在这里"
                        else "换个关键词试试",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    items(visible, key = { (s, _) -> FavoritesStore.keyOf(s) + s.id }) { (song, where) ->
                        val key = FavoritesStore.keyOf(song)
                        val inList = key in already
                        val checked = key in picked
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !inList) {
                                    picked = if (checked) picked - key else picked + key
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked || inList,
                                enabled = !inList,
                                onCheckedChange = {
                                    picked = if (checked) picked - key else picked + key
                                }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            AlbumArt(song = song, size = 44.dp, cornerRadius = 10.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = song.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = listOf(song.artist, where)
                                        .filter { it.isNotBlank() }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (inList) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "已在歌单里",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "已加入",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else if (song.durationMs > 0) {
                                Text(
                                    text = formatDuration(song.durationMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
