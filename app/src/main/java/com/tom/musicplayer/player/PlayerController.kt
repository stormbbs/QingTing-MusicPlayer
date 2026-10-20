@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.tom.musicplayer.player

import android.content.Context
import android.content.Intent
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
import com.tom.musicplayer.model.PlaybackProgress
import com.tom.musicplayer.model.SleepMode
import com.tom.musicplayer.model.SleepState
import com.tom.musicplayer.model.Song
import com.tom.musicplayer.ui.theme.DynamicSeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
            return
        }
        applyQueue(player, index, 0L, play = true)
        _currentIndex.value = index
        pushPlayingState()
        saveProgress()
        refreshArtwork()
        // 远端曲目来自一次列目录，代价不低；落盘后重启也能直接回到这个列表
        if (songs.any { it.isRemote }) {
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

    /** 服务建好 MediaSession 后登记进来，之后才能把收藏按钮下发到通知栏。 */
    fun attachSession(session: MediaSession?) {
        ensureMainThread()
        sessionRef = session
        pushFavoriteLayout()
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
    fun toggleFavorite(songId: String) {
        val app = appContext ?: return
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { FavoritesStore.toggle(app, songId) } }
            _favorites.value = withContext(Dispatchers.IO) {
                runCatching { FavoritesStore.all(app) }.getOrDefault(emptySet())
            }
            pushFavoriteLayout()
        }
    }

    /** 通知栏按钮走这里：收藏「正在播放的那一首」，返回切换后的收藏状态。 */
    fun toggleFavoriteCurrent(): Boolean {
        ensureMainThread()
        val id = currentFavoriteId() ?: return false
        val added = id !in _favorites.value
        toggleFavorite(id)
        return added
    }

    fun isFavorite(songId: String): Boolean = songId in _favorites.value

    /** 正在播放的那一首的 id（队列优先，队列还没建好时退回列表下标）。 */
    private fun currentFavoriteId(): String? =
        queueIds.getOrNull(playerRef?.currentMediaItemIndex ?: -1) ?: currentSong?.id

    /**
     * 把「收藏 / 取消收藏」下发给通知栏。
     * 图标跟着当前曲目的收藏状态走，所以切歌时必须重新推一次，
     * 否则会看到「上一首是实心心形，切歌后明明没收藏却还是实心」。
     */
    internal fun pushFavoriteLayout() {
        val session = sessionRef ?: return
        val favorite = currentFavoriteId()?.let { it in _favorites.value } ?: false
        val button = CommandButton.Builder()
            .setSessionCommand(MediaCommands.toggleFavorite())
            .setIconResId(if (favorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_border)
            .setDisplayName(if (favorite) "取消收藏" else "收藏")
            .setEnabled(true)
            .build()
        runCatching { session.setCustomLayout(listOf(button)) }
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
        // 远端曲目不在这里解内嵌封面：那相当于为了封面把整首歌先拉一遍，太贵
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

    /**
     * 从封面提取主色调给界面换色。
     * 过黑或过白的封面直接忽略：那种颜色当主色会让按钮和文字都变得难读。
     */
    private fun extractSeedColor(context: Context, audioUri: Uri) {
        val bitmap = ArtworkLoader.bitmap(context, audioUri, 128) ?: return
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
        pushPlayingState()
        refreshArtwork()
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
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .apply {
                if (artworkUri != null) setArtworkUri(artworkUri)
            }
            .build()
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaId(id)
            .setMediaMetadata(metadata)
            .build()
    }
}
