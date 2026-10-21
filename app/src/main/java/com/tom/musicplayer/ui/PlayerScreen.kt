package com.tom.musicplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.style.TextOverflow
import com.tom.musicplayer.model.SleepState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.tom.musicplayer.data.lyrics.LyricsRepository
import com.tom.musicplayer.model.LyricCandidate
import com.tom.musicplayer.model.LyricSource
import com.tom.musicplayer.model.Lyrics
import com.tom.musicplayer.model.Song
import com.tom.musicplayer.player.PlayMode
import com.tom.musicplayer.player.NotificationActions
import com.tom.musicplayer.player.PlayerController
import com.tom.musicplayer.ui.components.AlbumArt
import com.tom.musicplayer.ui.components.LyricsView
import com.tom.musicplayer.ui.theme.MintContainer
import com.tom.musicplayer.util.formatDuration
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun PlayerScreen(
    song: Song,
    isPlaying: Boolean,
    isFavorite: Boolean,
    mode: PlayMode,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSelectMode: (PlayMode) -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    /** 当前这首能不能下载 / 下到哪一步了。本地歌为 null，界面上就不出现下载键。 */
    downloadState: NotificationActions.DownloadState? = null,
    /** 下载进度 0..100，未知大小时为 -1。 */
    downloadProgress: Int = -1,
    onDownload: () -> Unit = {}
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lyrics by remember(song.id) { mutableStateOf<Lyrics?>(null) }
    var lyricsLoading by remember(song.id) { mutableStateOf(true) }
    var showLyrics by remember(song.id) { mutableStateOf(false) }
    var showLyricSearch by remember { mutableStateOf(false) }

    // 切歌时重新取歌词：自带的 .lrc → 本地已下载 → 在线搜索
    LaunchedEffect(song.id) {
        lyricsLoading = true
        lyrics = runCatching { LyricsRepository.load(context, song) }
            .getOrDefault(Lyrics(emptyList(), LyricSource.NONE))
        lyricsLoading = false
        showLyrics = lyrics?.isEmpty == false
    }

    val sleep by PlayerController.sleep.collectAsStateWithLifecycle()
    var showTimerMenu by remember { mutableStateOf(false) }

    // 进度由 PlayerController 统一以流的形式下发，界面只做展示：
    // 自己开轮询 + remember(song.id) 存状态时，切歌会换掉状态对象而协程还在写旧对象，
    // 结果是切歌后进度条和歌词永远停在 0（拖动也会立刻弹回）。
    val tick by PlayerController.progress.collectAsStateWithLifecycle()
    // 切歌瞬间播放器可能还报着上一首的位置，mediaId 对不上就先按 0 显示，避免进度条闪一下
    val rawPosition = if (tick.mediaId == song.id) tick.positionMs else 0L
    // 远端曲目刚起播时播放器还不知道时长，先退回元数据里的时长
    val duration = (if (tick.mediaId == song.id && tick.durationMs > 0) tick.durationMs else song.durationMs)
        .coerceAtLeast(0L)
    val position = if (duration > 0) rawPosition.coerceIn(0L, duration) else rawPosition
    val progress = if (duration > 0) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
    val config = LocalConfiguration.current
    // 同时受宽度和高度约束：矮屏 / 大字体时封面自动缩小，保证底部播放模式一定不被挤出屏幕
    val artSize = min(config.screenWidthDp.dp * 0.62f, config.screenHeightDp.dp * 0.32f).let {
        min(it, 280.dp)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回列表")
                }
                Text(
                    text = "正在播放",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                // 听得顺手就收藏：放在标题栏右侧，拇指不用去找列表里的那颗心形
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (isFavorite) "取消点心" else "点心这首歌",
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 下载放在收藏旁边：有些歌是听了一会儿才决定要留下的，
                // 不用退回列表、也不用去「我的下载」页再找一遍
                if (downloadState != null) {
                    DownloadAction(
                        state = downloadState,
                        progress = downloadProgress,
                        onClick = onDownload
                    )
                }
                Box {
                    if (sleep.active) {
                        TextButton(onClick = { showTimerMenu = true }) {
                            Icon(
                                Icons.Default.Timer,
                                contentDescription = "定时停止：${timerLabel(sleep)}",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(timerLabel(sleep), style = MaterialTheme.typography.labelLarge)
                        }
                    } else {
                        IconButton(onClick = { showTimerMenu = true }) {
                            Icon(Icons.Default.Timer, contentDescription = "定时停止")
                        }
                    }
                    DropdownMenu(
                        expanded = showTimerMenu,
                        onDismissRequest = { showTimerMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("不定时") },
                            onClick = {
                                showTimerMenu = false
                                scope.launch { PlayerController.cancelSleepTimer() }
                            }
                        )
                        listOf(15, 30, 45, 60).forEach { minutes ->
                            DropdownMenuItem(
                                text = { Text("$minutes 分钟后停止") },
                                onClick = {
                                    showTimerMenu = false
                                    scope.launch { PlayerController.setSleepMinutes(minutes) }
                                }
                            )
                        }
                        listOf(1, 3, 5).forEach { tracks ->
                            DropdownMenuItem(
                                text = { Text("再播完 $tracks 首就停") },
                                onClick = {
                                    showTimerMenu = false
                                    scope.launch { PlayerController.setSleepTracks(tracks) }
                                }
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                ArtOrLyrics(
                    song = song,
                    artSize = artSize,
                    showLyrics = showLyrics,
                    onShowLyricsChange = { showLyrics = it },
                    lyrics = lyrics,
                    loading = lyricsLoading,
                    positionMs = position,
                    onSeek = { target -> scope.launch { PlayerController.seekTo(target) } },
                    onSearchLyrics = { showLyricSearch = true },
                    onAdjustOffset = { deltaMs ->
                        val current = lyrics ?: return@ArtOrLyrics
                        val next = (current.offsetMs + deltaMs).coerceIn(-10_000L, 10_000L)
                        lyrics = current.copy(offsetMs = next)
                        scope.launch {
                            LyricsRepository.saveOffset(context, song.id, next)
                        }
                    }
                )
                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = song.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(24.dp))

                Slider(
                    value = if (dragging) dragValue else progress,
                    onValueChange = {
                        dragging = true
                        dragValue = it
                    },
                    onValueChangeFinished = {
                        dragging = false
                        PlayerController.seekTo((dragValue * duration).toLong())
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = formatDuration(if (dragging) (dragValue * duration).toLong() else position),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = formatDuration(duration),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    IconButton(onClick = onPrevious, modifier = Modifier.size(56.dp)) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = "上一首",
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    FloatingActionButton(
                        onClick = onTogglePlay,
                        modifier = Modifier.size(76.dp),
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "暂停" else "播放",
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "下一首",
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(22.dp))

                // 播放模式：标题 + 当前模式全称，四项按钮不会藏在屏幕外
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "播放模式",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = modeIcon(mode),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = mode.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                PlayModeRow(mode = mode, onSelect = onSelectMode)

                Spacer(modifier = Modifier.height(20.dp))
            }
        }

        if (showLyricSearch) {
            LyricSearchDialog(
                song = song,
                onDismiss = { showLyricSearch = false },
                onPick = { candidate ->
                    scope.launch {
                        lyrics = LyricsRepository.applyCandidate(context, song, candidate)
                    }
                    showLyricSearch = false
                    showLyrics = true
                }
            )
        }
    }
}

/**
 * 播放页标题栏上的下载键，三种样子：
 * - 能下：空心箭头；正在下：绕圈的进度环；下过了：勾，颜色提亮表示可以在「我的下载」里找到。
 *
 * 尺寸固定 48dp（IconButton 默认触摸区域），换状态时标题栏不会跟着抖。
 */
@Composable
private fun DownloadAction(
    state: NotificationActions.DownloadState,
    progress: Int,
    onClick: () -> Unit
) {
    when (state) {
        NotificationActions.DownloadState.BUSY -> Box(
            modifier = Modifier.size(48.dp),
            contentAlignment = Alignment.Center
        ) {
            if (progress in 0..100) {
                CircularProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.5.dp
                )
            } else {
                // 服务器没给总大小，只能转圈
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.5.dp
                )
            }
        }
        NotificationActions.DownloadState.DONE -> IconButton(onClick = onClick) {
            Icon(
                Icons.Default.DownloadDone,
                contentDescription = "已下载",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        NotificationActions.DownloadState.READY -> IconButton(onClick = onClick) {
            Icon(
                Icons.Default.Download,
                contentDescription = "下载这首歌",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PlayModeRow(mode: PlayMode, onSelect: (PlayMode) -> Unit) {    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PlayMode.entries.forEach { item ->
            val selected = item == mode
            val containerColor =
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            val contentColor =
                if (selected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurfaceVariant
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp)
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        onClick = { onSelect(item) }
                    ),
                shape = RoundedCornerShape(14.dp),
                color = containerColor,
                contentColor = contentColor
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = modeIcon(item),
                        contentDescription = item.label,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = item.short,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

fun modeIcon(mode: PlayMode): ImageVector = when (mode) {
    PlayMode.SEQUENTIAL -> Icons.Default.Repeat
    PlayMode.REVERSE -> Icons.Default.SwapVert
    PlayMode.SHUFFLE -> Icons.Default.Shuffle
    PlayMode.REPEAT_ONE -> Icons.Default.RepeatOne
}

/**
 * 播放页中部的「封面 / 歌词」切换区。
 * 两块内容共用同一个高度，切换时不会让下面的进度条和播放模式跟着上下跳。
 */
@Composable
private fun ArtOrLyrics(
    song: Song,
    artSize: Dp,
    showLyrics: Boolean,
    onShowLyricsChange: (Boolean) -> Unit,
    lyrics: Lyrics?,
    loading: Boolean,
    positionMs: Long,
    onSeek: (Long) -> Unit,
    onSearchLyrics: () -> Unit,
    onAdjustOffset: (Long) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f))
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ViewTab(text = "封面", selected = !showLyrics) { onShowLyricsChange(false) }
            ViewTab(text = "歌词", selected = showLyrics) { onShowLyricsChange(true) }
        }
        Spacer(modifier = Modifier.height(14.dp))

        if (showLyrics) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(artSize)
                ) {
                    LyricsView(
                        lyrics = lyrics,
                        positionMs = positionMs,
                        loading = loading,
                        onSeek = onSeek,
                        onSearch = onSearchLyrics,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp)
                    )
                }
            }
            if (lyrics?.isEmpty == false) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = offsetLabel(lyrics?.offsetMs ?: 0L),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { onAdjustOffset(-500L) }) { Text("-0.5秒") }
                    TextButton(onClick = { onAdjustOffset(500L) }) { Text("+0.5秒") }
                    TextButton(onClick = onSearchLyrics) { Text("重新搜索") }
                }
            }
        } else {
            Surface(
                modifier = Modifier.size(artSize),
                shape = RoundedCornerShape(28.dp),
                shadowElevation = 14.dp
            ) {
                AlbumArt(song = song, size = artSize, cornerRadius = 28.dp)
            }
        }
    }
}

