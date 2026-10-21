package com.tom.musicplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tom.musicplayer.model.Song
import com.tom.musicplayer.player.PlayMode
import com.tom.musicplayer.player.PlayerController
import com.tom.musicplayer.ui.components.AlbumArt
import com.tom.musicplayer.util.formatDuration

/**
 * 播放队列。
 *
 * 回答两个问题：「接下来会响哪些歌」（随机播时顺序和列表完全不同），
 * 以及「能不能把想听的那首提上来」。
 *
 * 调整顺序用「上移 / 下移」按钮而不是长按拖动：这首歌可能是一个几十上百首的队列，
 * 长按拖动一旦滑出屏幕就得一直悬在边上等它自己滚，手指抖一下还会掉回去，
 * 而真正想要的常常只是「把这首提到下一首」。按钮点得准，也不用教。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val queue by PlayerController.queue.collectAsStateWithLifecycle()
    val position by PlayerController.queuePosition.collectAsStateWithLifecycle()
    val isPlaying by PlayerController.isPlaying.collectAsStateWithLifecycle()
    val mode by PlayerController.mode.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // 进来先滚到正在播的那首：队列几十首的时候，一屏看不到「现在这首」等于没有队列
    LaunchedEffect(queue.size) {
        if (queue.isNotEmpty()) {
            listState.scrollToItem(position.coerceIn(0, queue.size - 1))
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
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "播放队列",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = when {
                            queue.isEmpty() -> "队列是空的"
                            mode == PlayMode.SHUFFLE -> "${queue.size} 首 · 随机顺序"
                            else -> "${queue.size} 首 · 第 ${position + 1} 首"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (queue.isNotEmpty()) {
                    IconButton(onClick = onOpenPlayer) {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = "回到播放页",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))

            if (queue.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 60.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "还没有在播的歌",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "在「我的音乐」或「在线」里点一首歌，队列就会出现在这里",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Scaffold
            }

            if (mode == PlayMode.SHUFFLE) {
                // 随机播时列表顺序和「点下一首会响什么」是两条线，不说清楚会以为是 bug
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Shuffle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "随机播放中：这里是这一轮实际会播的顺序",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(queue, key = { _, song -> song.id }) { index, song ->
                    QueueRow(
                        song = song,
                        index = index,
                        isCurrent = index == position,
                        isPlaying = isPlaying,
                        canUp = index > 0,
                        canDown = index < queue.size - 1,
                        onClick = { PlayerController.playFromQueue(index) },
                        onUp = { PlayerController.moveInQueue(index, index - 1) },
                        onDown = { PlayerController.moveInQueue(index, index + 1) }
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    song: Song,
    index: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    canUp: Boolean,
    canDown: Boolean,
    onClick: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                else MaterialTheme.colorScheme.background
            )
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 序号 / 正在播的喇叭：一眼看出在队列的哪个位置
        Box(modifier = Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            if (isCurrent) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                    Row(
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (isPlaying) "播放" else "暂停",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            } else {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        AlbumArt(song = song, size = 42.dp, cornerRadius = 10.dp)
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOf(
                    song.artist,
                    if (song.isOnline) "在线" else if (song.isRemote) "WebDAV" else "本地"
                ).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (song.durationMs > 0) {
            Text(
                text = formatDuration(song.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // 上移 / 下移：真正改变「下一首会响什么」，不是只调整显示顺序
        IconButton(onClick = onUp, enabled = canUp, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.KeyboardArrowUp,
                contentDescription = "移到上一首前面",
                tint = if (canUp) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.outlineVariant
            )
        }
        IconButton(onClick = onDown, enabled = canDown, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = "往后挪一位",
                tint = if (canDown) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}
