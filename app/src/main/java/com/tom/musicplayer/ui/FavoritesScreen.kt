package com.tom.musicplayer.ui

import android.content.Context
import android.net.Uri
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.tom.musicplayer.data.FavoriteSong
import com.tom.musicplayer.data.FavoritesStore
import com.tom.musicplayer.data.online.DownloadStore
import com.tom.musicplayer.model.Song
import com.tom.musicplayer.player.PlayerController
import com.tom.musicplayer.ui.components.AlbumArt
import com.tom.musicplayer.util.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「我点心的歌曲」。
 *
 * 点一下心形就往这里加一条，不分手动下载的还是在线听的。
 * 列表里的东西不是另存一份：它就是收藏本身，取消点心这里就同步少一条。
 *
 * 难点在「还原成能播的歌」：收藏只记身份，本地文件可能被挪走、
 * 在线歌需要重新向音源解析直链，所以进来时先把每条都解析一遍（见 [resolve]），
 * 解析不出来的标成「文件已不在」，而不是点了没反应。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    library: List<Song>,
    onBack: () -> Unit,
    onPlay: (List<Song>, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val favorites by PlayerController.favorites.collectAsStateWithLifecycle()

    // 只显示还在收藏里的：在别处（通知栏、列表）取消点心后，这里跟着消失
    val entries = remember(favorites) {
        FavoritesStore.entries(context).filter { it.id in favorites }
    }

    var rows by remember { mutableStateOf<List<FavoriteRow>>(emptyList()) }
    LaunchedEffect(entries, library) {
        rows = withContext(Dispatchers.IO) { resolve(context, entries, library) }
    }

    val playable = rows.filter { it.song != null }
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
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "我点心的歌曲",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when {
                        rows.isEmpty() -> "点过心的歌都会在这里"
                        missing > 0 -> "${rows.size} 首 · $missing 首找不到文件"
                        else -> "${rows.size} 首"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (playable.isNotEmpty()) {
                    TextButton(onClick = {
                        onPlay(playable.mapNotNull { it.song }, 0)
                    }) { Text("播放全部") }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))

            if (rows.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "还没有点心的歌曲",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "在播放页、通知栏或者歌曲列表里点一下心形，\n" +
                            "不管是在线的还是已经下载的，都会进这个歌单",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(rows, key = { it.key }) { row ->
                        FavoriteRowItem(
                            row = row,
                            onClick = {
                                if (row.song != null) {
                                    // 播放列表就是眼前这份（已解析的），下标按它算
                                    val index = playable.indexOfFirst { it.key == row.key }
                                    onPlay(playable.mapNotNull { it.song }, index.coerceAtLeast(0))
                                }
                            },
                            onRemove = {
                                // 一行可能是两条记录合并来的（同一份文件在下载页和曲库各点心过一次），
                                // 所以按 key 批量删，不能逐条 toggle —— 第二条会被当成「再加一次」
                                PlayerController.removeFavorites(row.entries.map { it.id })
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 歌单里的一行。
 *
 * 一行可能对应好几条收藏记录：同一份文件既可能从「我的下载」点心过，
 * 也可能从曲库点心过（两边记的 id 不一样）。显示时合并成一行，
 * 取消时把整组一起删掉，否则删一条会留下另一条。
 */
data class FavoriteRow(
    val entries: List<FavoriteSong>,
    /** 用来展示信息的那条（优先能播的、已下载的）。 */
    val entry: FavoriteSong,
    val song: Song?,
    /** 在线 / 已下载 / 本地。 */
    val where: String
) {
    val key: String get() = entries.joinToString("|") { it.id }
    val title: String
        get() = entry.title.ifBlank { entry.fileName.ifBlank { "未知歌曲" } }
    val subtitle: String
        get() = listOf(entry.artist, entry.album)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifBlank { if (entry.hasOnlineIdentity) "在线歌曲" else "本地文件" }
}

@Composable
private fun FavoriteRowItem(
    row: FavoriteRow,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val available = row.song != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = available, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumArt(song = row.song ?: row.entry.toSong(), size = 46.dp, cornerRadius = 10.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (available) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (available) listOf(row.subtitle, row.where).joinToString(" · ")
                else "文件已不在原处，重新扫描曲库后再试",
                style = MaterialTheme.typography.bodySmall,
                color = if (available) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (row.entry.durationMs > 0) {
            Text(
                text = formatDuration(row.entry.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Default.Favorite,
                contentDescription = "取消点心",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

/**
 * 把收藏记录还原成能播的歌。
 *
 * 优先用「活着的」那一份：
 * - 有音源身份的歌，先找下载记录 —— 下过就用本地文件，不用再花流量；
 * - 本地歌去曲库里按 Uri 找，Uri 变了（换过文件夹）就按文件名找；
 * - 都找不到时最后试一次记录里的老 Uri，读得开才算还在。
 */
private fun resolve(
    context: Context,
    entries: List<FavoriteSong>,
    library: List<Song>
): List<FavoriteRow> {
    val downloads = runCatching { DownloadStore.load(context) }.getOrDefault(emptyList())
    val byKey = library.associateBy { FavoritesStore.keyOf(it) }
    val byName = library.filter { it.fileName.isNotBlank() }.associateBy { it.fileName }

    // 先逐条解析，再按「同一首歌」分组
    val groups = LinkedHashMap<String, MutableList<FavoriteRow>>()
    entries.forEach { entry ->
        val row = resolveOne(context, entry, downloads, byKey, byName)
        groups.getOrPut(groupKey(entry)) { ArrayList(1) }.add(row)
    }

    return groups.values.map { list ->
        // 能播的那条做代表；同样是能播，优先本地文件
        val best = list.firstOrNull { it.where == "已下载" && it.song != null }
            ?: list.firstOrNull { it.song != null }
            ?: list.first()
        FavoriteRow(
            entries = list.map { it.entry },
            entry = best.entry,
            song = best.song,
            where = best.where
        )
    }
}

/** 分组的键：在线歌按音源身份，本地文件按文件名（下载记录和曲库扫描同一份文件）。 */
private fun groupKey(entry: FavoriteSong): String = when {
    entry.hasOnlineIdentity -> "online:${entry.onlineSourceId}:${entry.onlineSongId}"
    entry.fileName.isNotBlank() -> "file:${entry.fileName.lowercase()}"
    else -> "id:${entry.id}"
}

private fun resolveOne(
    context: Context,
    entry: FavoriteSong,
    downloads: List<com.tom.musicplayer.data.online.DownloadedSong>,
    byKey: Map<String, Song>,
    byName: Map<String, Song>
): FavoriteRow {
    // 下载过的那一份：在线歌按「音源:歌曲」找，下载记录自己的 id 也认
    // （从「我的下载」点心时，收藏 key 就是下载记录的 id）
    val saved = downloads.firstOrNull {
        (entry.hasOnlineIdentity &&
            it.sourceId == entry.onlineSourceId && it.songId == entry.onlineSongId) ||
            it.id == entry.id
    }?.let { DownloadStore.toSong(context, it) }

    val row = when {
        saved != null -> FavoriteRow(listOf(entry), entry, saved, "已下载")
        entry.hasOnlineIdentity -> {
            // 没下载过就按在线播：直链在要播的时候才向音源解析
            val inLibrary = byKey[entry.id]
            if (inLibrary != null) FavoriteRow(listOf(entry), entry, inLibrary, "已下载")
            else FavoriteRow(listOf(entry), entry, entry.toSong(), "在线")
        }
        else -> {
            val inLibrary = byKey[entry.id]
                ?: entry.fileName.takeIf { it.isNotBlank() }?.let { byName[it] }
            if (inLibrary != null) {
                FavoriteRow(listOf(entry), entry, inLibrary, "本地")
            } else {
                // 曲库里没有，就试试记录里的老 Uri；读得开才算还在
                val uri = entry.uri?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                val ok = uri != null && readable(context, uri)
                FavoriteRow(listOf(entry), entry, if (ok) entry.toSong() else null, "本地")
            }
        }
    }
    return row
}

/** 老 Uri 还能不能读：内容 Uri 真去开一次，文件 Uri 看文件在不在。 */
private fun readable(context: Context, uri: Uri): Boolean = runCatching {
    when (uri.scheme) {
        "content" -> context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        "file" -> File(uri.path.orEmpty()).exists()
        else -> true
    }
}.getOrDefault(false)
