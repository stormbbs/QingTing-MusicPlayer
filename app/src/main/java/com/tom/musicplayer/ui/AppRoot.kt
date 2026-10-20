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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import com.tom.musicplayer.model.Song
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

    val songs by PlayerController.songs.collectAsStateWithLifecycle()
    val currentIndex by PlayerController.currentIndex.collectAsStateWithLifecycle()
    val isPlaying by PlayerController.isPlaying.collectAsStateWithLifecycle()
    val mode by PlayerController.mode.collectAsStateWithLifecycle()
    // 收藏统一由控制器持有：通知栏点一下收藏，这里（列表计数、播放页心形）立刻跟着变
    val favorites by PlayerController.favorites.collectAsStateWithLifecycle()

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
                // 用户正在听网络曲库时，别让本地扫描结果把远端列表顶掉
                if (PlayerController.songs.value.none { it.isRemote }) {
                    runCatching { PlayerController.setSongs(context, result) }
                    withContext(Dispatchers.IO) { LibraryCache.save(context, result) }
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
        // 服务可能还没起来（界面先启动），所以这里主动把磁盘上的收藏读一次
        PlayerController.syncFavorites(context)

        // 1) 先用磁盘缓存把列表立刻顶起来，防止重启瞬间显示空白 + 要求重选文件夹
        val cached = withContext(Dispatchers.IO) { LibraryCache.load(context) }
        if (cached.isNotEmpty() && PlayerController.songs.value.isEmpty()) {
            runCatching { PlayerController.setSongs(context, cached) }
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

    Box(modifier = Modifier.fillMaxSize()) {
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
            AppScreen.LIBRARY -> LibraryScreen(
                songs = songs,
                currentIndex = currentIndex,
                isPlaying = isPlaying,
                folderLabel = sourceLabel(treeUri, songs),
                scanning = scanning,
                hasFolder = hasFolder,
                folderUnreadable = folderUnreadable,
                mode = mode,
                onPickFolder = { folderPicker.launch(null) },
                onOpenSources = { screen = AppScreen.SOURCES },
                onSongClick = { index ->
                    scope.launch {
                        withPlayer(context) { PlayerController.playSong(index) }
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
                onToggleFavorite = { song -> PlayerController.toggleFavorite(song.id) },
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
        }

        AnimatedVisibility(
            visible = showPlayer && currentSong != null,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            val song = currentSong
            if (song != null) {
                PlayerScreen(
                    song = song,
                    isPlaying = isPlaying,
                    isFavorite = song.id in favorites,
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
                    onToggleFavorite = { PlayerController.toggleFavorite(song.id) }
                )
            }
        }
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

    BackHandler(enabled = showPlayer) { showPlayer = false }
}

private enum class AppScreen { LIBRARY, SOURCES }

/** 列表副标题：本地曲库显示文件夹名，网络曲库显示来源提示。 */
private fun sourceLabel(treeUri: Uri?, songs: List<Song>): String? {
    if (songs.any { it.isRemote }) return "网络曲库 · WebDAV"
    return treeUri?.let { MusicScanner.folderName(it) }
}

/**
 * 确保播放服务活着再执行控制指令：服务可能被系统回收（暂停/息屏时很常见），
 * 重新拉起后控制器会自动按播放模式恢复队列，按钮因此可以立刻生效。
 */
private suspend fun withPlayer(context: Context, action: () -> Unit) {
    var waited = 0L
    while (PlayerController.player == null && waited < 3000L) {
        runCatching { context.startService(Intent(context, PlaybackService::class.java)) }
        delay(150)
        waited += 150
    }
    if (PlayerController.player == null) return
    runCatching { action() }
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
