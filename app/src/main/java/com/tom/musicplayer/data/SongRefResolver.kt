package com.tom.musicplayer.data

import android.content.Context
import android.net.Uri
import com.tom.musicplayer.data.online.DownloadStore
import com.tom.musicplayer.model.Song
import java.io.File

/**
 * 把「只记身份」的收藏 / 歌单条目还原成能播的歌。
 *
 * 点心和自建歌单都不能存播放直链（在线直链有时效），本地文件也可能被挪走，
 * 所以每次打开都要重新认一遍。两处用的是同一套规则，抽在这里避免各写一份、
 * 改了一边忘另一边。
 */
data class ResolvedRef(
    val song: Song?,
    /** 在线 / 已下载 / 本地。解析不出来时是「本地」，song 为 null。 */
    val where: String
)

object SongRefResolver {

    fun resolveAll(
        context: Context,
        entries: List<FavoriteSong>,
        library: List<Song>
    ): List<ResolvedRef> {
        if (entries.isEmpty()) return emptyList()
        val downloads = runCatching { DownloadStore.load(context) }.getOrDefault(emptyList())
        val byKey = library.associateBy { FavoritesStore.keyOf(it) }
        val byName = library.filter { it.fileName.isNotBlank() }.associateBy { it.fileName }
        return entries.map { resolveOne(context, it, downloads, byKey, byName) }
    }

    /**
     * 优先用「活着的」那一份：
     * - 有音源身份的歌，先找下载记录 —— 下过就用本地文件，不花流量；
     * - 本地歌去曲库里按 Uri / 文件名找（换过文件夹 Uri 会变）；
     * - 都找不到时最后试一次记录里的老 Uri，读得开才算还在。
     */
    private fun resolveOne(
        context: Context,
        entry: FavoriteSong,
        downloads: List<com.tom.musicplayer.data.online.DownloadedSong>,
        byKey: Map<String, Song>,
        byName: Map<String, Song>
    ): ResolvedRef {
        val saved = downloads.firstOrNull {
            (entry.hasOnlineIdentity &&
                it.sourceId == entry.onlineSourceId && it.songId == entry.onlineSongId) ||
                it.id == entry.id
        }?.let { DownloadStore.toSong(context, it) }
        if (saved != null) return ResolvedRef(saved, "已下载")

        if (entry.hasOnlineIdentity) {
            val inLibrary = byKey[entry.id]
            return if (inLibrary != null) ResolvedRef(inLibrary, "已下载")
            else ResolvedRef(entry.toSong(), "在线")
        }

        val inLibrary = byKey[entry.id]
            ?: entry.fileName.takeIf { it.isNotBlank() }?.let { byName[it] }
        if (inLibrary != null) return ResolvedRef(inLibrary, "本地")

        val uri = entry.uri?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val ok = uri != null && readable(context, uri)
        return ResolvedRef(if (ok) entry.toSong() else null, "本地")
    }

    /** 老 Uri 还能不能读：内容 Uri 真去开一次，文件 Uri 看文件在不在。 */
    private fun readable(context: Context, uri: Uri): Boolean = runCatching {
        when (uri.scheme) {
            "content" -> context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
            "file" -> File(uri.path.orEmpty()).exists()
            else -> true
        }
    }.getOrDefault(false)
}
