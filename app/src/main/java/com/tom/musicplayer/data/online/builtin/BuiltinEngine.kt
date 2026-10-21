package com.tom.musicplayer.data.online.builtin

import com.tom.musicplayer.data.online.ResolvedUrl
import com.tom.musicplayer.model.BuiltinProvider
import com.tom.musicplayer.model.OnlinePlaylist
import com.tom.musicplayer.model.OnlineSong
import com.tom.musicplayer.model.OnlineSource

/**
 * 一个内置音源引擎：把某个平台的搜索 / 榜单 / 歌词 / 封面直接实现在 App 里，
 * 不需要用户再架一个 HTTP 服务。
 *
 * 直链不走这里——统一交给 [LinglanResolver]，因为那块要用密钥、也涉及版权，
 * 和 LX 生态里「元数据内置、直链外置」的分工是一致的。
 */
internal interface BuiltinEngine {

    val provider: BuiltinProvider

    suspend fun search(
        src: OnlineSource,
        keyword: String,
        page: Int,
        limit: Int
    ): List<OnlineSong>

    suspend fun playlists(src: OnlineSource): List<OnlinePlaylist>

    /** 返回 (歌单名, 歌曲列表) */
    suspend fun playlistSongs(
        src: OnlineSource,
        playlistId: String,
        page: Int,
        limit: Int
    ): Pair<String, List<OnlineSong>>

    /** 没有歌词返回 null，调用方会回落到 LRCLIB。 */
    suspend fun lyric(src: OnlineSource, songId: String): String?

    /** 单曲封面。拿不到返回 null。 */
    suspend fun picUrl(src: OnlineSource, songId: String): String?

    /** 阻塞版直链解析，给播放线程用。 */
    fun resolveBlocking(
        src: OnlineSource,
        songId: String,
        quality: String,
        songName: String,
        singer: String
    ): ResolvedUrl

    /** 连通性自检：返回 null 表示正常，否则是给人看的原因。 */
    suspend fun test(src: OnlineSource): String?
}

/** 引擎注册表。新增内置平台只要在这里加一行。 */
internal object BuiltinEngines {

    private val engines: Map<String, BuiltinEngine> =
        listOf<BuiltinEngine>(KwEngine, WyEngine).associateBy { it.provider.key }

    fun of(provider: String): BuiltinEngine? = engines[provider]

    fun all(): List<BuiltinEngine> = engines.values.toList()
}
