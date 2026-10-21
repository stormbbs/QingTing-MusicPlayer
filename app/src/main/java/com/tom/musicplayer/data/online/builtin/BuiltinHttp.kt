package com.tom.musicplayer.data.online.builtin

import com.tom.musicplayer.data.online.SourceException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * 内置引擎共用的 HTTP 客户端。
 *
 * 和 [com.tom.musicplayer.data.online.OnlineApi] 的客户端分开，是因为内置源要模拟
 * 浏览器/移动端身份（酷我、网易云都会检查 UA 和 Referer），而自定义音源不该被塞这些头。
 */
internal object BuiltinHttp {

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    const val MOBILE_UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"

    val desktopUa: String get() = DESKTOP_UA

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        val builder = Request.Builder().url(url)
        // 默认带桌面 UA，各引擎可以按需覆盖
        builder.header("User-Agent", DESKTOP_UA)
        builder.header("Accept", "*/*")
        headers.forEach { (k, v) -> builder.header(k, v) }
        val request = builder.build()
        val body = try {
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw SourceException(
                        if (resp.code == 403) "源站拒绝了请求（可能需要代理或已被风控）"
                        else "源站返回 ${resp.code}"
                    )
                }
                text
            }
        } catch (e: SourceException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw SourceException("连接源站超时，检查一下网络")
        } catch (e: Exception) {
            throw SourceException("连不上源站：${e.message ?: e.javaClass.simpleName}")
        }
        return body
    }

    fun getJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject {
        val text = getText(url, headers)
        return runCatching { JSONObject(text) }.getOrElse {
            // 个别接口会包一层 `var x = {...}`，兜一下
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start in 0 until end) {
                runCatching { JSONObject(text.substring(start, end + 1)) }.getOrNull()
            } else null
        } ?: throw SourceException("源站返回了无法解析的数据，接口可能改版了")
    }
}
