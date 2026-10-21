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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tom.musicplayer.data.Prefs
import com.tom.musicplayer.data.online.LocalPlaylistStore
import com.tom.musicplayer.data.online.Downloader
import com.tom.musicplayer.data.online.OnlineApi
import com.tom.musicplayer.data.online.OnlineSourceStore
import com.tom.musicplayer.model.OnlinePlaylist
import com.tom.musicplayer.model.OnlineSong
import com.tom.musicplayer.model.Song
import com.tom.musicplayer.util.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 三档音质。传给音源，也决定下载下来的是什么品质。 */
private val QUALITIES = listOf("standard" to "标准", "high" to "高品", "lossless" to "无损")

/**
 * 在线音乐：搜索、榜单歌单、粘贴链接导入。
 *
 * 所有数据都来自「音源管理」里配置的音源——没有音源这里就是空的，
 * 因为 App 本身不生产数据，它只是把标识交给音源、拿回链接播放。
 */
@OptIn(ExperimentalMaterial3Api::class)
/**
 * 多个音源的结果按「来源轮流」交替合并。
 *
 * 直接 flatMap 拼接的话，第一个源的几十条会把后面的源全挤到列表末尾，
 * 用户根本翻不到第二个源的东西——看起来就像「只搜到一个源」。
 * 交错之后每个源的内容都能出现在前几屏。
 */
private fun <T> List<List<T>>.interleave(): List<T> {
    val max = maxOfOrNull { it.size } ?: 0
    val out = ArrayList<T>(sumOf { it.size })
    for (i in 0 until max) {
        for (list in this) if (i < list.size) out.add(list[i])
    }
    return out
}

