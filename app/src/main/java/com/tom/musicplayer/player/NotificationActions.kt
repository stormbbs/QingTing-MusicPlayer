package com.tom.musicplayer.player

import androidx.media3.session.CommandButton
import com.tom.musicplayer.R
import com.tom.musicplayer.data.online.Downloader
import com.tom.musicplayer.model.Song

/**
 * 通知栏（以及锁屏、车机、手表）上那几个自定义按钮的统一构建处。
 *
 * 两处都要下发同一批按钮：PlaybackService 建 Session 时铺一次初版，
 * PlayerController 在切歌 / 收藏 / 下载状态变化时再推一次。
 * 以前这段代码在两边各写一份，加按钮时很容易只改到一处 ——
 * 图标和文案到这里来取，就不会再对不上了。
 */
object NotificationActions {

    /** 下载按钮的三种样子。 */
    enum class DownloadState { READY, BUSY, DONE }

    fun favorite(favorite: Boolean): CommandButton =
        CommandButton.Builder()
            .setSessionCommand(MediaCommands.toggleFavorite())
            .setIconResId(if (favorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_border)
            .setDisplayName(if (favorite) "取消点心" else "点心")
            .setEnabled(true)
            .build()

    fun download(state: DownloadState): CommandButton {
        val icon = when (state) {
            DownloadState.READY -> R.drawable.ic_download
            DownloadState.BUSY -> R.drawable.ic_download
            DownloadState.DONE -> R.drawable.ic_download_done
        }
        val name = when (state) {
            DownloadState.READY -> "下载这首歌"
            DownloadState.BUSY -> "正在下载"
            DownloadState.DONE -> "已下载"
        }
        return CommandButton.Builder()
            .setSessionCommand(MediaCommands.downloadCurrent())
            .setIconResId(icon)
            .setDisplayName(name)
            // 已经下载完 / 正在下的就不该再点，但按钮本身留着 ——
            // 直接抽掉会让通知栏按钮位置来回跳，看着像点错了
            .setEnabled(state == DownloadState.READY)
            .build()
    }

    /**
     * 当前这首该显示什么下载按钮。返回 null 表示根本不显示：
     * 本地 / WebDAV 的歌本来就在手上，放个下载键只会让人困惑。
     */
    fun downloadStateOf(song: Song?): DownloadState? {
        if (song == null || !song.isOnline) return null
        val key = Downloader.taskKey(song.onlineSourceId, song.onlineSongId) ?: return null
        val busy = Downloader.tasks.value.any { it.key == key && !it.done && it.error == null }
        if (busy) return DownloadState.BUSY
        if (key in Downloader.downloadedKeys.value) return DownloadState.DONE
        return DownloadState.READY
    }
}
