package com.tom.musicplayer.model

import android.net.Uri

/** 歌曲来源：本地 SAF 文件，或 WebDAV 远端文件。 */
enum class SourceKind(val value: String) {
    LOCAL("local"),
    WEBDAV("webdav");

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
    val lyricUri: Uri? = null
) {
    val isRemote: Boolean get() = kind != SourceKind.LOCAL
}