@Composable
fun OnlineScreen(
    onPlaySongs: (List<Song>, Int) -> Unit,
    onOpenSources: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    // 搜索框是这一页唯一会唤起键盘的东西。键盘没收掉就点结果，播放页会压在键盘上弹出来，
    // 而且第一次按返回是被输入法吃掉的（关键盘），用户会觉得「返回失灵了」。
    // 所以凡是离开输入框的动作——提交搜索、点结果、点下载、打开歌单——都先把焦点挪走。
    //
    // 这里踩过坑：clearFocus(force = true)、SoftwareKeyboardController.hide()、
    // 延后一帧再补一刀，三招都试过，键盘照样弹回来 —— 输入法在 onEditorAction 返回后
    // 会把输入会话重新接上，只要焦点还在输入框上就压不住。
    // 真正管用的是「把焦点交出去」：让页面根节点（非输入型可聚焦节点）接管，
    // 输入框一失焦，输入法自己就退了。
    val rootFocus = remember { FocusRequester() }

    fun dismissKeyboard() {
        runCatching { rootFocus.requestFocus() }
        keyboard?.hide()
    }

    var query by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf(Prefs.getQuality(context)) }
    var results by remember { mutableStateOf<List<OnlineSong>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<OnlinePlaylist>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    // 歌单详情：名字 + 曲目
    var detail by remember { mutableStateOf<Pair<String, List<OnlineSong>>?>(null) }
    var addTarget by remember { mutableStateOf<OnlineSong?>(null) }
    var importOpen by remember { mutableStateOf(false) }

    // 音源是可以在别的页面改的，回来时要重新读，所以用个版本号触发
    var sourceVersion by remember { mutableIntStateOf(0) }
    val sources = remember(sourceVersion) { OnlineSourceStore.enabled(context) }

    fun search(keyword: String) {
        if (sources.isEmpty()) {
            message = "还没有可用的音源，先去「音源管理」添加一个"
            return
        }
        if (keyword.isBlank()) {
            results = emptyList()
            return
        }
        scope.launch {
            loading = true
            message = null
            // 多个音源并行搜，谁先回来谁先显示；单个音源挂了不影响其他源
            val merged = coroutineScope {
                sources.map { src ->
                    async(Dispatchers.IO) {
                        runCatching { OnlineApi.search(src, keyword, quality) }.getOrElse { emptyList() }
                    }
                }.map { it.await() }.interleave()
            }
            results = merged
            loading = false
            if (merged.isEmpty()) message = "没有找到「$keyword」，换个关键词或换音源试试"
        }
    }

    fun loadPlaylists() {
        if (sources.isEmpty()) return
        scope.launch {
            loading = true
            message = null
            val merged = coroutineScope {
                sources.map { src ->
                    async(Dispatchers.IO) {
                        runCatching { OnlineApi.playlists(src) }.getOrElse { emptyList() }
                    }
                }.map { it.await() }.interleave()
            }
            playlists = merged
            loading = false
            if (merged.isEmpty()) message = "这个音源没有提供榜单或歌单"
        }
    }

    /** 起播的统一出口：先把键盘收掉，再交给外层开播放页。 */
    fun play(list: List<OnlineSong>, index: Int) {
        dismissKeyboard()
        onPlaySongs(list.map { it.toSong() }, index)
    }

    fun openPlaylist(p: OnlinePlaylist) {
        dismissKeyboard()
        val src = sources.firstOrNull { it.id == p.sourceId } ?: return
        scope.launch {
            loading = true
            message = null
            val pair = withContext(Dispatchers.IO) {
                runCatching { OnlineApi.playlistSongs(src, p.id, quality) }.getOrNull()
            }
            loading = false
            if (pair == null) message = "打不开这个歌单"
            else detail = pair
        }
    }

    LaunchedEffect(sourceVersion) {
        if (sources.isNotEmpty() && playlists.isEmpty()) loadPlaylists()
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
                .padding(horizontal = 20.dp)
                // 页面根就是这个导航的落点：把焦点交给它，输入框就失焦、键盘收起
                .focusRequester(rootFocus)
                .focusable()
        ) {
            Text(
                text = "在线音乐",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("搜歌名、歌手、专辑") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "搜索") },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = { query = ""; results = emptyList() }) {
                            Icon(Icons.Default.Close, contentDescription = "清空")
                        }
                    }
                },
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(),
                // 必须显式接上输入法的「搜索」动作：不接的话键盘上的放大镜/回车按了没反应，
                // 用户会以为搜索坏了（他没有任何别的入口触发搜索）
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    dismissKeyboard()
                    search(query)
                })
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                QUALITIES.forEach { (value, label) ->
                    FilterChip(
                        selected = quality == value,
                        onClick = {
                            quality = value
                            Prefs.saveQuality(context, value)
                            // 换音质后旧的直链作废，下次播放会重新解析
                            if (query.isNotBlank()) search(query)
                        },
                        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
                        colors = FilterChipDefaults.filterChipColors()
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = { importOpen = true }) { Text("导入歌单") }
            }
            Spacer(modifier = Modifier.height(6.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                TabChip(text = "搜索结果", selected = tab == 0) { tab = 0 }
                Spacer(modifier = Modifier.width(8.dp))
                TabChip(text = "榜单歌单", selected = tab == 1) {
                    tab = 1
                    if (playlists.isEmpty()) loadPlaylists()
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            if (sources.isEmpty()) {
                NoSourceHint(onOpenSources = onOpenSources)
                return@Column
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    detail != null -> {
                        val (name, list) = detail!!
                        Column(modifier = Modifier.fillMaxSize()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (list.isNotEmpty()) {
                                    TextButton(onClick = { play(list, 0) }) { Text("播放全部") }
                                }
                                TextButton(onClick = { detail = null }) { Text("返回") }
                            }
                            SongResultList(
                                songs = list,
                                onPlay = { i -> play(list, i) },
                                onDownload = { song ->
                                    dismissKeyboard()
                                    scope.launch { Downloader.download(context, song, quality) }
                                },
                                onAdd = { addTarget = it }
                            )
                        }
                    }
                    tab == 1 -> {
                        if (playlists.isEmpty() && message != null) {
                            HintText(text = message!!, modifier = Modifier.align(Alignment.Center))
                        } else {
                            LazyColumn(contentPadding = PaddingValues(bottom = 12.dp)) {
                                itemsIndexed(playlists, key = { _, p -> p.sourceId + p.id }) { _, p ->
                                    PlaylistRow(playlist = p, onClick = { openPlaylist(p) })
                                }
                            }
                        }
                    }
                    results.isEmpty() -> {
                        HintText(
                            text = message ?: "输入关键词开始搜索",
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    else -> SongResultList(
                        songs = results,
                        onPlay = { i -> play(results, i) },
                        onDownload = { song ->
                            dismissKeyboard()
                            scope.launch { Downloader.download(context, song, quality) }
                        },
                        onAdd = { addTarget = it }
                    )
                }
            }
        }
    }

    addTarget?.let { song ->
        AddToPlaylistDialog(
            song = song,
            onDismiss = { addTarget = null },
            onConfirm = { playlistId ->
                LocalPlaylistStore.addSong(context, playlistId, song)
                addTarget = null
            },
            onCreate = { name ->
                val p = LocalPlaylistStore.create(context, name)
                LocalPlaylistStore.addSong(context, p.id, song)
                addTarget = null
            }
        )
    }

    if (importOpen) {
        ImportPlaylistDialog(
            sources = sources,
            quality = quality,
            onDismiss = { importOpen = false },
            onResult = { name, songs ->
                importOpen = false
                if (songs.isNotEmpty()) detail = name to songs
                else message = "没导入到歌曲，检查一下链接或 ID"
            }
        )
    }
}

