package com.tom.musicplayer.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.concurrent.futures.ResolvableFuture
import com.google.common.util.concurrent.ListenableFuture
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.tom.musicplayer.MainActivity
import com.tom.musicplayer.R
import com.tom.musicplayer.util.CrashLogger

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    companion object {
        /** 播放链路的诊断日志标签，排查「某首歌播不起来」时用 adb logcat -s 过滤。 */
        const val QT_TAG = "QingTing"
    }

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // 记一笔「服务起来了」。如果上次的标记还在，说明上次是被系统直接收掉的，
        // 而不是走到了 onDestroy —— 下次用户再遇到「App 没了」时能据此区分原因。
        CrashLogger.markServiceStart(this)
        val player = ExoPlayer.Builder(this)
            // 换数据源：本地 content:// 仍走 ContentResolver，网络地址走带鉴权的 OkHttp，
            // 并且套一层磁盘缓存，网络歌曲听过一次就能离线重播。
            .setMediaSourceFactory(PlayerDataSources.mediaSourceFactory(this))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            // 播放期间同时握住 PARTIAL_WAKE_LOCK 和 WiFi 锁。
            // 缺了它，息屏后 CPU 和 WiFi 会先后休眠：本地歌表现为进度卡住，
            // 在线歌表现为缓冲中直接断流 —— 这正是「听着听着自己停了」的典型成因。
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        // 只记错误：某首歌放不出来时，adb logcat -s QingTing 能直接看到原因，
        // 常规切歌不打日志，免得把 logcat 刷满。
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(QT_TAG, "onPlayerError ${error.errorCodeName}: ${error.message}", error)
            }
        })

        val session = MediaSession.Builder(this, player)
            .setBitmapLoader(ArtworkBitmapLoader(applicationContext))
            // 收藏按钮要靠 Session 回调接回来：通知栏只是把命令丢给 Session
            .setCallback(SessionCallback())
            // 先把「收藏」按钮摆到通知栏上（初始按未收藏画），
            // 之后的图标切换由 PlayerController 在切歌 / 收藏变化时刷新
            .setCustomLayout(listOf(favoriteButton(favorite = false)))
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

        mediaSession = session
        PlayerController.attach(this, player)
        PlayerController.attachSession(session)
        // 服务可能比界面先起来（系统恢复播放、通知栏控制等），先把曲库补回来，
        // 否则界面点播放时队列还是空的
        PlayerController.restoreIfNeeded(this, autoPlay = false)
    }

    /** 通知栏上的「收藏」按钮。图标必须是资源 id，系统界面才拿得到。 */
    private fun favoriteButton(favorite: Boolean): CommandButton =
        CommandButton.Builder()
            .setSessionCommand(MediaCommands.toggleFavorite())
            .setIconResId(if (favorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_border)
            .setDisplayName(getString(if (favorite) R.string.cmd_unfavorite else R.string.cmd_favorite))
            .setEnabled(true)
            .build()

    private fun ok(): ListenableFuture<SessionResult> {
        val future = ResolvableFuture.create<SessionResult>()
        future.set(SessionResult(SessionResult.RESULT_SUCCESS))
        return future
    }

    /**
     * 会话回调：放行「收藏」这条自定义命令，并处理它。
     *
     * 注意 [onConnect] 必须把这条 command 放进 SessionCommands，
     * 否则系统界面（通知栏）会认为它不支持，按钮压根不会显示。
     */
    private inner class SessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ConnectionResult {
            val sessionCommands = SessionCommands.Builder()
                .add(MediaCommands.toggleFavorite())
                .build()
            return ConnectionResult.accept(
                sessionCommands,
                ConnectionResult.DEFAULT_PLAYER_COMMANDS
            )
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            // 控制器连上来时补一次，保证通知栏按钮图标与当前曲目一致
            runCatching { PlayerController.pushFavoriteLayout() }
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == MediaCommands.TOGGLE_FAVORITE) {
                // toggleFavoriteCurrent 会直接返回「切换之后」的收藏状态，
                // 写文件是异步的，等 PlayerController 再推一轮就来不及给按钮换图标了
                val added = PlayerController.toggleFavoriteCurrent()
                session.setCustomLayout(listOf(favoriteButton(added)))
                return ok()
            }
            val future = ResolvableFuture.create<SessionResult>()
            future.set(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            return future
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val result = super.onStartCommand(intent, flags, startId)
        // intent 为 null 是「进程被系统回收后由 START_STICKY 重新拉起」的标志。
        // 这种重启必须把队列和进度补回来并接着播，否则音乐就断了 ——
        // 也就是用户反馈的「听着听着自己退出」。
        if (intent == null) {
            Log.i(QT_TAG, "服务被系统重建，开始恢复队列与播放位置")
            PlayerController.restoreIfNeeded(this, autoPlay = true)
        }
        return result
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        // 这里不能用 isPlaying 判断：缓冲中它同样是 false。用户划掉任务卡片的瞬间
        // 恰好碰上缓冲，就会被误判成「没在放」而 stopSelf，音乐应声而断。
        // 只要用户是想听（playWhenReady）且播放没结束，就应当继续活着。
        val ongoing = player != null &&
            player.playWhenReady &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED
        if (ongoing) {
            Log.i(QT_TAG, "任务卡片被划掉，仍在播放，保持服务存活")
        } else {
            stopSelf()
        }
    }

    override fun onLowMemory() {
        // 系统内存吃紧时会先回调这里，再决定是否回收本进程。
        // 记一笔，方便事后对照「App 莫名消失」的时间点。
        Log.w(QT_TAG, "onLowMemory：系统内存紧张，随时可能回收本进程")
        super.onLowMemory()
    }

    override fun onDestroy() {
        // 走到了这里说明是正常销毁，清掉存活标记，下次启动就不会误报「被系统结束」
        CrashLogger.markServiceStop(this)
        PlayerController.detach()
        PlayerController.attachSession(null)
        mediaSession?.let { session ->
            session.player.release()
            session.release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
