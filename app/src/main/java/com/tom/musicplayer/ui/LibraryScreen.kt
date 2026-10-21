package com.tom.musicplayer.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tom.musicplayer.model.Song
import com.tom.musicplayer.player.PlayMode
import com.tom.musicplayer.ui.components.AlbumArt
import com.tom.musicplayer.util.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    songs: List<Song>,
    /** 正在播放的那首。可能不在 [songs] 里（比如正在听在线歌），迷你条照样要显示。 */
    nowPlaying: Song?,
    currentIndex: Int,
    isPlaying: Boolean,
    folderLabel: String?,
    scanning: Boolean,
    hasFolder: Boolean,
    folderUnreadable: Boolean,
    mode: PlayMode,
    onPickFolder: () -> Unit,
    onSongClick: (Int) -> Unit,
    onPlayQueue: (List<Song>, Int) -> Unit,
    onToggleFavorite: (Song) -> Unit,
    onOpenPlayer: () -> Unit,
    onTogglePlay: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSelectMode: (PlayMode) -> Unit,
    onOpenSources: () -> Unit,
    favorites: Set<String>,
    modifier: Modifier = Modifier
) {
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var favOnly by remember { mutableStateOf(false) }

    // 过滤结果保留原始下标：点歌时要知道它在完整列表里的位置
    val visible = remember(songs, query, favOnly, favorites) {
        songs.mapIndexed { index, song -> index to song }
            .filter { (_, song) ->
                val matchesText = query.isBlank() ||
                    song.title.contains(query, ignoreCase = true) ||
                    song.artist.contains(query, ignoreCase = true) ||
                    song.album.contains(query, ignoreCase = true) ||
                    song.fileName.contains(query, ignoreCase = true)
                matchesText && (!favOnly || song.id in favorites)
            }
    }
    val filtering = query.isNotBlank() || favOnly

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            HeaderBar(
                count = songs.size,
                folderLabel = folderLabel,
                hasFolder = hasFolder,
                mode = mode,
                query = query,
                searchOpen = searchOpen,
                onSearchOpenChange = { searchOpen = it },
                onQueryChange = { query = it },
                favOnly = favOnly,
                onFavOnlyChange = { favOnly = it },
                favCount = favorites.size,
                onPickFolder = onPickFolder,
                onSelectMode = onSelectMode,
                onOpenSources = onOpenSources
            )
        },
        bottomBar = {
            if (nowPlaying != null) {
                MiniPlayer(
                    song = nowPlaying,
                    isPlaying = isPlaying,
                    onOpenPlayer = onOpenPlayer,
                    onTogglePlay = onTogglePlay,
                    onSkipNext = onSkipNext,
                    onSkipPrevious = onSkipPrevious
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (folderUnreadable) {
                FolderAccessBanner(folderLabel = folderLabel, onPickFolder = onPickFolder)
            }
            Box(modifier = Modifier.fillMaxSize().weight(1f)) {
                if (songs.isEmpty()) {
                    EmptyState(
                        scanning = scanning,
                        hasFolder = hasFolder,
                        folderUnreadable = folderUnreadable,
                        onPickFolder = onPickFolder
                    )
                } else if (visible.isEmpty()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (favOnly) "收藏里还没有歌" else "没有匹配的歌曲",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (favOnly) "在列表里点心形图标就能加进来"
                            else "换个关键词试试，或清除搜索条件",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp)
                    ) {
                        itemsIndexed(visible, key = { _, pair -> pair.second.id }) { _, pair ->
                            val (originalIndex, song) = pair
                            SongRow(
                                song = song,
                                isCurrent = originalIndex == currentIndex,
                                isFavorite = song.id in favorites,
                                onToggleFavorite = { onToggleFavorite(song) },
                                onClick = {
                                    if (filtering) {
                                        // 搜索或收藏视图下，队列就应该是眼前这一份，不该跳回完整列表
                                        val queue = visible.map { it.second }
                                        val at = visible.indexOfFirst { it.first == originalIndex }
                                        onPlayQueue(queue, at)
                                    } else {
                                        onSongClick(originalIndex)
                                    }
                                }
                            )
                        }
                    }
                }
                if (scanning) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderBar(
    count: Int,
    folderLabel: String?,
    hasFolder: Boolean,
    mode: PlayMode,
    onPickFolder: () -> Unit,
    onSelectMode: (PlayMode) -> Unit,
    onOpenSources: () -> Unit,
    query: String,
    searchOpen: Boolean,
    onSearchOpenChange: (Boolean) -> Unit,
    onQueryChange: (String) -> Unit,
    favOnly: Boolean,
    onFavOnlyChange: (Boolean) -> Unit,
    favCount: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "我的音乐",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = when {
                        !hasFolder -> "还没有选择音乐文件夹"
                        count == 0 -> "文件夹：$folderLabel"
                        else -> "$count 首歌曲 · $folderLabel"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    IconButton(onClick = { onSearchOpenChange(!searchOpen) }) {
                        Icon(Icons.Default.Search, contentDescription = "搜索歌曲")
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    IconButton(onClick = onOpenSources) {
                        Icon(
                            imageVector = Icons.Default.Cloud,
                            contentDescription = "网络音乐服务器"
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    IconButton(onClick = onPickFolder) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "选择音乐文件夹"
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        // 播放模式入口：带文字，一眼能看到当前是哪种模式
        ModePill(mode = mode, onSelectMode = onSelectMode)

        AnimatedVisibility(visible = searchOpen) {
            Column {
                Spacer(modifier = Modifier.height(10.dp))
                SearchField(
                    query = query,
                    onQueryChange = onQueryChange,
                    onClose = {
                        onQueryChange("")
                        onSearchOpenChange(false)
                    }
                )
            }
        }

        if (favCount > 0 || favOnly) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = favOnly,
                    onClick = { onFavOnlyChange(!favOnly) },
                    label = {
                        Text(
                            // 空收藏时只显示「我的喜欢」，不写「0 首」这种别扭的计数
                            text = if (favCount > 0) "我的喜欢 $favCount" else "我的喜欢",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (favOnly) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (favOnly) Icons.Default.Favorite
                            else Icons.Default.FavoriteBorder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.primary
                    )
                )
                Spacer(modifier = Modifier.width(10.dp))
                if (favOnly || query.isNotBlank()) {
                    TextButton(
                        onClick = {
                            onQueryChange("")
                            onFavOnlyChange(false)
                        }
                    ) {
                        Text("清除筛选", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/** 搜索框：直接过滤标题 / 歌手 / 专辑 / 文件名，不用再开一个页面。 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        placeholder = {
            Text("搜歌名、歌手、专辑…", style = MaterialTheme.typography.bodyMedium)
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "清空搜索",
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭搜索",
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            cursorColor = MaterialTheme.colorScheme.primary
        )
    )
}

/** 播放模式选择：显示当前模式全称，点开可切换四种模式。 */
@Composable
private fun ModePill(mode: PlayMode, onSelectMode: (PlayMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.height(44.dp)
        ) {
            Row(
                modifier = Modifier
                    .clickable { expanded = true }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = modeIcon(mode),
                    contentDescription = "播放模式",
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "播放模式",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        text = mode.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "切换播放模式",
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PlayMode.entries.forEach { item ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = item.label,
                            fontWeight = if (item == mode) FontWeight.Bold else FontWeight.Normal,
                            color = if (item == mode) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = modeIcon(item),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (item == mode) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        if (item == mode) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "当前模式",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    onClick = {
                        onSelectMode(item)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SongRow(
    song: Song,
    isCurrent: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit
) {
    val containerColor =
        if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
        else MaterialTheme.colorScheme.surface
    val textColor =
        if (isCurrent) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isCurrent) 4.dp else 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AlbumArt(song = song, size = 52.dp, cornerRadius = 12.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isCurrent) textColor.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = formatDuration(song.durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = if (isCurrent) textColor.copy(alpha = 0.75f) else MaterialTheme.colorScheme.outline
            )
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (isFavorite) "取消收藏" else "收藏这首歌",
                    modifier = Modifier.size(20.dp),
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MiniPlayer(
    song: Song,
    isPlaying: Boolean,
    onOpenPlayer: () -> Unit,
    onTogglePlay: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 10.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpenPlayer() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AlbumArt(song = song, size = 46.dp, cornerRadius = 12.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onSkipPrevious) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "上一首")
            }
            IconButton(onClick = onTogglePlay) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放"
                )
            }
            IconButton(onClick = onSkipNext) {
                Icon(Icons.Default.SkipNext, contentDescription = "下一首")
            }
        }
    }
}

@Composable
private fun EmptyState(
    scanning: Boolean,
    hasFolder: Boolean,
    folderUnreadable: Boolean,
    onPickFolder: () -> Unit
) {
    val (title, desc, button) = when {
        scanning -> Triple("正在扫描音乐…", "正在读取文件夹里的音频文件", null)
        folderUnreadable -> Triple(
            "读不到文件夹里的音乐",
            "可能是系统收回了访问权限。重新选择同一个文件夹即可恢复，已缓存的歌曲不受影响。",
            "重新选择文件夹"
        )
        hasFolder -> Triple(
            "这个文件夹里没有音乐",
            "支持 MP3 / WAV / FLAC / M4A 等格式，换一个文件夹试试。",
            "换一个文件夹"
        )
        else -> Triple(
            "还没有音乐",
            "选择一个存放音乐的文件夹，支持 MP3 / WAV / FLAC 等格式",
            "选择音乐文件夹"
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            Box(modifier = Modifier.size(112.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.LibraryMusic,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = desc,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        button?.let { label ->
            Button(
                onClick = onPickFolder,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(label)
            }
        }
    }
}

/** 文件夹曾经可用、这次扫不出来时给出明确提示，而不是假装在首次运行。 */
@Composable
private fun FolderAccessBanner(folderLabel: String?, onPickFolder: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.FolderOff,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "暂时读不到「${folderLabel ?: "已选文件夹"}」，下面显示的是上次的列表",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onPickFolder) {
                Text("重新授权", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
