package com.tom.musicplayer.data.online.builtin

import com.tom.musicplayer.data.online.ResolvedUrl
import com.tom.musicplayer.data.online.SourceException
import com.tom.musicplayer.model.OnlineSource
import org.json.JSONObject

/**
 * 播放直链解析器（供内置源共用）。
 *
 * 内置源只负责元数据（搜索 / 榜单 / 歌词 / 封面），音频直链统一走这里。
 * 之所以要单独一层，是因为直链解析涉及版权，LX 生态里这本来就是外置的一环
 * （LX 的「自定义源脚本」干的就是这件事）。这里对接的是聆澜音源的服务端，
 * 用用户自己在 App 里填的密钥（或构建时注入的密钥）鉴权。
 *
 * 没配密钥时不报「连不上」，而是明确告诉用户去音源管理里填——否则用户会以为是网络问题。
 */
internal object LinglanResolver {

    private const val DEFAULT_BASE = "https://source.shiqianjiang.cn/api"

    /** 协议档位 -> 聆澜 quality 参数 */
    private fun upstreamQuality(quality: String): String = when (quality) {
        "lossless" -> "flac"
        "high" -> "320k"
        else -> "128k"
    }

    /** 降档链：请求无损拿不到就退高品，再退标准，不直接报错。 */
    private fun chain(quality: String): List<String> = when (quality) {
        "lossless" -> listOf("lossless", "high", "standard")
        "high" -> listOf("high", "standard")
        else -> listOf("standard")
    }

    private val cache = HashMap<String, ResolvedUrl>()

    @Synchronized
    fun resolve(
        src: OnlineSource,
        songId: String,
        quality: String,
        songName: String = "",
        singer: String = ""
    ): ResolvedUrl {
        val key = src.apiKey.trim()
        if (key.isBlank()) {
            throw SourceException("还没填音源密钥：进「音源管理 → ${src.name} → 编辑」填一次就行")
        }

        val cacheKey = "${src.provider}|$songId|$quality"
        cache[cacheKey]?.let { hit ->
            // 到期过半就重解析，避免用到快过期的链接
            val remain = hit.expiresAtMs - System.currentTimeMillis()
            val total = hit.expiresAtMs - (hit.expiresAtMs - 20 * 60 * 1000L)
            if (remain > total / 2) return hit
        }

        val base = src.trimmedBase.ifBlank { DEFAULT_BASE }.trimEnd('/')
        var lastError: SourceException? = null

        for (q in chain(quality)) {
            val url = "$base/music/url?source=${src.provider}" +
                "&songId=${enc(songId)}" +
                "&quality=${enc(upstreamQuality(q))}" +
                (if (songName.isNotBlank()) "&name=${enc(songName)}" else "") +
                (if (singer.isNotBlank()) "&singer=${enc(singer)}" else "")

            val json: JSONObject = try {
                BuiltinHttp.getJson(
                    url,
                    mapOf(
                        "X-API-Key" to key,
                        "Content-Type" to "application/json",
                        // 服务端按 UA 区分调用方，带个能认出来的
                        "User-Agent" to "qingting-android/1.4"
                    )
                )
            } catch (e: SourceException) {
                lastError = e
                continue
            }

            val code = json.optInt("code", -1)
            val link = json.optString("url").trim()
            if ((code == 0 || code == 200) && link.isNotBlank()) {
                val resolved = ResolvedUrl(
                    url = link,
                    quality = q,
                    expiresAtMs = System.currentTimeMillis() + 20 * 60 * 1000L
                )
                cache[cacheKey] = resolved
                return resolved
            }

            val msg = json.optString("message").ifBlank { "解析失败" }
            lastError = SourceException(msg)
            // 密钥无效 / 额度用尽这类错误，降档重试没有意义
            if (msg.contains("密钥") || msg.contains("额度") || msg.contains("过期") ||
                msg.contains("封禁") || msg.contains("未授权")
            ) break
        }

        throw lastError ?: SourceException("解析不到播放地址，可能这首歌没有版权")
    }

    @Synchronized
    fun clear() = cache.clear()

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
