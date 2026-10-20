package com.tom.musicplayer.player

import android.os.Bundle
import androidx.media3.session.SessionCommand

/**
 * 界面与通知栏之间约定的「自定义命令」。
 *
 * Media3 的 MediaSession 允许 App 自己定义动作按钮（custom layout），
 * 通知栏、锁屏甚至车机都会把它当成一组额外按钮显示出来 ——
 * 「收藏当前这首歌」就走这条路，不用进 App 也能直接点。
 */
object MediaCommands {

    const val TOGGLE_FAVORITE = "com.tom.musicplayer.TOGGLE_FAVORITE"

    fun toggleFavorite(): SessionCommand = SessionCommand(TOGGLE_FAVORITE, Bundle.EMPTY)
}
