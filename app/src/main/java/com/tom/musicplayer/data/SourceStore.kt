package com.tom.musicplayer.data

import android.content.Context
import com.tom.musicplayer.model.WebDavServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** WebDAV 服务器配置的本地存储。 */
object SourceStore {

    private const val FILE = "webdav_servers.json"

    private fun file(context: Context) = File(context.filesDir, FILE)

    @Volatile
    private var memory: List<WebDavServer>? = null

    fun load(context: Context): List<WebDavServer> {
        // 缓存的意义：播放时每个 HTTP range 请求都要过一遍鉴权拦截器，
        // 若每次都去读 filesDir 上的 JSON 会把播放拖慢，所以进程内保留一份。
        memory?.let { return it }
        val text = runCatching { file(context).readText() }.getOrNull() ?: return emptyList()
        if (text.isBlank()) return emptyList()
        val list = runCatching {
            val array = JSONArray(text)
            val out = ArrayList<WebDavServer>(array.length())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val base = o.optString("baseUrl")
                if (base.isBlank()) continue
                out.add(
                    WebDavServer(
                        id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = o.optString("name").ifBlank { "WebDAV 服务器" },
                        baseUrl = base,
                        user = o.optString("user", ""),
                        password = o.optString("password", ""),
                        rootPath = normalizePath(o.optString("rootPath", "/"))
                    )
                )
            }
            out
        }.getOrDefault(emptyList())
        memory = list
        return list
    }

    private fun save(context: Context, list: List<WebDavServer>) {
        memory = list
        val array = JSONArray()
        list.forEach { s ->
            array.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("baseUrl", s.baseUrl)
                    put("user", s.user)
                    put("password", s.password)
                    put("rootPath", s.rootPath)
                }
            )
        }
        runCatching {
            file(context).writeText(array.toString())
        }.onFailure { android.util.Log.e("SourceStore", "save failed", it) }
    }

    fun add(context: Context, server: WebDavServer): List<WebDavServer> {
        val list = load(context).toMutableList()
        list.add(server)
        save(context, list)
        return list
    }

    fun remove(context: Context, id: String): List<WebDavServer> {
        val list = load(context).filterNot { it.id == id }
        save(context, list)
        return list
    }

    /**
     * 找到负责这个 URL 的服务器（取最长前缀匹配），Bearer 鉴权据此决定要不要带 Authorization。
     * 播放时 URL 是「重放」的：可能是缓存 miss 后的直接回放，所以每次请求都要能算回来。
     */
    fun matchByUrl(context: Context, url: String): WebDavServer? =
        load(context).filter { url.startsWith(it.baseUrl.trimEnd('/')) }
            .maxByOrNull { it.baseUrl.length }

    fun normalizePath(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return "/"
        val withSlash = if (trimmed.startsWith("/")) trimmed else "/$trimmed"
        return if (withSlash.length > 1) withSlash.trimEnd('/') else "/"
    }
}
