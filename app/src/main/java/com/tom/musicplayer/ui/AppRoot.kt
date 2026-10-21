package com.tom.musicplayer.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.tom.musicplayer.data.FavoritesStore
import com.tom.musicplayer.data.LibraryCache
import com.tom.musicplayer.data.MusicScanner
import com.tom.musicplayer.data.Prefs
import com.tom.musicplayer.data.UriGrant
import com.tom.musicplayer.data.online.DownloadStore
import com.tom.musicplayer.data.online.Downloader
import com.tom.musicplayer.data.update.UpdateCenter
import com.tom.musicplayer.model.Song
import com.tom.musicplayer.player.NotificationActions
import com.tom.musicplayer.player.PlaybackService
import com.tom.musicplayer.player.PlayerController
import com.tom.musicplayer.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var treeUri by remember { mutableStateOf<Uri?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var showPlayer by remember { mutableStateOf(false) }
    var crashLog by remember { mutableStateOf(CrashLogger.read(context)) }
    // 曾经选过文件夹（哪怕这一次暂时读不出来），决定要不要显示「首次引导」文案
    var hasFolder by remember { mutableStateOf(false) }
    // 文件夹有记录，但这次扫不出内容（多半是 SAF 授权被系统回收）
    var folderUnreadable by remember { mutableStateOf(false) }
    var screen by remember { mutableStateOf(AppScreen.LIBRARY) }
    var rescanJob by remember { mutableStateOf<Job?>(null) }
    // 从「在线」页进音源管理时，返回键要回在线页；从「我的」进则回「我的」
    var returnToOnline by remember { mutableStateOf(false) }
    // 「我点心的歌曲」有三个入口（我的 / 我的歌单 / 曲库筛选），返回要回到进来的地方
    var favoritesBack by remember { mutableStateOf(AppScreen.PLAYLISTS) }
    // 播放队列是从播放页点进去的，返回要回到播放页（而不是退到列表）
    var queueOpen by remember { mutableStateOf(false) }
    // 「我的」的下级页：返回时回「我的」，底栏继续保持「我的」高亮
    var meSub by remember { mutableStateOf<AppScreen?>(null) }

    /**
     * 「我的音乐」列表 == 本地曲库，独立于播放队列。
     *
     * 以前这里直接把播放队列当成曲库显示，结果从在线页播一首歌、或者从「我的下载」
     * 里放着刚下的一首，整个本地曲库视图就被那一首歌顶掉了 —— 用户看到的是
     * 「文件夹里其他歌都不见了，重新扫描才回来」。列表跟队列分开，就不会再互相污染。
     */
    var library by remember { mutableStateOf<List<Song>>(emptyList()) }

    val songs by PlayerController.songs.collectAsStateWithLifecycle()
    val currentIndex by PlayerController.currentIndex.collectAsStateWithLifecycle()
    val isPlaying by PlayerController.isPlaying.collectAsStateWithLifecycle()
    val mode by PlayerController.mode.collectAsStateWithLifecycle()
    // 收藏统一由控制器持有：通知栏点一下收藏，这里（列表计数、播放页心形）立刻跟着变
    val favorites by PlayerController.favorites.collectAsStateWithLifecycle()
    // 播放页的下载键要实时反映「正在下 / 下过了」，所以这两条流都得订阅
    val downloadKeys by Downloader.downloadedKeys.collectAsStateWithLifecycle()
    val downloadTasks by Downloader.tasks.collectAsStateWithLifecycle()
    val songQueue by PlayerController.queue.collectAsStateWithLifecycle()

    val currentSong = songs.getOrNull(currentIndex)

    // 扫描指定文件夹；失败会重试一次，并用磁盘缓存兜底，避免闪一下「还没有音乐」。
    // known 传上一次的结果，命中就复用元数据，避免每次启动都把整库标签重解一遍。
    fun rescan(uri: Uri, known: List<Song> = emptyList()) {
        // 界面可能因为息屏、语言或主题切换连续重建，取消上一次没跑完的扫描，
        // 否则两个全库扫描并发起来，内存峰值直接翻倍
        rescanJob?.cancel()
        scanning = true
        folderUnreadable = false
        rescanJob = scope.launch {
            val first = withContext(Dispatchers.IO) {
                runCatching { MusicScanner.scan(context, uri, known) }.getOrDefault(emptyList())
            }
            val result = if (first.isEmpty()) {
                // 某些 ROM 冷启动后授权恢复有延迟，给一次短重试
                delay(600)
                withContext(Dispatchers.IO) {
                    runCatching { MusicScanner.scan(context, uri, known) }.getOrDefault(emptyList())
                }
            } else first

            if (result.isNotEmpty()) {
                UriGrant.take(context, uri)
                // 曲库列表先更新，它只反映「文件夹里有什么」，与正在播什么无关
                library = result
                // 用户正在听网络曲库时，别让本地扫描结果把远端播放队列顶掉
                if (PlayerController.songs.value.none { it.isRemote }) {
                    runCatching { PlayerController.setSongs(context, result) }
                }
                withContext(Dispatchers.IO) {
                    LibraryCache.save(context, result)
                    // 曲库是最全的一份元数据：拿它把收藏里缺歌名 / 缺地址的老记录补上
                    runCatching { FavoritesStore.enrich(context, result) }
                }
                folderUnreadable = false
            } else {
                // 扫不到但列表里有缓存歌曲：保留旧列表，只提示需要重新授权
                folderUnreadable = true
            }
            scanning = false
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 注意：这里只建立 MediaController 连接，不主动「抢占」前台服务名额。
    // 播放服务按需用普通 startService 拉起（见 withPlayer），避免触发前台服务契约检查。
    DisposableEffect(context) {
        var controller: MediaController? = null
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = runCatching { MediaController.Builder(context, token).buildAsync() }.getOrNull()
        future?.addListener(
            { controller = runCatching { future.get() }.getOrNull() },
            ContextCompat.getMainExecutor(context)
        )
        onDispose {
            runCatching { controller?.release() }
            PlayerController.saveProgress()
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            UriGrant.take(context, uri)
            Prefs.saveTreeUri(context, uri)
            hasFolder = true
            treeUri = uri
            rescan(uri)
        }
    }

    LaunchedEffect(Unit) {
        // 下载索引要先就位：曲库里那个文件能不能认回「原本是音源里的哪首歌」全靠它，
        // 认不出来，同一首歌在「我的音乐」和「在线」就会各有一套点心记录
        withContext(Dispatchers.IO) {
            runCatching { DownloadStore.load(context) }
            runCatching { FavoritesStore.normalize(context) }
        }
        // 服务可能还没起来（界面先启动），所以这里主动把磁盘上的收藏读一次
        PlayerController.syncFavorites(context)

        // 1) 先用磁盘缓存把列表立刻顶起来，防止重启瞬间显示空白 + 要求重选文件夹
        val cached = withContext(Dispatchers.IO) { LibraryCache.load(context) }
        if (cached.isNotEmpty()) {
            library = cached
            if (PlayerController.songs.value.isEmpty()) {
                runCatching { PlayerController.setSongs(context, cached) }
            }
        }

        // 2) 找回上次选择的文件夹：Prefs 优先，系统仍留着的持久化授权兜底
        val stored = Prefs.getTreeUri(context) ?: UriGrant.recoverTree(context)?.also {
            Prefs.saveTreeUri(context, it)
        }
        hasFolder = stored != null
        treeUri = stored
        // 把已解析好的元数据传给扫描器：只有新歌才需要真正打开文件
        if (stored != null) rescan(stored, cached)
    }

    // 下载完自动刷一次曲库：下到音乐文件夹里的歌该马上出现，
    // 而不是等用户下次重启、或者自己去重新选一遍文件夹
    LaunchedEffect(Unit) {
        Downloader.saved.collect {
            // 刚下完的歌从此有了音源身份：如果之前把本地文件点过心，这里把它并成一条
            withContext(Dispatchers.IO) {
                runCatching { FavoritesStore.normalize(context) }
            }
            PlayerController.syncFavorites(context)
            val uri = Prefs.getTreeUri(context) ?: return@collect
            if (Prefs.getDownloadTarget(context) == "saf") rescan(uri, library)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
        // 内容区占满除底栏外的空间：三个主页面都有底部导航，
        // 播放页浮层要盖住全部，所以放在最外层的 Box 里
        Box(modifier = Modifier.weight(1f)) {
        // 「我的」可能还叠着一个下级页（数据管理）。下级页开着时底下的菜单页照样组合着，
        // 返回只是把它关掉 —— 和列表进详情的体验一致，不用再记一次「上一页是哪个」
        if (meSub != null) {
            Box(modifier = Modifier.fillMaxSize()) {
            MeScreen(
                onOpenDownloads = { screen = AppScreen.DOWNLOADS },
                onOpenFavorites = { favoritesBack = AppScreen.ME; screen = AppScreen.FAVORITES },
                onOpenPlaylists = { screen = AppScreen.PLAYLISTS },
                onOpenSources = {
                    returnToOnline = false
                    screen = AppScreen.ONLINE_SOURCES
                },
                onOpenBackup = { meSub = AppScreen.BACKUP }
            )
            when (meSub) {
                AppScreen.BACKUP -> BackupScreen(onBack = { meSub = null })
                else -> Unit
            }
            }
            return@Box
        }
        when (screen) {
            AppScreen.SOURCES -> SourcesScreen(
                onBack = { screen = AppScreen.LIBRARY },
                onPlayRemote = { list, index ->
                    scope.launch {
                        withPlayer(context) { PlayerController.playSongs(context, list, index) }
                    }
                    screen = AppScreen.LIBRARY
                    showPlayer = true
                }
            )
            AppScreen.ONLINE -> OnlineScreen(
                onPlaySongs = { list, index ->
                    scope.launch {
                        withPlayer(context) { PlayerController.playSongs(context, list, index) }
                    }
                    showPlayer = true
                },
                onOpenSources = {
                    returnToOnline = true
                    screen = AppScreen.ONLINE_SOURCES
                },
                playerOpen = showPlayer
            )
            AppScreen.ME -> MeScreen(
                onOpenDownloads = { screen = AppScreen.DOWNLOADS },
                onOpenFavorites = { favoritesBack = AppScreen.ME; screen = AppScreen.FAVORITES },
                onOpenPlaylists = { screen = AppScreen.PLAYLISTS },
                onOpenSources = {
                    returnToOnline = false
                    screen = AppScreen.ONLINE_SOURCES
                },
                onOpenBackup = {
                    // 数据管理是「我的」的下级，选中态仍然停在「我的」
                    meSub = AppScreen.BACKUP
                }
            )
            AppScreen.DOWNLOADS -> DownloadsScreen(
                onBack = { screen = AppScreen.ME },
                onPlay = { song ->
                    scope.launch {
                        withPlayer(context) { PlayerController.playSongs(context, listOf(song), 0) }
                    }
                    showPlayer = true
                },
                onPickFolder = { folderPicker.launch(null) }
            )
            AppScreen.PLAYLISTS -> PlaylistsScreen(
                onOpenFavorites = { favoritesBack = AppScreen.PLAYLISTS; screen = AppScreen.FAVORITES },
                onBack = { screen = AppScreen.ME },
                onPlay = { list, index ->
                    scope.launch {
                        withPlayer(context) { PlayerController.playSongs(context, list, index) }
                    }
                    showPlayer = true
                }
            )
            // 点心（收藏）就是一份歌单，点开直接能播，不用先转存
            AppScreen.FAVORITES -> FavoritesScreen(
                library = library,
                onBack = { screen = favoritesBack },
                onPlay = { list, index ->
                    scope.launch {
                        withPlayer(context) { PlayerController.playSongs(context, list, index) }
                    }
                    showPlayer = true
                }
            )
            AppScreen.ONLINE_SOURCES -> OnlineSourceScreen(
                onBack = { screen = if (returnToOnline) AppScreen.ONLINE else AppScreen.ME }
            )
            AppScreen.LIBRARY -> LibraryScreen(
                songs = library,
                nowPlaying = currentSong,
                // 高亮按曲目 id 找：队列现在可能和曲库不是同一份
                currentIndex = currentSong?.let { cur ->
                    library.indexOfFirst { it.id == cur.id }
                } ?: -1,
                isPlaying = isPlaying,
                folderLabel = sourceLabel(treeUri, library),
                scanning = scanning,
                hasFolder = hasFolder,
                folderUnreadable = folderUnreadable,
                mode = mode,
                onPickFolder = { folderPicker.launch(null) },
                onOpenSources = { screen = AppScreen.SOURCES },
                onOpenFavorites = { favoritesBack = AppScreen.LIBRARY; screen = AppScreen.FAVORITES },
                onSongClick = { index ->
                    val songList = library
                    scope.launch {
                        withPlayer(context) {
                            // 队列就是曲库时按原逻辑走（能记住上次听到哪），
                            // 否则才整体换成曲库 —— 这样从在线/下载页播完歌回来，
                            // 曲库不会被一首歌顶掉
                            val queueIds = PlayerController.songs.value.map { it.id }
                            if (queueIds == songList.map { it.id }) {
                                PlayerController.playSong(index)
                            } else {
                                PlayerController.playSongs(context, songList, index)
                            }
                        }
                        showPlayer = true
                    }
                },
                onOpenPlayer = { showPlayer = true },
                onPlayQueue = { queue, index ->
                    scope.launch {
                        withPlayer(context) { PlayerController.playSongs(context, queue, index) }
                        showPlayer = true
                    }
                },
                onToggleFavorite = { song -> PlayerController.toggleFavorite(song) },
                favorites = favorites,
                onTogglePlay = { scope.launch { withPlayer(context) { PlayerController.togglePlay() } } },
                onSkipNext = { scope.launch { withPlayer(context) { PlayerController.skipNext() } } },
                onSkipPrevious = {
                    scope.launch { withPlayer(context) { PlayerController.skipPrevious() } }
                },
                onSelectMode = { target ->
                    scope.launch { withPlayer(context) { PlayerController.setMode(target) } }
                }
            )
            // 理论上走不到：meSub 已在上面的分支里处理掉了，这里只是让 when 保持穷尽
            else -> Unit
        }
        }

        if (screen == AppScreen.LIBRARY || screen == AppScreen.ONLINE || screen == AppScreen.ME) {
            BottomNav(
                current = screen,
                onSelect = { target ->
                    screen = target
                    meSub = null
                }
            )
        }
        }

        AnimatedVisibility(
            visible = showPlayer && currentSong != null,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            val song = currentSong
            if (song != null) {
                // 正在播的这首的下载状态：本地歌没有下载概念（null），在线歌分三态
                val dlKey = Downloader.taskKey(song.onlineSourceId, song.onlineSongId)
                val busyTask = downloadTasks.firstOrNull {
                    it.key == dlKey && !it.done && it.error == null
                }
                val downloadState = if (!song.isOnline || dlKey == null) null else when {
                    busyTask != null -> NotificationActions.DownloadState.BUSY
                    dlKey in downloadKeys -> NotificationActions.DownloadState.DONE
                    else -> NotificationActions.DownloadState.READY
                }
                PlayerScreen(
                    song = song,
                    isPlaying = isPlaying,
                    isFavorite = PlayerController.isFavorite(song),
                    mode = mode,
                    onBack = { showPlayer = false },
                    onTogglePlay = {
                        scope.launch { withPlayer(context) { PlayerController.togglePlay() } }
                    },
                    onNext = { scope.launch { withPlayer(context) { PlayerController.skipNext() } } },
                    onPrevious = {
                        scope.launch { withPlayer(context) { PlayerController.skipPrevious() } }
                    },
                    onSelectMode = { target ->
                        scope.launch { withPlayer(context) { PlayerController.setMode(target) } }
                    },
                    onToggleFavorite = { PlayerController.toggleFavorite(song) },
                    onOpenQueue = { queueOpen = true },
                    queueSize = songQueue.size,
                    downloadState = downloadState,
                    downloadProgress = busyTask?.progress ?: -1,
                    onDownload = {
                        when (downloadState) {
                            NotificationActions.DownloadState.READY -> {
                                val started = Downloader.downloadFromSong(context, song)
                                toast(
                                    context,
                                    if (started) "开始下载：${song.title}"
                                    else "这首歌已经在下载了"
                                )
                            }
                            NotificationActions.DownloadState.DONE ->
                                toast(context, "已经在「我的下载」里了：${song.title}")
                            else -> Unit
                        }
                    }
                )
            }
        }
    }

    // 播放队列铺在播放页上面：从播放页点进去、返回回到播放页，
    // 而不是把播放页一起关掉（那样用户点一下队列就"退出"了正在听的这首）
    if (queueOpen) {
        QueueScreen(
            onBack = { queueOpen = false },
            onOpenPlayer = { queueOpen = false }
        )
    }

    val currentLog = crashLog
    if (currentLog != null) {
        CrashLogDialog(
            log = currentLog,
            onDismiss = {
                CrashLogger.clear(context)
                crashLog = null
            }
        )
    }

    // 升级弹窗放在这里，是因为它是全局状态：启动时静默检查到的结果、
    // 「我的」页手动检查的结果，都从同一个状态流过来，只弹一个框
    val updateState by UpdateCenter.state.collectAsStateWithLifecycle()
    when (val s = updateState) {
        is UpdateCenter.State.Available,
        is UpdateCenter.State.Downloading,
        is UpdateCenter.State.Ready,
        is UpdateCenter.State.NeedPermission,
        is UpdateCenter.State.Failed -> UpdateDialog(state = s, onDismiss = { UpdateCenter.dismiss() })
        else -> Unit
    }

    BackHandler(enabled = queueOpen) { queueOpen = false }

    BackHandler(enabled = showPlayer && !queueOpen) { showPlayer = false }

    /**
     * 二级页面的系统返回（含全面屏左滑手势）。
     *
     * 以前只有播放页和在线详情接了返回，其余二级页（我的下载 / 歌单 / 点心 /
     * 音源管理 / WebDAV）系统返回会直接退出 App——用户左滑的预期明明是回到上一级。
     * 这里统一接一次：每个二级页声明自己的父页面，返回键走和左上角箭头一样的路径。
     *
     * 注意 enabled 要排除播放页：两个 handler 同为开启时后注册的赢，
     * 而手势发生在播放页盖上时应该先关播放页，不是退二级页。
     */
    BackHandler(enabled = meSub != null && !showPlayer && !queueOpen) { meSub = null }

    val screenParent: AppScreen? = when (screen) {
        AppScreen.SOURCES -> AppScreen.LIBRARY
        AppScreen.DOWNLOADS -> AppScreen.ME
        AppScreen.PLAYLISTS -> AppScreen.ME
        AppScreen.FAVORITES -> favoritesBack
        AppScreen.ONLINE_SOURCES -> if (returnToOnline) AppScreen.ONLINE else AppScreen.ME
        else -> null
    }
    BackHandler(enabled = !showPlayer && screenParent != null) {
        screenParent?.let { screen = it }
    }
}

private enum class AppScreen {
    LIBRARY, SOURCES, ONLINE, ME, DOWNLOADS, PLAYLISTS, FAVORITES, ONLINE_SOURCES, BACKUP
}

/**
 * 底部三个主页面。SOURCES（WebDAV）是列表页里的二级入口，不占底栏位置——
 * 它属于「本地曲库」的延伸，而不是和在线音乐并列的一级功能。
 */
@Composable
private fun BottomNav(current: AppScreen, onSelect: (AppScreen) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        NavigationBarItem(
            selected = current == AppScreen.LIBRARY,
            onClick = { onSelect(AppScreen.LIBRARY) },
            icon = { Icon(Icons.Default.LibraryMusic, contentDescription = "我的音乐") },
            label = { Text("我的音乐") }
        )
        NavigationBarItem(
            selected = current == AppScreen.ONLINE,
            onClick = { onSelect(AppScreen.ONLINE) },
            icon = { Icon(Icons.Default.Public, contentDescription = "在线") },
            label = { Text("在线") }
        )
        NavigationBarItem(
            selected = current == AppScreen.ME,
            onClick = { onSelect(AppScreen.ME) },
            icon = { Icon(Icons.Default.Person, contentDescription = "我的") },
            label = { Text("我的") }
        )
    }
}

/** 列表副标题：本地曲库显示文件夹名，网络曲库显示来源提示。 */
private fun sourceLabel(treeUri: Uri?, songs: List<Song>): String? {
    if (songs.any { it.isRemote }) return "网络曲库 · WebDAV"
    return treeUri?.let { MusicScanner.folderName(it) }
}

/**
 * 确保播放服务活着再执行控制指令：服务可能被系统回收（暂停/息屏时很常见），
 * 重新拉起后控制器会自动按播放模式恢复队列，按钮因此可以立刻生效。
 */
private suspend fun withPlayer(context: Context, action: () -> Unit) {    var waited = 0L
    while (PlayerController.player == null && waited < 3000L) {
        runCatching { context.startService(Intent(context, PlaybackService::class.java)) }
        delay(150)
        waited += 150
    }
    if (PlayerController.player == null) return
    // 不要静默吞掉：播放指令抛异常时按钮「点了没反应」最难查，
    // 这里把堆栈打到 logcat，adb logcat -s QingTing 就能看到
    runCatching { action() }
        .onFailure { android.util.Log.e("QingTing", "播放指令执行失败", it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CrashLogDialog(log: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("上次运行崩溃了") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = "下面是崩溃日志，复制后发给开发者就能快速定位：",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = log,
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(log)) }) { Text("复制") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/**
 * 一条短暂的操作反馈。
 *
 * 下载是「点完就走」的动作，成败都在通知栏里报告；但点了按钮如果界面上一点动静都没有，
 * 用户会以为没点上，所以这里立刻给一句提示。
 */
private fun toast(context: Context, message: String) {
    runCatching {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }
}
