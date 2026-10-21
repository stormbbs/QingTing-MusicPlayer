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
import java.util.Locale

/**
 * 酷我音乐内置引擎。
 *
 * 接口参数取自 LX Music 的 `musicSdk/kw/`（Apache-2.0）。这些是酷我客户端的私有接口，
 * 酷我改版就得跟着改，所以踩过的坑都写在注释里，方便下次修。
 */
internal object KwEngine : BuiltinEngine {

    override val provider = BuiltinProvider.KW

    private const val SEARCH_HOST = "http://search.kuwo.cn/r.s"
    private const val RANK_TREE = "http://qukudata.kuwo.cn/q.k"
    private const val RANK_DETAIL = "http://kbangserver.kuwo.cn/ksong.s"
    private const val LYRIC_API = "http://m.kuwo.cn/newh5/singles/songinfoandlrc"
    private const val PIC_API = "http://artistpicserver.kuwo.cn/pic.web"

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    // ----------------------------------------------------------------- 搜索

    private fun searchUrl(keyword: String, page: Int, limit: Int): String =
        "$SEARCH_HOST?client=kt&all=${enc(keyword)}" +
            "&pn=${(page - 1).coerceAtLeast(0)}&rn=$limit" +
            "&uid=794762570&ver=kwplayer_ar_9.2.2.1&vipver=1" +
            "&show_copyright_off=1&newver=1&ft=music&cluster=0&strategy=2012" +
            "&encoding=utf8&rformat=json&vermerge=1&mobi=1&issubtitle=1"