/** 睡眠定时在标题栏上的显示：倒计时按 mm:ss，按曲目则显示还剩几首。 */
private fun timerLabel(state: com.tom.musicplayer.model.SleepState): String = when (state.mode) {
    com.tom.musicplayer.model.SleepMode.MINUTES -> {
        val minutes = state.remainingSec / 60
        val seconds = state.remainingSec % 60
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
    com.tom.musicplayer.model.SleepMode.TRACKS -> "还剩 ${state.remainingTracks} 首"
    else -> ""
}

private fun offsetLabel(offsetMs: Long): String {    if (offsetMs == 0L) return "歌词同步"
    val seconds = offsetMs / 1000.0
    return String.format(Locale.getDefault(), "偏移 %+.1f 秒", seconds)
}

@Composable
private fun ViewTab(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(50.dp))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        shape = RoundedCornerShape(50.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * 自动搜不到（或搜得不对）时的手动入口：输入关键词 → 列出候选 → 选中那条写入本地缓存。
 */
@Composable
private fun LyricSearchDialog(
    song: Song,
    onDismiss: () -> Unit,
    onPick: (LyricCandidate) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(listOf(song.title, song.artist).filter { it.isNotBlank() }.joinToString(" ")) }
    var results by remember { mutableStateOf<List<LyricCandidate>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun runSearch() {
        scope.launch {
            loading = true
            error = null
            results = null
            val outcome = LyricsRepository.search(context, query)
            loading = false
            if (outcome.isSuccess) {
                results = outcome.getOrDefault(emptyList())
            } else {
                error = outcome.exceptionOrNull()?.message ?: "搜索失败"
            }
        }
    }

    LaunchedEffect(Unit) { runSearch() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动挑选歌词") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索关键词") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        IconButton(onClick = { runSearch() }) {
                            Icon(Icons.Default.Search, contentDescription = "搜索")
                        }
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
                Box(modifier = Modifier.height(300.dp)) {
                    when {
                        loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        error != null -> Text(
                            text = error.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        results == null -> Unit
                        results?.isEmpty() == true -> Text(
                            "没有找到，换个关键词试试",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(results.orEmpty(), key = { it.id }) { candidate ->
                                Surface(onClick = { onPick(candidate) }) {
                                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                                        Text(
                                            candidate.trackName,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            listOf(candidate.artistName, candidate.albumName)
                                                .filter { it.isNotBlank() }.joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            if (candidate.hasTimeline) "带时间轴" else "纯文本",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        confirmButton = { }
    )
}
