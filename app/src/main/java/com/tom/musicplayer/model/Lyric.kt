package com.tom.musicplayer.model

import android.net.Uri

/** 歌词来源，用于在界面上标明这是哪里来的、是否可以微调。 */
enum class LyricSource(val label: String) {
    EMBEDDED_FILE("本地歌词文件"),
    DOWNLOADED("已下载歌词"),
    NONE("无歌词")
}

/** 一行歌词。 */
data class LyricLine(
    val timeMs: Long,
    val text: String
)

/** 在线搜索到的候选条目。 */
data class LyricCandidate(
    val id: Long,
    val trackName: String,
    val artistName: String,
    val albumName: String,
    val durationSec: Double,
    val plainLyrics: String,
    val syncedLyrics: String?
) {
    val hasTimeline: Boolean get() = syncedLyrics.isNullOrBlank().not()
}

/** 播放页要展示的歌词状态。 */
data class Lyrics(
    val lines: List<LyricLine>,
    val source: LyricSource,
    val offsetMs: Long = 0L
) {
    val isEmpty: Boolean get() = lines.isEmpty()
}
