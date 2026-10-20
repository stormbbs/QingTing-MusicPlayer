package com.tom.musicplayer.data.lyrics

import com.tom.musicplayer.data.lyrics.LrcLibClient.httpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * LRCLIB 在线歌词接口客户端。
 *
 * 选它的理由：开源、免费、不需要注册或 API Key，也没有调用频率限制，
 * 返回体里 `syncedLyrics` 就是标准 LRC 时间轴。国内几家音乐平台的接口都是
 * 非公开的加签接口，随时可能失效，不适合绑进去。
 */
object LrcLibClient {

    private const val BASE = "https://lrclib.net/api"
    private const val UA = "QingTingMusicPlayer/1.0"

    /** 歌词接口和播放用的 OkHttp 分开：它不需要 WebDAV 的鉴权拦截器。 */
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun exact(artist: String, title: String, album: String?, durationSec: Long?): Result<RawLyric> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "$BASE/get".toHttpUrlOrNull()!!.newBuilder().apply {
                    addQueryParameter("artist_name", artist)
                    addQueryParameter("track_name", title)
                    if (!album.isNullOrBlank()) addQueryParameter("album_name", album)
                    if (durationSec != null && durationSec > 0) {
                        addQueryParameter("duration", durationSec.toString())
                    }
                }.build()
                val body = get(url.toString())
                    ?: throw IllegalStateException("没有找到匹配的歌词")
                parseOne(JSONObject(body))
            }
        }

    suspend fun search(query: String): Result<List<RawLyric>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$BASE/search".toHttpUrlOrNull()!!.newBuilder()
                .addQueryParameter("q", query)
                .build()
            val body = get(url.toString()) ?: return@runCatching emptyList()
            val array = JSONArray(body)
            val out = ArrayList<RawLyric>(array.length())
            for (i in 0 until array.length()) {
                val candidate = runCatching { parseOne(array.getJSONObject(i)) }.getOrNull()
                if (candidate != null) out.add(candidate)
            }
            out
        }
    }

    private fun get(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", UA).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IllegalStateException("歌词服务返回 ${response.code}")
            return response.body?.string()
        }
    }

    private fun parseOne(o: JSONObject): RawLyric = RawLyric(
        id = o.optLong("id", 0L),
        trackName = o.optString("trackName").ifBlank { o.optString("name") },
        artistName = o.optString("artistName"),
        albumName = o.optString("albumName"),
        durationSec = o.optDouble("duration", 0.0),
        plainLyrics = o.optString("plainLyrics", ""),
        syncedLyrics = o.optString("syncedLyrics", "").takeIf { it.isNotBlank() }
    )

    data class RawLyric(
        val id: Long,
        val trackName: String,
        val artistName: String,
        val albumName: String,
        val durationSec: Double,
        val plainLyrics: String,
        val syncedLyrics: String?
    )
}
