package com.tom.musicplayer.model

import android.net.Uri

/**
 * 歌曲来源：本地 SAF 文件、WebDAV 远端文件，或在线音源。
 *
 * ONLINE 的歌没有固定地址 —— [Song.uri] 是一个 `qingting://` 占位符，
 * 真正的直链在播放器要读数据时才向音源解析（直链普遍有时效，提前拿到放着会过期）。
 */
enum class SourceKind(val value: String) {
    LOCAL("local"),
    WEBDAV("webdav"),
    ONLINE("online");

    companion object {
        fun parse(raw: String?): SourceKind =
            entries.firstOrNull { it.value == raw } ?: LOCAL
    }
}

data class Song(
    val id: String,
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val fileName: String,
    val kind: SourceKind = SourceKind.LOCAL,
    /** WebDAV 源文件在服务器上的路径，用于重算直链；本地歌曲为 null。 */
    val remotePath: String? = null,
    val sizeBytes: Long = 0L,
    /** 与之同名的 .lrc 歌词文件（扫描时顺手找到的），没有则为 null。 */
    val lyricUri: Uri? = null,
    // --- 仅 ONLINE ---
    /** 这首歌属于哪个音源（[com.tom.musicplayer.model.OnlineSource.id]）。 */
    val onlineSourceId: String? = null,
    /** 这首歌在音源里的 id。 */
    val onlineSongId: String? = null,
    /** 在线封面 URL。本地/WebDAV 的封面是嵌在文件里的，只有在线歌曲需要单独给。 */
    val coverUrl: String? = null
) {
    val isRemote: Boolean get() = kind != SourceKind.LOCAL
    val isOnline: Boolean get() = kind == SourceKind.ONLINE
}