    override suspend fun search(
        src: OnlineSource,
        keyword: String,
        page: Int,
        limit: Int
    ): List<OnlineSong> = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext emptyList()
        val body = BuiltinHttp.getJson(searchUrl(keyword, page, limit))
        val arr = body.optJSONArray("abslist") ?: return@withContext emptyList()
        val out = ArrayList<OnlineSong>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("MUSICRID").removePrefix("MUSIC_")
                .ifBlank { o.optString("DC_TARGETID") }
            val name = o.optString("SONGNAME").ifBlank { o.optString("NAME") }
            if (id.isBlank() || name.isBlank()) continue
            val seconds = o.optString("DURATION").toIntOrNull() ?: 0
            out.add(
                OnlineSong(
                    songId = id,
                    name = name,
                    artist = o.optString("ARTIST").replace("&", "、").ifBlank { "未知歌手" },
                    album = o.optString("ALBUM"),
                    durationMs = seconds * 1000L,
                    cover = coverFromShort(o.optString("web_albumpic_short")),
                    qualities = qualitiesOf(o.optString("MINFO").ifBlank { o.optString("N_MINFO") }),
                    sourceId = src.id
                )
            )
        }
        out
    }

    /**
     * MINFO 形如 `level:ff,bitrate:2000,format:flac,size:24.81Mb;level:p,bitrate:320,...`，
     * bitrate 4000/2000 是无损，320 高品，128 标准。
     */
    private fun qualitiesOf(minfo: String): List<String> {
        val found = HashSet<String>()
        Regex("bitrate:(\\d+)").findAll(minfo).forEach { m ->
            when (m.groupValues[1]) {
                "2000", "4000" -> found.add("lossless")
                "320" -> found.add("high")
                "128" -> found.add("standard")
            }
        }
        val ordered = listOf("standard", "high", "lossless").filter { it in found }
        return ordered.ifEmpty { listOf("standard") }
    }

    /**
     * 搜索结果带 `web_albumpic_short`，形如 `120/s4s0/93/1794217775.jpg`。
     * 把尺寸段换成 500 就是大图，省掉每首一次封面查询。
     *
     * 注意必须走 HTTP：这个域名的 HTTPS 证书有问题，走 https 会直接握手失败。
     * App 已在 Manifest 里开了 usesCleartextTraffic。
     */
    private fun coverFromShort(short: String): String? {
        if (short.isBlank()) return null
        val path = short.replaceFirst(Regex("^\\d+/"), "500/")
        return "http://img1.kwcdn.kuwo.cn/star/albumcover/$path"
    }

    // ------------------------------------------------------------ 榜单 / 歌单

    override suspend fun playlists(src: OnlineSource): List<OnlinePlaylist> =
        withContext(Dispatchers.IO) {
            val body = BuiltinHttp.getJson("$RANK_TREE?op=query&cont=tree&node=2&pn=0&rn=200&fmt=json&level=2")
            val out = ArrayList<OnlinePlaylist>()
            fun walk(node: JSONObject) {
                val kids = node.optJSONArray("child") ?: return
                for (i in 0 until kids.length()) {
                    val k = kids.optJSONObject(i) ?: continue
                    val grandKids = k.optJSONArray("child")
                    if (grandKids != null && grandKids.length() > 0) {
                        walk(k)
                    } else {
                        val id = k.optString("id")
                        if (id.isBlank()) continue
                        out.add(
                            OnlinePlaylist(
                                id = "bang:$id",
                                name = k.optString("disname").ifBlank { k.optString("name") },
                                cover = k.optString("pic").takeIf { it.isNotBlank() },
                                desc = k.optString("info")
                                    .takeIf { it.isNotBlank() && it != "0个列表" },
                                sourceId = src.id
                            )
                        )
                    }
                }
            }
            walk(body)
            out.take(40)
        }

    override suspend fun playlistSongs(
        src: OnlineSource,
        playlistId: String,
        page: Int,
        limit: Int
    ): Pair<String, List<OnlineSong>> = withContext(Dispatchers.IO) {
        val pid = playlistId.removePrefix("bang:")
        if (pid == playlistId) throw SourceException("这个歌单不是榜单，暂不支持")
        val body = BuiltinHttp.getJson(
            "$RANK_DETAIL?from=pc&fmt=json&pn=${(page - 1).coerceAtLeast(0)}&rn=$limit" +
                "&type=bang&data=content&id=${enc(pid)}&show_copyright_off=0&pcmp4=1&isbang=1"
        )
        val list = body.optJSONArray("musiclist") ?: throw SourceException("榜单里没有歌")
        val out = ArrayList<OnlineSong>(list.length())
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val name = o.optString("name")
            if (id.isBlank() || name.isBlank()) continue
            // 榜单里的 duration 是试听时长，完整时长在 song_duration
            val seconds = o.optString("song_duration").toIntOrNull()
                ?: o.optString("duration").toIntOrNull() ?: 0
            out.add(
                OnlineSong(
                    songId = id,
                    name = name,
                    artist = o.optString("artist").replace("&", "、").ifBlank { "未知歌手" },
                    album = o.optString("album"),
                    durationMs = seconds * 1000L,
                    cover = null,
                    qualities = provider.qualities,
                    sourceId = src.id
                )
            )
        }
        body.optString("name").ifBlank { "榜单" } to out
    }

    // -------------------------------------------------------------- 歌词 封面

    /**
     * 酷我歌词接口有三个坑，少一个就返回 `status:301 音乐查询失败`：
     *   1. 必须带 `httpsStatus=1`
     *   2. 必须是移动端 UA + Referer
     *   3. Accept 必须是浏览器式完整列表，`* / *` 会被判成非浏览器请求
     */
    override suspend fun lyric(src: OnlineSource, songId: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = BuiltinHttp.getJson(
                    "$LYRIC_API?musicId=${enc(songId)}&httpsStatus=1",
                    mapOf(
                        "User-Agent" to BuiltinHttp.MOBILE_UA,
                        "Referer" to "http://m.kuwo.cn/",
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                    )
                )
                val rows = body.optJSONObject("data")?.optJSONArray("lrclist")
                    ?: return@runCatching null
                if (rows.length() == 0) return@runCatching null
                buildString {
                    for (i in 0 until rows.length()) {
                        val r = rows.optJSONObject(i) ?: continue
                        append(lrcTime(r.optString("time").toDoubleOrNull() ?: 0.0))
                        append(r.optString("lineLyric"))
                        append('\n')
                    }
                }.trim().ifBlank { null }
            }.getOrNull()
        }

    private fun lrcTime(sec: Double): String {
        val m = (sec / 60).toInt()
        val s = sec - m * 60
        return String.format(Locale.US, "[%02d:%05.2f]", m, s)
    }

    /** 这个接口返回的是「图片地址的纯文本」，不是图片本身，所以要再下载一次。 */
    override suspend fun picUrl(src: OnlineSource, songId: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = BuiltinHttp.getText(
                    "$PIC_API?corp=kuwo&type=rid_pic&pictype=500&size=500&rid=${enc(songId)}"
                ).trim()
                text.takeIf { it.startsWith("http") }
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
