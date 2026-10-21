package com.tom.musicplayer.data.online.builtin

import com.tom.musicplayer.data.online.ResolvedUrl
import com.tom.musicplayer.data.online.SourceException
import com.tom.musicplayer.model.BuiltinProvider
import com.tom.musicplayer.model.OnlinePlaylist
import com.tom.musicplayer.model.OnlineSong
import com.tom.musicplayer.model.OnlineSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 网易云音乐内置引擎。
 *
 * 用的是网易云 web 端的老 API（`/api/search/get/web`、`/api/song/lyric` 等），
 * 只需要带 `Referer` 和一个匿名 Cookie，**不需要 weapi 的 AES+RSA 加密签名**。
 * LX 用 eapi 是为了拿到 hires 之类的细节，这里只要元数据，老接口够了。
 *
 * 所有请求都必须带 Referer，否则会返回 `code: 400 参数错误`。
 */
internal object WyEngine : BuiltinEngine {

    override val provider = BuiltinProvider.WY

    private const val BASE = "https://music.163.com/api"

    private val headers = mapOf(
        "Referer" to "https://music.163.com/",
        // 匿名标识即可，网易云只要求「有 Cookie」而不校验内容
        "Cookie" to "NMTID=00OqTingTing0000000000000000000000",
        "Accept" to "*/*"
    )

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun get(path: String): JSONObject = BuiltinHttp.getJson("$BASE$path", headers)

    // ----------------------------------------------------------------- 搜索

    override suspend fun search(
        src: OnlineSource,
        keyword: String,
        page: Int,
        limit: Int
    ): List<OnlineSong> = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext emptyList()
        val offset = (page - 1).coerceAtLeast(0) * limit
        val body = get("/search/get/web?s=${enc(keyword)}&type=1&limit=$limit&offset=$offset")
        val songs = body.optJSONObject("result")?.optJSONArray("songs")
            ?: return@withContext emptyList()

