package com.tom.musicplayer.data.online

import com.tom.musicplayer.data.online.builtin.BuiltinEngine
import com.tom.musicplayer.data.online.builtin.BuiltinEngines
import com.tom.musicplayer.model.OnlinePlaylist
import com.tom.musicplayer.model.OnlineSong
import com.tom.musicplayer.model.OnlineSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** 音源返回的错误：message 直接就是能给用户看的话。 */
class SourceException(message: String) : Exception(message)

/** 解析出来的直链。直链通常有时效，所以要连过期时间一起记住。 */
data class ResolvedUrl(
    val url: String,
    val quality: String,
    val expiresAtMs: Long,
    val size: Long = 0L
)

/**
 * `docs/ONLINE_SOURCE_PROTOCOL.md` 协议 v1 的客户端。
 *
 * 六个接口：/search /playlists /playlist /url /lyric /pic，全部 GET。
 * 所有函数都是 suspend，请在 IO 线程调用；
 * 只有 [resolveBlocking] 是阻塞版——给播放器的 DataSource 用（它在播放线程上，不能用协程）。
 */
object OnlineApi {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * 直链缓存。
     *
     * 拖动进度、缓冲断线重连都会让播放器重新 open 数据源，
     * 如果每次都去问音源要直链，一首歌能打出十几个请求。
     * 所以按「剩余寿命过半就重新解析」的策略缓存，既省请求又不会用到过期链接。
     */
    private val urlCache = HashMap<String, ResolvedUrl>()

    @Synchronized
    private fun cacheKey(src: OnlineSource, songId: String, quality: String) =
        "${src.id}|$songId|$quality"

    @Synchronized
    private fun peekCache(key: String): ResolvedUrl? {
        val hit = urlCache[key] ?: return null
        val halfLife = hit.expiresAtMs - (hit.expiresAtMs - System.currentTimeMillis()) / 2
        return if (System.currentTimeMillis() < halfLife) hit else null
    }

    // ------------------------------------------------------------- 基础请求

    /**
     * 内置源走 App 自带的引擎，自定义源走 HTTP 协议。
     * 这里是两条路的分岔口：只要 provider 有值，下面所有接口都不会发 HTTP 请求。
     */
    private fun engineOf(src: OnlineSource): BuiltinEngine? =
        if (src.isBuiltin) BuiltinEngines.of(src.provider) else null

    private fun authHeader(src: OnlineSource): String? = when {
        src.token.isNotBlank() -> "Bearer ${src.token}"
        src.user.isNotBlank() || src.password.isNotBlank() ->
            okhttp3.Credentials.basic(src.user, src.password)
        else -> null
    }

