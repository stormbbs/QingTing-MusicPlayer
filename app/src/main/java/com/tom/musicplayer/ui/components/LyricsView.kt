package com.tom.musicplayer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tom.musicplayer.model.Lyrics
import kotlinx.coroutines.delay

/**
 * 滚动歌词：当前行放大高亮并自动居中，点某一行跳到对应进度。
 *
 * 自动滚动会在用户手动滑动时让路（[androidx.compose.foundation.lazy.LazyListState.isScrollInProgress]），
 * 否则用户一边找歌词，一边被程序不停拽回当前行。
 */
@Composable
fun LyricsView(
    lyrics: Lyrics?,
    positionMs: Long,
    loading: Boolean,
    onSeek: (Long) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (lyrics == null || lyrics.isEmpty) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                    Text("正在寻找歌词…", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        "这首歌还没找到歌词",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(onClick = onSearch) { Text("手动搜索歌词") }
                }
            }
        }
        return
    }

    val played = positionMs - lyrics.offsetMs
    var activeIndex = 0
    for ((i, line) in lyrics.lines.withIndex()) {
        if (line.timeMs <= played) activeIndex = i else break
    }
    val listState = rememberLazyListState()

    // 「用户正在翻歌词」必须靠 snapshotFlow 观察：之前在 BoxWithConstraints 的内容里直接读
    // isScrollInProgress 并写进 state，那段代码只在子组合（测量阶段）跑一次，标记会被永久
    // 锁死成 true，之后当前行再也不会自动滚过去——表现为歌词完全不动。
    var autoScrolling by remember { mutableStateOf(false) }
    var userScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !autoScrolling) userScrolling = true
            else if (!scrolling) userScrolling = false
        }
    }

    LaunchedEffect(activeIndex) {
        // 用户在翻歌词时先让路，等他停手再跟回当前行
        while (userScrolling) delay(250)
        autoScrolling = true
        runCatching { listState.animateScrollToItem(activeIndex) }
        autoScrolling = false
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val topPad = (maxHeight.value * 0.34f).dp
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = topPad),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            itemsIndexed(lyrics.lines, key = { index, _ -> index }) { index, line ->
                val active = index == activeIndex
                Text(
                    text = line.text,
                    textAlign = TextAlign.Center,
                    lineHeight = 28.sp,
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    fontSize = if (active) 19.sp else 16.sp,
                    modifier = Modifier
                        .padding(horizontal = 18.dp, vertical = 7.dp)
                        .clickable { onSeek(line.timeMs + lyrics.offsetMs) }
                )
            }
        }
    }
}