@Composable
private fun TabChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun HintText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 32.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun NoSourceHint(onOpenSources: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "还没有音源",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "在线音乐全部来自你在「音源管理」里添加的服务。App 自己不提供歌曲，"
                    + "去添加一个就能搜索和播放了。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(14.dp))
        TextButton(onClick = onOpenSources) { Text("去添加音源") }
    }
}

@Composable
private fun SongResultList(
    songs: List<OnlineSong>,
    onPlay: (Int) -> Unit,
    onDownload: (OnlineSong) -> Unit,
    onAdd: (OnlineSong) -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        itemsIndexed(songs, key = { _, s -> s.globalId }) { index, song ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlay(index) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (song.cover.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    AsyncImage(
                        model = song.cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = song.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = listOf(song.artist, song.album).filter { it.isNotBlank() }
                            .joinToString(" · "),
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
                IconButton(onClick = { onDownload(song) }) {
                    Icon(Icons.Default.Download, contentDescription = "下载")
                }
                IconButton(onClick = { onAdd(song) }) {
                    Icon(Icons.Default.PlaylistAdd, contentDescription = "加到歌单")
                }
            }
        }
    }
}

@Composable
private fun PlaylistRow(playlist: OnlinePlaylist, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = playlist.name.take(1),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            playlist.desc?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (playlist.count > 0) {
            Text(
                text = "${playlist.count} 首",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 粘贴链接 / ID 导入歌单。音源侧给什么 id 就用什么，这里只做透传。 */
@Composable
private fun ImportPlaylistDialog(
    sources: List<com.tom.musicplayer.model.OnlineSource>,
    quality: String,
    onDismiss: () -> Unit,
    onResult: (String, List<OnlineSong>) -> Unit
) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导入歌单") },
        text = {
            Column {
                Text(
                    text = "粘贴歌单链接或歌单 ID。链接里的数字部分会自动当作 ID 用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    singleLine = true,
                    placeholder = { Text("https://.../playlist?id=12345") },
                    shape = RoundedCornerShape(12.dp)
                )
                AnimatedVisibility(visible = error != null) {
                    Text(
                        text = error ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank() && !busy,
                onClick = {
                    val id = extractPlaylistId(text)
                    if (id.isBlank()) {
                        error = "没认出歌单 ID"
                        return@TextButton
                    }
                    busy = true
                    scope.launch {
                        var found: Pair<String, List<OnlineSong>>? = null
                        for (src in sources) {
                            val pair = withContext(Dispatchers.IO) {
                                runCatching { OnlineApi.playlistSongs(src, id, quality) }.getOrNull()
                            }
                            if (pair != null && pair.second.isNotEmpty()) {
                                found = pair
                                break
                            }
                        }
                        busy = false
                        if (found == null) {
                            error = "所有音源都打不开这个歌单"
                        } else {
                            onResult(found!!.first, found!!.second)
                        }
                    }
                }
            ) { Text(if (busy) "导入中" else "导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 从链接里抠出歌单 ID：优先取 id 参数，退化规则是取 URL 末尾那段数字。 */
private fun extractPlaylistId(raw: String): String {
    val text = raw.trim()
    if (text.isBlank()) return ""
    runCatching {
        val uri = android.net.Uri.parse(text)
        uri.getQueryParameter("id")?.takeIf { it.isNotBlank() }?.let { return it }
        uri.lastPathSegment?.takeIf { it.all { c -> c.isDigit() } }?.let { return it }
    }
    // 不是 URL 就当它是纯 ID
    return text.substringAfterLast('/').substringBefore('?')
}

@Composable
private fun AddToPlaylistDialog(
    song: OnlineSong,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onCreate: (String) -> Unit
) {
    val context = LocalContext.current
    val playlists = remember { LocalPlaylistStore.load(context) }
    var newName by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加到歌单") },
        text = {
            Column {
                Text(
                    text = song.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (playlists.isEmpty() || creating) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        placeholder = { Text("新歌单名字") },
                        shape = RoundedCornerShape(12.dp)
                    )
                } else {
                    playlists.forEach { p ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onConfirm(p.id) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(p.name, modifier = Modifier.weight(1f))
                            Text(
                                text = "${p.songs.size} 首",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { creating = true }) { Text("新建歌单") }
                }
            }
        },
        confirmButton = {
            if (creating || playlists.isEmpty()) {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = { onCreate(newName.trim()) }
                ) { Text("创建并加入") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