        val out = ArrayList<OnlineSong>(songs.length())
        for (i in 0 until songs.length()) {
            val o = songs.optJSONObject(i) ?: continue
            val id = o.optLong("id").toString()
            val name = o.optString("name")
            if (id.isBlank() || id == "0" || name.isBlank()) continue
            out.add(
                OnlineSong(
                    songId = id,
                    name = name,
                    artist = joinArtists(o.optJSONArray("artists")).ifBlank { "未知歌手" },
                    album = o.optJSONObject("album")?.optString("name").orEmpty(),
                    // 搜索接口给的已经是毫秒
                    durationMs = o.optLong("duration", 0L),
                    cover = pickPic(o.optJSONObject("album")),
                    qualities = provider.qualities,
                    sourceId = src.id
                )
            )
        }
        // 搜索接口不返回封面，批量补一次（一次请求，失败就算了，不影响搜索）
        fillCovers(out)
        out
    }

    /**
     * 老搜索接口的 album 里没有 picUrl，只能用 `/song/detail?ids=[...]` 批量补。
     * 30 首拼成一个请求，比每首查一次划算得多。
     */
    private fun fillCovers(songs: MutableList<OnlineSong>) {
        if (songs.isEmpty()) return
        val ids = songs.map { it.songId }.joinToString(",")
        val body = runCatching { get("/song/detail?ids=%5B$ids%5D") }.getOrNull() ?: return
        val arr = body.optJSONArray("songs") ?: return
        val map = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pic = pickPic(o.optJSONObject("album")) ?: continue
            map[o.optLong("id").toString()] = pic
        }
        if (map.isEmpty()) return
        for (i in songs.indices) {
            if (songs[i].cover == null) {
                map[songs[i].songId]?.let { songs[i] = songs[i].copy(cover = it) }
            }
        }
    }

    private fun joinArtists(arr: org.json.JSONArray?): String {
        if (arr == null || arr.length() == 0) return ""
        val names = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val n = arr.optJSONObject(i)?.optString("name").orEmpty()
            if (n.isNotBlank()) names.add(n)
        }
        return names.joinToString("、")
    }

    /** 封面统一转 https：网易云会给 http 地址，走 https 更稳。 */
    private fun pickPic(album: JSONObject?): String? {
        if (album == null) return null
        val url = album.optString("picUrl")
            .ifBlank { album.optString("blurPicUrl") }
            .ifBlank { album.optString("img1v1Url") }
        return url.takeIf { it.isNotBlank() }?.replace("http://", "https://")
    }

    // ------------------------------------------------------------ 榜单 / 歌单

    override suspend fun playlists(src: OnlineSource): List<OnlinePlaylist> =
        withContext(Dispatchers.IO) {
            val body = get("/toplist")
            val list = body.optJSONArray("list") ?: return@withContext emptyList()
            val out = ArrayList<OnlinePlaylist>(list.length())
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val id = o.optLong("id").toString()
                if (id == "0") continue
                out.add(
                    OnlinePlaylist(
                        id = "top:$id",
                        name = o.optString("name"),
                        cover = o.optString("coverImgUrl")
                            .takeIf { it.isNotBlank() }?.replace("http://", "https://"),
                        count = o.optInt("trackCount", 0),
                        desc = o.optString("updateFrequency").takeIf { it.isNotBlank() },
                        sourceId = src.id
                    )
                )
            }
            out
        }

    override suspend fun playlistSongs(
        src: OnlineSource,
        playlistId: String,
        page: Int,
        limit: Int
    ): Pair<String, List<OnlineSong>> = withContext(Dispatchers.IO) {
        val pid = playlistId.removePrefix("top:")
        if (pid == playlistId) throw SourceException("这个歌单不是榜单，暂不支持")
        val body = get("/playlist/detail?id=$pid")
        val result = body.optJSONObject("result") ?: throw SourceException("榜单打不开")
        val tracks = result.optJSONArray("tracks") ?: throw SourceException("榜单里没有歌")
        val out = ArrayList<OnlineSong>(tracks.length())
        for (i in 0 until tracks.length()) {
            val o = tracks.optJSONObject(i) ?: continue
            val id = o.optLong("id").toString()
            val name = o.optString("name")
            if (id == "0" || name.isBlank()) continue
            out.add(
                OnlineSong(
                    songId = id,
                    name = name,
                    artist = joinArtists(o.optJSONArray("artists")).ifBlank { "未知歌手" },
                    album = o.optJSONObject("album")?.optString("name").orEmpty(),
                    durationMs = o.optLong("duration", 0L),
                    cover = pickPic(o.optJSONObject("album")),
                    qualities = provider.qualities,
                    sourceId = src.id
                )
            )
        }
        result.optString("name").ifBlank { "榜单" } to out
    }

    // -------------------------------------------------------------- 歌词 封面

    override suspend fun lyric(src: OnlineSource, songId: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = get("/song/lyric?id=${enc(songId)}&lv=1&kv=1&tv=-1")
                body.optJSONObject("lrc")?.optString("lyric")?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }

    override suspend fun picUrl(src: OnlineSource, songId: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = get("/song/detail?ids=%5B${enc(songId)}%5D")
                val arr = body.optJSONArray("songs") ?: return@runCatching null
                if (arr.length() == 0) return@runCatching null
                pickPic(arr.optJSONObject(0)?.optJSONObject("album"))
            }.getOrNull()
        }

    // ------------------------------------------------------------------ 其它

    override fun resolveBlocking(
        src: OnlineSource,
        songId: String,
        quality: String,
        songName: String,
        singer: String
    ): ResolvedUrl = LinglanResolver.resolve(src, songId, quality, songName, singer)

    override suspend fun test(src: OnlineSource): String? = withContext(Dispatchers.IO) {
        runCatching {
            search(src, "晴天", 1, 1)
            if (src.apiKey.isBlank()) "能搜索，但还没填密钥，播放会失败" else null
        }.getOrElse { e ->
            (e as? SourceException)?.message ?: e.message ?: "连接失败"
        }
    }
}