    /** 发一次 GET 并解析成 JSON。失败统一转成 [SourceException]。 */
    private fun getJson(src: OnlineSource, path: String, params: Map<String, String>): JSONObject {
        val base = src.url(path).toHttpUrlOrNull()
            ?: throw SourceException("音源地址不合法：${src.baseUrl}")
        val url = base.newBuilder().apply {
            params.forEach { (k, v) -> if (v.isNotBlank()) addQueryParameter(k, v) }
        }.build()
        val request = Request.Builder().url(url).apply {
            authHeader(src)?.let { header("Authorization", it) }
        }.build()
        val body = try {
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching { JSONObject(text).optString("error") }.getOrNull()
                    throw SourceException(msg?.takeIf { it.isNotBlank() } ?: "音源返回 ${resp.code}")
                }
                text
            }
        } catch (e: SourceException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw SourceException("连接音源超时")
        } catch (e: Exception) {
            throw SourceException("连不上音源：${e.message ?: e.javaClass.simpleName}")
        }
        val json = runCatching { JSONObject(body) }
            .getOrElse { throw SourceException("音源返回的不是 JSON") }
        val error = json.optString("error")
        if (error.isNotBlank()) throw SourceException(error)
        return json
    }

    private fun parseSong(o: JSONObject, sourceId: String): OnlineSong? {
        val id = o.optString("id")
        val name = o.optString("name")
        if (id.isBlank() || name.isBlank()) return null
        val qualities = mutableListOf<String>()
        o.optJSONArray("qualities")?.let { arr ->
            for (i in 0 until arr.length()) {
                val q = arr.optString(i)
                if (q.isNotBlank()) qualities.add(q)
            }
        }
        return OnlineSong(
            songId = id,
            name = name,
            artist = o.optString("artist").ifBlank { "未知歌手" },
            album = o.optString("album"),
            durationMs = o.optLong("duration", 0L),
            cover = o.optString("cover").takeIf { it.isNotBlank() },
            qualities = qualities,
            sourceId = sourceId
        )
    }

    private fun parseSongs(json: JSONObject, sourceId: String): List<OnlineSong> {
        val arr = json.optJSONArray("songs") ?: return emptyList()
        val out = ArrayList<OnlineSong>(arr.length())
        for (i in 0 until arr.length()) {
            parseSong(arr.optJSONObject(i) ?: continue, sourceId)?.let { out.add(it) }
        }
        return out
    }

    // ------------------------------------------------------------- 各接口

    suspend fun search(
        src: OnlineSource,
        keyword: String,
        quality: String,
        page: Int = 1,
        limit: Int = 30
    ): List<OnlineSong> = withContext(Dispatchers.IO) {
        engineOf(src)?.let { return@withContext it.search(src, keyword, page, limit) }
        val json = getJson(src, "/search", mapOf(
            "keyword" to keyword,
            "page" to page.toString(),
            "limit" to limit.toString(),
            "quality" to quality
        ))
        parseSongs(json, src.id)
    }

    suspend fun playlists(src: OnlineSource): List<OnlinePlaylist> = withContext(Dispatchers.IO) {
        engineOf(src)?.let { return@withContext it.playlists(src) }
        val json = getJson(src, "/playlists", emptyMap())
        val arr = json.optJSONArray("playlists") ?: return@withContext emptyList()
        val out = ArrayList<OnlinePlaylist>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val name = o.optString("name")
            if (id.isBlank() || name.isBlank()) continue
            out.add(
                OnlinePlaylist(
                    id = id,
                    name = name,
                    cover = o.optString("cover").takeIf { it.isNotBlank() },
                    count = o.optInt("count", 0),
                    desc = o.optString("desc").takeIf { it.isNotBlank() },
                    sourceId = src.id
                )
            )
        }
        out
    }

    suspend fun playlistSongs(
        src: OnlineSource,
        playlistId: String,
        quality: String
    ): Pair<String, List<OnlineSong>> = withContext(Dispatchers.IO) {
        engineOf(src)?.let {
            return@withContext it.playlistSongs(src, playlistId, 1, 200)
        }
        val json = getJson(src, "/playlist", mapOf(
            "id" to playlistId,
            "page" to "1",
            "limit" to "200",
            "quality" to quality
        ))
        json.optString("name").ifBlank { "歌单" } to parseSongs(json, src.id)
    }

    /** 歌词。源没给就返回 null，调用方会回落到 LRCLIB。 */
    suspend fun lyric(src: OnlineSource, songId: String): String? = withContext(Dispatchers.IO) {
        engineOf(src)?.let { return@withContext it.lyric(src, songId) }
        runCatching {
            getJson(src, "/lyric", mapOf("id" to songId))
                .optString("lyric").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    suspend fun resolve(
        src: OnlineSource,
        songId: String,
        quality: String
    ): ResolvedUrl = withContext(Dispatchers.IO) { resolveBlocking(src, songId, quality) }

    /**
     * 解析直链（阻塞版）。
     *
     * 播放器的数据源在播放线程上要拿真实地址，这里不能切协程，只能同步等。
     * 请求本身很短（一次 HTTP GET），不会拖慢起播。
     */
    @Synchronized
    fun resolveBlocking(src: OnlineSource, songId: String, quality: String): ResolvedUrl {
        // 内置源的直链由引擎内部解析并缓存（要带密钥、要降档回退）
        engineOf(src)?.let { return it.resolveBlocking(src, songId, quality, "", "") }
        val key = cacheKey(src, songId, quality)
        peekCache(key)?.let { return it }
        val json = getJson(src, "/url", mapOf("id" to songId, "quality" to quality))
        val url = json.optString("url")
        if (url.isBlank()) throw SourceException("音源没返回播放地址")
        val expiresIn = json.optLong("expiresIn", 600L).coerceAtLeast(30L)
        val resolved = ResolvedUrl(
            url = url,
            quality = json.optString("quality").ifBlank { quality },
            expiresAtMs = System.currentTimeMillis() + expiresIn * 1000L,
            size = json.optLong("size", 0L)
        )
        urlCache[key] = resolved
        return resolved
    }

    /** 连一下根路径，用于「测试连通性」。成功返回 null，失败返回原因。 */
    suspend fun test(src: OnlineSource): String? = withContext(Dispatchers.IO) {
        engineOf(src)?.let { return@withContext it.test(src) }
        runCatching {
            getJson(src, "/", emptyMap())
            null
        }.getOrElse { e ->
            (e as? SourceException)?.message ?: e.message ?: "连接失败"
        }
    }

    /** 换音质、换源之后旧直链就作废了。 */
    @Synchronized
    fun clearUrlCache() = urlCache.clear()
}
