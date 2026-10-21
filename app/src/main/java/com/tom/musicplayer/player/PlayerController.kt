@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.tom.musicplayer.player

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.compose.ui.graphics.Color
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.palette.graphics.Palette
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import com.tom.musicplayer.R
import com.tom.musicplayer.data.ArtworkLoader
import com.tom.musicplayer.data.FavoritesStore
import com.tom.musicplayer.data.LibraryCache
import com.tom.musicplayer.data.Prefs
import com.tom.musicplayer.data.ResumeStore
import com.tom.musicplayer.data.online.Downloader
import com.tom.musicplayer.model.PlaybackProgress
import com.tom.musicplayer.model.SleepMode
import com.tom.musicplayer.model.SleepState
import com.tom.musicplayer.model.Song
import com.tom.musicplayer.model.SourceKind
import com.tom.musicplayer.ui.theme.DynamicSeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 播放控制中心（界面与前台服务同进程共享）。
 *
 * 设计要点：
 * 1. 界面永远按「歌曲列表正序」工作，播放顺序由 [PlayMode] 决定，内部维护一条与
 *    ExoPlayer 时间线一一对应的队列 [queueIds]，因此 shuffle 交给 ExoPlayer 也不会错位。
 * 2. 播放器可能随服务重启而换实例，所以 attach() 时总是按模式重建队列，
 *    保证「服务刚起来、还没点播放」时按钮就已经可用。
 * 3. 播放状态以 playWhenReady 为准实时下发，保证按钮图标立刻响应。
 */
object PlayerController {

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _mode = MutableStateFlow(PlayMode.SEQUENTIAL)
    val mode: StateFlow<PlayMode> = _mode.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /**
     * 「接下来要播什么」——按 ExoPlayer 时间线顺序排好的队列，第一项就是正在播的。
     *
     * 界面只认这一份：顺序播是 [queueIds]，随机播是洗过的那条顺序，倒序是反过来的。
     * 以前队列藏在内部（[queueIds]），用户看不到下一首是什么，也没法把某一首拖到前面，
     * 只能反复点「下一首」摸过去。拖动排序改的是 [_songs] 的顺序，队列按新模式重排，
     * 所以拖完的效果是持久的，不是只影响这一次播放。
     */
    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    /** 时间线下标 → 「正在播的那首」在 [queue] 里的位置。 */
    private val _queuePosition = MutableStateFlow(0)
    val queuePosition: StateFlow<Int> = _queuePosition.asStateFlow()

    @Suppress("OPT_IN_USAGE")
    private val _sleep = MutableStateFlow(SleepState())
    val sleep: StateFlow<SleepState> = _sleep.asStateFlow()

    /**
     * 收藏（我的喜欢）的曲目 id 集合。
     * 由控制器统一持有：通知栏那边点一下收藏，界面上的心形必须跟着变，
     * 各自去读文件就会出现「通知里加了、App 里还是空心」的错位。
     */
    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    /**
     * 播放进度（位置 + 时长），由 attach 起的定时器统一推送。
     *
     * 这里必须由控制器持有、而不是界面自己开轮询：界面用 remember(song.id) 存 position 时，
     * 切歌会换掉状态对象，而 LaunchedEffect 里那条只捕获了「首次组合时那个旧对象」的协程
     * 还在往旧对象写，表现为切歌后进度条和歌词永远停在 0。
     */
    private val _progress = MutableStateFlow(PlaybackProgress())
    val progress: StateFlow<PlaybackProgress> = _progress.asStateFlow()

    private var playerRef: ExoPlayer? = null
    val player: ExoPlayer? get() = playerRef

    private var items: List<MediaItem> = emptyList()

    /** 当前下发给 ExoPlayer 的队列（元素为 mediaId，顺序即时间线顺序）。 */
    private var queueIds: List<String> = emptyList()

    private var appContext: Context? = null
    /** 通知栏按钮通过 MediaSession 的 custom layout 下发，留个引用好在收藏变化时刷新。 */
    private var sessionRef: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionSaver: Job? = null
    private var progressTicker: Job? = null
    private var artworkJob: Job? = null
    private var sleepJob: Job? = null
    private var restoreJob: Job? = null
    /** 盯着下载状态，好在通知栏按钮上把「下载」换成「已下载」。 */
    private var downloadWatcher: Job? = null
    private var artworkId: String? = null
    /** 上一次落盘的「是否在播放」，用来避免重复写盘。 */
    private var persistedPlaying: Boolean? = null
    /** 恢复流程进行中：这期间禁止把「未在播放」写盘，见 [pushPlayingState]。 */
    private var restoring = false
    /** 曲目计数定时触发后挂起「停止」意图：避免切歌流程紧接着又把 playWhenReady 打开。 */
    private var stopPending = false
    /** 曲目计数定时的起点，避免同一次播放被重复计数。 */
    private var sleepAnchorId: String? = null

    val currentSong: Song? get() = _songs.value.getOrNull(_currentIndex.value)

