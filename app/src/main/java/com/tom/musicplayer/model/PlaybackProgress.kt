package com.tom.musicplayer.model

/**
 * 播放进度快照。
 *
 * [mediaId] 用来标记这份进度属于哪一曲：切歌瞬间播放器可能还报着上一首的位置，
 * 界面据此判断是否采用，避免新歌一上来进度条先闪一下「上一首的尾巴」。
 */
data class PlaybackProgress(
    val mediaId: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
)