    val positionMs: Long get() = playerRef?.currentPosition?.coerceAtLeast(0L) ?: 0L
    val durationMs: Long get() = playerRef?.duration?.coerceAtLeast(0L) ?: 0L

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            pushPlayingState()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            pushPlayingState()
            syncIndex()
            syncDuration()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncIndex()
            pushPlayingState()
            saveProgress()
            refreshArtwork()
            tickTrackSleepTimer(mediaItem?.mediaId)
        }
    }

    /** 播放器就绪（服务 onCreate）；此时队列可能还没下发，这里补上。 */
    fun attach(context: Context, player: ExoPlayer) {
        val app = context.applicationContext
        appContext = app
        // 服务刚起来就把「是否在播放」的写盘通道关掉：此时播放器必然是「未播放」，
        // 一旦写进去就会覆盖掉磁盘上「被系统杀掉时正在播放」的记录，
        // 恢复流程随后读到 false 便不再续播。由 restoreIfNeeded 收尾时解除。
        restoring = true
        _mode.value = Prefs.getMode(app)
        player.addListener(listener)

        if (items.isNotEmpty()) {
            val savedIndex = items.indexOfFirst { it.mediaId == Prefs.getLastSongId(app) }
            val startIndex = if (savedIndex >= 0) savedIndex else 0
            val startPosition = if (savedIndex >= 0) Prefs.getLastPosition(app) else 0L
            applyQueue(player, startIndex, startPosition, play = false)
        } else {
            player.shuffleModeEnabled = false
            player.repeatMode =
                if (_mode.value == PlayMode.REPEAT_ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_ALL
        }

        // 最后赋值：外部一旦拿到 player 引用，队列必然已经就绪
        playerRef = player
        _ready.value = true
        pushPlayingState()
        refreshArtwork()
        syncFavorites(app)

        // 「已下载」集合要读一次磁盘（首次），放后台；读完顺手把通知栏按钮刷新一次，
        // 否则重启后明明下过的歌，通知栏还摆着一个「下载」图标
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { Downloader.refreshDownloadedKeys(app) } }
            pushActionLayout()
        }

        // 下载开始 / 结束 / 失败时，通知栏那个按钮要跟着换样子（下载 → 已下载）。
        // 只订阅「正在下载的 key 集合」的变化，不用进度百分比 —— 否则一次下载要重推上百次布局。
        downloadWatcher?.cancel()
        downloadWatcher = scope.launch {
            Downloader.tasks
                .map { list ->
                    list.filter { !it.done && it.error == null }.map { it.key }.toSet()
                }
                .distinctUntilChanged()
                .collect { pushActionLayout() }
        }

        // 两条后台循环都只在「真的需要」时才干活：
        // 暂停或没人在看界面时继续空转，只会白白消耗 CPU，增加息屏听歌时被系统回收的概率。
        positionSaver?.cancel()
        positionSaver = scope.launch {
            var ticks = 0
            while (isActive) {
                if (playerRef?.isPlaying == true) {
                    saveProgress()
                    // 进度攒 12 秒才落一次盘：播放中没必要频繁写文件
                    if (++ticks % 6 == 0) ResumeStore.flush(app)
                }
                delay(2000)
            }
        }

        progressTicker?.cancel()
        progressTicker = scope.launch {
            while (isActive) {
                // 没有界面在订阅（息屏 / 退到桌面）就别推了，省掉持续的 StateFlow 更新
                if (_progress.subscriptionCount.value > 0) pushProgress()
                delay(250)
            }
        }
    }

    /**
     * 服务被系统回收后重新拉起时，把播放队列补回来。
     *
     * PlayerController 是内存里的单例：进程一死，items 就跟着没了。此时服务虽然活了，
     * 但队列是空的 —— 音乐停、通知消失，用户看到的就是「听着听着自己退出了」。
     * 这里从磁盘缓存把曲库灌回去，并定位到上次听的那首。
     *
     * [autoPlay] 只在「系统回收后重启」时为真：那种情况才该自动续播。
     * 用户自己按了暂停，重启后音乐自作主张响起来会很突然。
     */
    fun restoreIfNeeded(context: Context, autoPlay: Boolean) {
        ensureMainThread()
        val app = context.applicationContext
        appContext = app
        restoreJob?.cancel()
        restoring = true
        restoreJob = scope.launch {
            try {
                // 界面有可能已经抢先把列表灌进来了（服务重启时 Activity 常常也一起重建），
                // 那种情况就不必再补列表，但仍然要判断「该不该接着播」。
                if (items.isEmpty()) {
                    val cached = withContext(Dispatchers.IO) { LibraryCache.load(app) }
                    if (cached.isNotEmpty() && items.isEmpty()) {
                        _songs.value = cached
                        items = cached.map { it.toMediaItem() }
                    }
                }
                val player = playerRef
                if (player == null || items.isEmpty()) return@launch
                // 队列还没建起来才需要重建；界面已经建好的话别动，免得打断它
                if (queueIds.isEmpty()) {
                    val lastId = Prefs.getLastSongId(app)
                    val index = items.indexOfFirst { it.mediaId == lastId }.coerceAtLeast(0)
                    applyQueue(player, index, Prefs.getLastPosition(app), play = false)
                }
                // was_playing 必须在解除写盘封锁之前读，否则读到的就是刚被覆盖过的 false
                val wasPlaying = Prefs.getWasPlaying(app)
                if (autoPlay && wasPlaying && !player.playWhenReady) {
                    player.play()
                }
            } finally {
                // 恢复结束才解除写盘封锁，并补推一次，让 was_playing 与真实状态一致
                restoring = false
                pushPlayingState()
            }
        }
    }

    fun detach() {
        restoreJob?.cancel()
        restoreJob = null
        restoring = false
        persistedPlaying = null
        progressTicker?.cancel()
        progressTicker = null
        _progress.value = PlaybackProgress()
        positionSaver?.cancel()
        positionSaver = null
        sleepJob?.cancel()
        sleepJob = null
        _sleep.value = SleepState()
        saveProgress()
        appContext?.let { runCatching { scope.launch { ResumeStore.flush(it) } } }
        sessionRef = null
        playerRef?.removeListener(listener)
        playerRef = null
        queueIds = emptyList()
        _queue.value = emptyList()
        _queuePosition.value = 0
        _ready.value = false
        _isPlaying.value = false
    }

    /** 更新歌曲库。必须在主线程调用（内部直接操作 ExoPlayer）。 */
    fun setSongs(context: Context, songs: List<Song>) {
        ensureMainThread()
        val app = context.applicationContext
        appContext = app

        // 内容没变化就别重建队列：启动时「磁盘缓存」和「实时扫描」会先后各给一次结果，
        // 重复重建会让正在播放的曲目被重新 prepare，表现为进度短暂卡住。
        if (_songs.value.map { it.id } == songs.map { it.id }) {
            return
        }

        val lastId = Prefs.getLastSongId(app)
        val lastPosition = Prefs.getLastPosition(app)

        _songs.value = songs
        items = songs.map { it.toMediaItem() }
        // 队列跟着列表走；播放器还没起来时也得先给界面一份能看的东西
        if (playerRef == null) _queue.value = songs

        val player = playerRef
        if (player == null) {
            _currentIndex.value = items.indexOfFirst { it.mediaId == lastId }
            return
        }
        val keep = items.indexOfFirst { it.mediaId == lastId }
        val startIndex = if (keep >= 0) keep else 0
        val startPosition = if (keep >= 0) lastPosition else 0L
        applyQueue(player, startIndex, startPosition, play = player.playWhenReady)
    }

    /**
     * 整体替换播放列表并起播（点某个远端目录时用）。
     * 与 [setSongs] 的区别：这里有明确的用户意图，所以不做「内容相同就跳过」的去重。
     */
    fun playSongs(context: Context, songs: List<Song>, startIndex: Int) {
        ensureMainThread()
        val app = context.applicationContext
        appContext = app
        _songs.value = songs
        items = songs.map { it.toMediaItem() }
        val index = startIndex.coerceIn(0, songs.size - 1)
        val player = playerRef
        if (player == null) {
            _currentIndex.value = index
            _queue.value = songs
            _queuePosition.value = index
            return
        }
        applyQueue(player, index, 0L, play = true)
        _currentIndex.value = index
        pushPlayingState()
        saveProgress()
        refreshArtwork()
        // 远端曲目来自一次列目录，代价不低；落盘后重启也能直接回到这个列表。
        // 只认「整份都是 WebDAV」的列表：这个缓存是本地曲库的画像，
        // 让在线歌或点心歌单写进去，下次启动曲库就会被它们顶掉
        if (songs.isNotEmpty() && songs.all { it.kind == SourceKind.WEBDAV }) {
            scope.launch(Dispatchers.IO) {
                runCatching { LibraryCache.save(app, songs) }
            }
        }
    }

    /** 播放指定歌曲（index 为列表正序下标）：这歌以前听过就从上次的位置接着放。 */
    fun playSong(index: Int) {
        ensureMainThread()
        val player = playerRef ?: return
        val target = items.getOrNull(index) ?: return
        val timelineIndex = queueIds.indexOf(target.mediaId)
        val resume = resumePositionFor(target.mediaId)
        stopPending = false
        if (timelineIndex < 0) {
            applyQueue(player, index, resume, play = true)
        } else {
            player.seekTo(timelineIndex, resume)
            startPlayback(player)
        }
        _currentIndex.value = index
        pushPlayingState()
        saveProgress()
        refreshArtwork()
    }

    /** 播放 / 暂停切换。 */
    fun togglePlay() {
        ensureMainThread()
        val player = playerRef ?: return
        // 用户主动操作一律解除定时停止意图
        stopPending = false
        if (player.playWhenReady && player.playbackState != Player.STATE_ENDED) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
            if (items.isEmpty()) return
            if (player.playbackState == Player.STATE_IDLE || queueIds.isEmpty()) {
                applyQueue(player, _currentIndex.value.coerceAtLeast(0), 0L, play = true)
            } else {
                startPlayback(player)
            }
        }
        pushPlayingState()
        saveProgress()
    }

    fun skipNext() {
        ensureMainThread()
        stopPending = false
        val player = playerRef ?: return
        if (items.isEmpty()) return
        if (queueIds.isEmpty() || player.playbackState == Player.STATE_IDLE) {
            val index = _currentIndex.value.coerceAtLeast(0)
            applyQueue(player, nextFromIndex(index), 0L, play = true)
            return
        }
        val timeline = player.currentMediaItemIndex.coerceIn(0, queueIds.size - 1)
        if (timeline >= queueIds.size - 1) {
            if (_mode.value == PlayMode.SHUFFLE) {
                // 一轮随机播完 → 重新洗牌
                applyQueue(player, (0 until items.size).random(), 0L, play = true)
            } else {
                player.seekTo(0, 0)
                startPlayback(player)
            }
        } else {
            player.seekTo(timeline + 1, 0)
            startPlayback(player)
        }
        syncIndex()
        pushPlayingState()
        saveProgress()
        refreshArtwork()
    }

    fun skipPrevious() {
        ensureMainThread()
        stopPending = false
        val player = playerRef ?: return
        if (items.isEmpty()) return
        if (queueIds.isEmpty() || player.playbackState == Player.STATE_IDLE) {
            val index = _currentIndex.value.coerceAtLeast(0)
            applyQueue(player, previousFromIndex(index), 0L, play = true)
            return
        }
        val timeline = player.currentMediaItemIndex.coerceIn(0, queueIds.size - 1)
        if (timeline <= 0) {
            player.seekTo((queueIds.size - 1).coerceAtLeast(0), 0)
        } else {
            player.seekTo(timeline - 1, 0)
        }
        startPlayback(player)
        syncIndex()
        pushPlayingState()
        saveProgress()
        refreshArtwork()
    }

    fun setMode(mode: PlayMode) {
        ensureMainThread()
        appContext?.let { Prefs.saveMode(it, mode) }
        val player = playerRef
        if (player == null) {
            _mode.value = mode
            return
        }
        if (_mode.value == mode && queueIds.isNotEmpty()) return
        _mode.value = mode
        val index = _currentIndex.value.coerceAtLeast(0)
        val position = player.currentPosition
        val wasPlaying = player.playWhenReady
        if (items.isEmpty()) {
            player.shuffleModeEnabled = false
            player.repeatMode =
                if (mode == PlayMode.REPEAT_ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_ALL
            return
        }
        applyQueue(player, index, position, play = wasPlaying)
    }

    fun cycleMode() = setMode(_mode.value.next())

    // ------------------------------------------------------------------ 收藏

    /** 服务建好 MediaSession 后登记进来，之后才能把按钮下发到通知栏。 */
    fun attachSession(session: MediaSession?) {
        ensureMainThread()
        sessionRef = session
        pushActionLayout()
    }

    /** 从磁盘重读一次收藏列表：界面启动时、或收藏可能被别处改过时用。 */
    fun syncFavorites(context: Context) {
        val app = context.applicationContext
        appContext = app
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { FavoritesStore.all(app) }.getOrDefault(emptySet())
            }
            _favorites.value = loaded
            pushFavoriteLayout()
        }
    }

    /** 收藏 / 取消收藏指定曲目。切歌或通知栏点击后，UI 与通知按钮都会同步刷新。 */
    fun toggleFavorite(song: Song) {
        val app = appContext ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { FavoritesStore.toggle(app, song) } }
            _favorites.value = withContext(Dispatchers.IO) {
                runCatching { FavoritesStore.all(app) }.getOrDefault(emptySet())
            }
            pushFavoriteLayout()
        }
    }

    /** 通知栏按钮走这里：收藏「正在播放的那一首」，返回切换后的收藏状态。 */
    fun toggleFavoriteCurrent(): Boolean {
        ensureMainThread()
        val song = currentPlayingSong() ?: return false
        val added = !isFavorite(song)
        toggleFavorite(song)
        return added
    }

    /**
     * 一次取消多条点心。
     *
     * 歌单里的一行可能是两条记录合并出来的（同一份文件在下载页和曲库各点心过一次），
     * 逐条 [toggleFavorite] 会把第二条当成「再加一次」，所以这里直接按 key 删。
     */
    fun removeFavorites(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val app = appContext ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { FavoritesStore.remove(app, keys) } }
            _favorites.value = withContext(Dispatchers.IO) {
                runCatching { FavoritesStore.all(app) }.getOrDefault(emptySet())
            }
            pushFavoriteLayout()
        }
    }

    /** 心形图标亮不亮。按 [FavoritesStore.keyOf] 判定，下载版和在线版算同一首。 */
    fun isFavorite(song: Song): Boolean = FavoritesStore.keyOf(song) in _favorites.value

    fun isFavoriteKey(key: String): Boolean = key in _favorites.value

    /** 正在播放的那一首的 id（队列优先，队列还没建好时退回列表下标）。 */
    private fun currentFavoriteId(): String? =
        queueIds.getOrNull(playerRef?.currentMediaItemIndex ?: -1) ?: currentSong?.id

    /**
     * 把「收藏 / 下载」两个按钮下发给通知栏。
     *
     * 图标跟着当前曲目的状态走，所以切歌、收藏变化、下载状态变化时都要重推一次，
     * 否则会看到「上一首是实心心形，切歌后明明没收藏却还是实心」这种错位。
     * 本地歌没有「下载」的概念，此时只下发收藏一个按钮。
     */
    internal fun pushActionLayout() {
        val session = sessionRef ?: return
        val song = currentPlayingSong()
        val favorite = song?.let { isFavorite(it) } ?: false
        val buttons = ArrayList<CommandButton>(2)
        buttons.add(NotificationActions.favorite(favorite))
        NotificationActions.downloadStateOf(song)?.let {
            buttons.add(NotificationActions.download(it))
        }
        runCatching { session.setCustomLayout(buttons) }
    }

    /** 兼容旧调用点：只刷新收藏图标时也用得到。 */
    internal fun pushFavoriteLayout() = pushActionLayout()

    /**
     * 当前正在播放的那一首的完整信息。
     *
     * 优先按播放器的 mediaId 去队列里找：用户拖动进度条、切歌瞬间，
     * [_currentIndex] 有几十毫秒是滞后的，按 id 找才拿得到正在响的那首。
     */
    private fun currentPlayingSong(): Song? {
        val id = currentFavoriteId() ?: return currentSong
        return _songs.value.firstOrNull { it.id == id } ?: currentSong
    }

    /**
     * 通知栏点「下载」：把正在播的这首下下来。
     *
     * 这个入口完全没有界面 —— 用户可能在锁屏或车机上点的，
     * 所以下载任务必须由 [Downloader] 自己在后台跑完，不依赖任何协程作用域。
     *
     * @return 真的开始了下载返回 true
     */
    fun downloadCurrent(): Boolean {
        ensureMainThread()
        val app = appContext ?: return false
        val song = currentPlayingSong() ?: return false
        val started = Downloader.downloadFromSong(app, song)
        // 立刻刷一次：按钮马上变成「正在下载」，用户点了有反应
        if (started) pushActionLayout()
        return started
    }

    fun seekTo(positionMs: Long) {
        ensureMainThread()
        playerRef?.seekTo(positionMs.coerceAtLeast(0))
        // 立刻推一次：不然拖动结束后最多要等 250ms 才回弹到正确位置
        pushProgress()
    }

    /** 读一次播放器真实进度并广播给界面。时长未知时给 0，界面会退回用元数据里的时长。 */
    private fun pushProgress() {
        val player = playerRef ?: return
        _progress.value = PlaybackProgress(
            mediaId = player.currentMediaItem?.mediaId,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.coerceAtLeast(0L)
        )
    }

    /** 睡眠定时：到点自动暂停。传 null 表示取消。 */
    fun setSleepMinutes(minutes: Int?) {
        ensureMainThread()
        sleepJob?.cancel()
        sleepJob = null
        if (minutes == null || minutes <= 0) {
            _sleep.value = SleepState()
            return
        }
        var left = minutes * 60L
        _sleep.value = SleepState(SleepMode.MINUTES, left, 0)
        sleepJob = scope.launch {
            while (isActive && left > 0) {
                delay(1000)
                left -= 1
                _sleep.value = SleepState(SleepMode.MINUTES, left, 0)
            }
            if (isActive && left <= 0L) {
                _sleep.value = SleepState()
                playerRef?.pause()
                pushPlayingState()
            }
        }
    }

    /** 睡眠定时：再播完 [tracks] 首之后停止。 */
    fun setSleepTracks(tracks: Int) {
        ensureMainThread()
        sleepJob?.cancel()
        sleepJob = null
        if (tracks <= 0) {
            _sleep.value = SleepState()
            return
        }
        sleepAnchorId = queueIds.getOrNull(playerRef?.currentMediaItemIndex ?: -1)
        _sleep.value = SleepState(SleepMode.TRACKS, 0L, tracks)
    }

    fun cancelSleepTimer() = setSleepMinutes(null)

    /** 曲目计数：只有真的换了一首歌才递减。 */
    private fun tickTrackSleepTimer(newId: String?) {
        val state = _sleep.value
        if (state.mode != SleepMode.TRACKS) return
        if (newId.isNullOrBlank() || newId == sleepAnchorId) return
        sleepAnchorId = newId
        val left = state.remainingTracks - 1
        if (left <= 0) {
            _sleep.value = SleepState()
            stopPending = true
            playerRef?.pause()
            pushPlayingState()
        } else {
            _sleep.value = state.copy(remainingTracks = left)
        }
    }

    /**
     * 这首歌上次听到哪儿了。
     * 太靠前（< 3 秒）不值得恢复，已经听到结尾的则从头开始，否则一按播放就跳到下一首。
     */
    private fun resumePositionFor(songId: String): Long {
        val app = appContext ?: return 0L
        val saved = ResumeStore.get(app, songId)
        if (saved < 3_000L) return 0L
        val duration = _songs.value.firstOrNull { it.id == songId }?.durationMs ?: 0L
        if (duration > 0L && saved >= duration - 1_500L) return 0L
        return saved
    }

    /** 把当前进度与曲目写盘，供下次启动恢复。 */
    fun saveProgress() {
        val context = appContext ?: return
        val song = currentSong ?: return
        Prefs.saveLastSongId(context, song.id)
        val player = playerRef
        if (player != null) {
            val current = player.currentPosition.coerceAtLeast(0)
            Prefs.saveLastPosition(context, current)
            ResumeStore.mark(context, song.id, current)
        }
    }

    // ---------------------------------------------------------------- 内部实现

    /**
     * 给「当前播放的曲目」补上内嵌封面数据（artworkData）。
     *
     * 列表里上千首歌不可能全部预解码，所以只在切歌时按需解码这一首的封面（采样后压成 JPEG），
     * 通过 replaceMediaItem 更新队列里的那一项，mediaId 不变所以播放不中断。
     * 这样通知栏 / 锁屏 / 车机等外部界面才能直接拿到 Bitmap 显示封面。
     */
    private fun refreshArtwork() {
        val context = appContext ?: return
        val player = playerRef ?: return
        val id = queueIds.getOrNull(player.currentMediaItemIndex) ?: return
        if (id == artworkId) return
        // 歌曲库可能还没加载完（服务先于扫描启动），此时先不标记，等下一次事件再补
        val song = _songs.value.firstOrNull { it.id == id } ?: return
        // 在线曲目：封面是音源给的一个 http 地址，直接拿来用，不用解码音频文件
        if (song.isOnline) {
            artworkId = id
            val cover = song.coverUrl?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
            if (cover == null) {
                DynamicSeed.reset()
                return
            }
            artworkJob?.cancel()
            artworkJob = scope.launch {
                // 在线封面也能取主色，和本地歌一个待遇
                withContext(Dispatchers.IO) { extractSeedColorFromUrl(cover.toString()) }
                val current = playerRef ?: return@launch
                val index = queueIds.indexOf(id)
                if (index < 0 || current.currentMediaItemIndex != index) return@launch
                if (current.currentMediaItem?.mediaMetadata?.artworkUri == null) {
                    runCatching { current.replaceMediaItem(index, song.toMediaItem(cover)) }
                }
            }
            return
        }
        // WebDAV 曲目不在这里解内嵌封面：那相当于为了封面把整首歌先拉一遍，太贵
        if (song.isRemote) {
            artworkId = id
            DynamicSeed.reset()
            return
        }
        artworkJob?.cancel()
        artworkJob = scope.launch {
            val artUri = withContext(Dispatchers.IO) {
                ArtworkLoader.coverUri(context, song.uri, 512)
            }
            if (artUri == null) {
                artworkId = id
                // 这首歌没有封面，别沿用上一首的颜色
                DynamicSeed.reset()
                return@launch
            }
            grantArtwork(context, artUri)
            // 顺手从封面取主色给界面换色（128px 缩略图足够，Palette 是同步 API，放在 IO 线程跑）
            withContext(Dispatchers.IO) { extractSeedColor(context, song.uri) }
            // 队列项里已经有封面就别再 replace 了：replaceMediaItem 会让正在播放的这一首重新缓冲
            val current = playerRef ?: return@launch
            val index = queueIds.indexOf(id)
            if (index < 0 || current.currentMediaItemIndex != index) return@launch
            if (current.currentMediaItem?.mediaMetadata?.artworkUri == null) {
                runCatching { current.replaceMediaItem(index, song.toMediaItem(artUri)) }
            }
            artworkId = id
        }
    }

    /** 在线封面取主色：图片来自音源给的 http 地址。 */
    private fun extractSeedColorFromUrl(url: String) {
        val bitmap = ArtworkLoader.remoteBitmap(url, 128) ?: return
        applySeed(bitmap)
    }

    /**
     * 从封面提取主色调给界面换色。
     * 过黑或过白的封面直接忽略：那种颜色当主色会让按钮和文字都变得难读。
     */
    private fun extractSeedColor(context: Context, audioUri: Uri) {
        val bitmap = ArtworkLoader.bitmap(context, audioUri, 128) ?: return
        applySeed(bitmap)
    }

    private fun applySeed(bitmap: Bitmap) {
        val palette = runCatching { Palette.from(bitmap).generate() }.getOrNull() ?: return
        val swatch = palette.vibrantSwatch
            ?: palette.lightVibrantSwatch
            ?: palette.mutedSwatch
            ?: palette.dominantSwatch
            ?: return
        val lightness = swatch.hsl.getOrNull(2) ?: return
        if (lightness < 0.18f || lightness > 0.92f) return
        DynamicSeed.update(Color(swatch.rgb))
    }

    /**
     * 把封面图片的读权限授给系统界面：通知栏媒体卡片是 SystemUI 自己去读 artworkUri 的。
     * 该授权不持久，且只对具体 URI 生效，所以每次切歌都重新授一次。
     */
    private fun grantArtwork(context: Context, artUri: Uri) {
        listOf("com.android.systemui").forEach { pkg ->
            runCatching {
                context.grantUriPermission(
                    pkg,
                    artUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    private fun nextFromIndex(index: Int): Int {
        if (items.isEmpty()) return 0
        return if (index >= items.size - 1) 0 else (index + 1)
    }

    private fun previousFromIndex(index: Int): Int {
        if (items.isEmpty()) return 0
        return if (index <= 0) items.size - 1 else (index - 1)
    }

    private fun startPlayback(player: ExoPlayer) {
        // 定时刚好在这一次切歌时到期：这里必须尊重「停止」，否则刚暂停就被重新播放盖掉
        player.playWhenReady = !stopPending
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
    }

    /** 按当前播放模式重建队列并定位到 [startSongIndex]（列表正序下标）。 */
    private fun applyQueue(
        player: ExoPlayer,
        startSongIndex: Int,
        positionMs: Long,
        play: Boolean
    ) {
        if (items.isEmpty()) {
            player.clearMediaItems()
            queueIds = emptyList()
            _currentIndex.value = -1
            pushPlayingState()
            return
        }
        val index = startSongIndex.coerceIn(0, items.size - 1)
        val startId = items[index].mediaId
        val order = buildOrder(startId)
        val queued = order.mapNotNull { id -> items.firstOrNull { it.mediaId == id } }
        // 起点必须在 order 里定位，不能用 index —— 倒序模式听第一首时它其实排在队尾
        val timelineStart = order.indexOf(startId).coerceAtLeast(0)

        player.shuffleModeEnabled = false
        player.repeatMode =
            if (_mode.value == PlayMode.REPEAT_ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_ALL
        player.setMediaItems(queued, timelineStart, positionMs)
        player.prepare()
        player.playWhenReady = play

        queueIds = order
        _currentIndex.value = index
        pushQueue()
        pushPlayingState()
        refreshArtwork()
    }

    /**
     * 把时间线顺序翻译成「给用户看的队列」。
     *
     * 随机播时这是洗过的顺序、倒序时是反过来的，所以不能直接拿 [_songs] 显示 ——
     * 那样用户看到的下一首和真正会响的那首对不上。队里找不到的歌（曲库刚换过）跳过。
     */
    private fun pushQueue() {
        val byId = _songs.value.associateBy { it.id }
        _queue.value = queueIds.mapNotNull { byId[it] }
        val index = playerRef?.currentMediaItemIndex ?: -1
        _queuePosition.value = if (index in queueIds.indices) index else 0
    }

    private fun pushQueuePosition() {
        val index = playerRef?.currentMediaItemIndex ?: return
        _queuePosition.value = index.coerceIn(0, (queueIds.size - 1).coerceAtLeast(0))
    }

    /**
     * 队列里点某一首：直接跳过去播。
     *
     * 传的是队列下标（也就是时间线下标），不是 [_songs] 的下标 ——
     * 随机播时两者完全不同，混用会跳错歌。
     */
    fun playFromQueue(position: Int) {
        ensureMainThread()
        val player = playerRef ?: return
        if (position !in queueIds.indices) return
        stopPending = false
        val targetId = queueIds[position]
        val resume = if (position == (player.currentMediaItemIndex)) player.currentPosition else 0L
        player.seekTo(position, resume)
        startPlayback(player)
        val songIndex = items.indexOfFirst { it.mediaId == targetId }
        if (songIndex >= 0) _currentIndex.value = songIndex
        pushQueuePosition()
        pushPlayingState()
        saveProgress()
        refreshArtwork()
    }

    /**
     * 拖动排序：把队列里的 [from] 挪到 [to]。
     *
     * 改的是播放列表本身（[_songs]）的顺序，然后按当前模式重排时间线，
     * 再定位回「刚才在听的还是刚才在听的那首」。这样：
     * - 顺序播下，你拖到前面的那首真的会下一首就响；
     * - 随机播下，这一轮的时间线也按新顺序重建（不动 [_songs] 的话下次又乱）；
     * - 正在播的那首不会被拖动打断，进度也不会跳。
     */
    fun moveInQueue(from: Int, to: Int) {
        ensureMainThread()
        val player = playerRef
        val currentId = queueIds.getOrNull(player?.currentMediaItemIndex ?: -1)
        val ordered = _queue.value
        if (from !in ordered.indices || to !in ordered.indices || from == to) return
        val moved = ordered.toMutableList()
        moved.add(to, moved.removeAt(from))
        // 队列里没出现的歌（曲库刚换过、正在加载）保持原样跟在后面，别被这条操作弄丢
        val inQueue = moved.mapTo(HashSet()) { it.id }
        val rest = _songs.value.filterNot { it.id in inQueue }
        val songs = moved + rest
        _songs.value = songs
        items = songs.map { it.toMediaItem() }

        if (player == null) {
            rememberQueueOrder(songs)
            return
        }
        val keepId = currentId ?: songs.firstOrNull()?.id ?: return
        val keepSong = songs.firstOrNull { it.id == keepId }
        val keepIndex = keepSong?.let { songs.indexOfFirst { s -> s.id == it.id } } ?: 0
        // 正在播的那首如果正好被拖动了，进度得接着往下走，不能回 0
        val position = if (player.currentMediaItemIndex >= 0) player.currentPosition else 0L
        val wasPlaying = player.playWhenReady
        applyQueue(player, keepIndex, position, play = wasPlaying)
        // 队列顺序变了，但「上次听到哪」这条记录不该被改写
        if (keepSong != null) Prefs.saveLastSongId(appContext ?: return, keepSong.id)
    }

    /** 没有播放器时（服务还没起来）也要记住新顺序，下次起播就是它。 */
    private fun rememberQueueOrder(songs: List<Song>) {
        appContext?.let { LibraryCache.save(it, songs) }
        val keep = currentSong
        if (keep != null) _currentIndex.value = songs.indexOfFirst { it.id == keep.id }.coerceAtLeast(0)
        _queue.value = songs
        _queuePosition.value = _currentIndex.value.coerceAtLeast(0)
    }

    /** 依据播放模式生成 mediaId 顺序；随机/倒序都以当前曲目打头，切换模式不跑偏。 */
    private fun buildOrder(startId: String): List<String> {
        val ids = items.map { it.mediaId }
        return when (_mode.value) {
            PlayMode.SEQUENTIAL, PlayMode.REPEAT_ONE -> ids
            PlayMode.REVERSE -> ids.asReversed()
            PlayMode.SHUFFLE -> {
                val shuffled = ids.shuffled()
                val i = shuffled.indexOf(startId)
                if (i > 0) {
                    listOf(shuffled[i]) + shuffled.take(i) + shuffled.drop(i + 1)
                } else {
                    shuffled
                }
            }
        }
    }

    private fun syncIndex() {
        val player = playerRef
        if (player == null || queueIds.isEmpty() || items.isEmpty()) return
        val id = queueIds.getOrNull(player.currentMediaItemIndex)
            ?: player.currentMediaItem?.mediaId
            ?: return
        val index = items.indexOfFirst { it.mediaId == id }
        if (index >= 0) _currentIndex.value = index
        pushQueuePosition()
        pushFavoriteLayout()
    }

    /**
     * 远端曲目的时长在扫描阶段是拿不到的（列目录不会给时长），
     * 所以等 ExoPlayer 解析完头文件后回填一次，列表上的时长也就跟着补齐了。
     */
    private fun syncDuration() {
        val player = playerRef ?: return
        val duration = player.duration
        if (duration <= 0L || duration == C.TIME_UNSET) return
        val index = _currentIndex.value
        if (index < 0) return
        val song = _songs.value.getOrNull(index) ?: return
        if (song.durationMs > 0) return
        val updated = _songs.value.toMutableList()
        updated[index] = song.copy(durationMs = duration)
        _songs.value = updated
    }

    private fun pushPlayingState() {
        val player = playerRef
        val playing = player != null &&
            player.playWhenReady &&
            player.playbackState != Player.STATE_IDLE
        _isPlaying.value = playing
        // 落盘「是否在播放」，供服务被系统回收后判断是否该自动续播。
        // 只在真的翻转时写，播放中这个值几秒就会被检查一次。
        val app = appContext ?: return
        // 恢复流程跑完之前绝不写盘：此刻播放器必然是「未播放」，
        // 一旦写进去就把「被系统杀掉时正在播放」这条记录冲掉了，
        // 紧接着的恢复逻辑读到 false 便不再续播 —— 正是「听着听着自己退出」的成因。
        if (restoring) return
        if (persistedPlaying != playing) {
            persistedPlaying = playing
            Prefs.saveWasPlaying(app, playing)
        }
    }

    private fun ensureMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "PlayerController 必须在主线程调用"
        }
    }

    private fun Song.toMediaItem(artworkUri: Uri? = null): MediaItem {
        // artworkUri 必须是「图片」URI（这里指向我们导出的封面缩略图）：
        // 通知栏 / 锁屏会直接把 artworkUri 当图片解码，给它音频文件 Uri 只会解码失败。
        // 在线歌曲的封面本来就是音源给的 http 图片地址，可以直接拿去用。
        val art = artworkUri
            ?: coverUrl?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .apply {
                if (art != null) setArtworkUri(art)
            }
            .build()
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(id)
            .setMediaMetadata(metadata)
            .build()
    }
}
