package com.tom.musicplayer.data.online

import android.content.Context
import com.tom.musicplayer.model.OnlineSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 在线音源配置的本地存储。
 *
 * 除了常规增删改，还负责**导入 / 导出**：音源配置是一份普通 JSON，
 * 可以从文件、剪贴板文本、甚至一个 URL 导入——这样换手机、换音源都不用一个个手填。
 */
object OnlineSourceStore {

    private const val FILE = "online_sources.json"

    @Volatile
    private var memory: List<OnlineSource>? = null

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun load(context: Context): List<OnlineSource> {
        memory?.let { return it }
        val text = runCatching { file(context).readText() }.getOrNull() ?: return emptyList()
        if (text.isBlank()) return emptyList()
        val list = runCatching {
            val array = JSONArray(text)
            val out = ArrayList<OnlineSource>(array.length())
            for (i in 0 until array.length()) {
                OnlineSource.fromJson(array.getJSONObject(i))?.let { out.add(it) }
            }
            out
        }.getOrDefault(emptyList())
        memory = list
        return list
    }

    fun enabled(context: Context): List<OnlineSource> = load(context).filter { it.enabled }

    fun byId(context: Context, id: String): OnlineSource? = load(context).firstOrNull { it.id == id }

    private fun save(context: Context, list: List<OnlineSource>) {
        memory = list
        val array = JSONArray()
        list.forEach { array.put(OnlineSource.toJson(it)) }
        runCatching { file(context).writeText(array.toString()) }
            .onFailure { android.util.Log.e("OnlineSourceStore", "save failed", it) }
    }

    fun upsert(context: Context, source: OnlineSource): List<OnlineSource> {
        val list = load(context).toMutableList()
        val idx = list.indexOfFirst { it.id == source.id }
        if (idx >= 0) list[idx] = source else list.add(source)
        save(context, list)
        return list
    }

    fun remove(context: Context, id: String): List<OnlineSource> {
        val list = load(context).filterNot { it.id == id }
        save(context, list)
        return list
    }

    fun toggle(context: Context, id: String, enabled: Boolean): List<OnlineSource> {
        val list = load(context).map { if (it.id == id) it.copy(enabled = enabled) else it }
        save(context, list)
        return list
    }

    fun newId(): String = UUID.randomUUID().toString()

    /**
     * 解析一段导入文本。支持四种形态：
     * - 单个音源对象 `{...}`
     * - 音源数组 `[...]`
     * - 包装对象 `{"sources":[...]}`
     * - 逗号分隔的多个 JSON（容错）
     *
     * @return 解析出来的音源；解析不出任何有效项时返回空列表
     */
    fun parseImport(text: String): List<OnlineSource> {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return emptyList()
        val out = LinkedHashMap<String, OnlineSource>()
        val candidates = mutableListOf<String>()
        val asArray = runCatching { JSONArray(trimmed) }.getOrNull()
        if (asArray != null) {
            for (i in 0 until asArray.length()) candidates.add(asArray.getJSONObject(i).toString())
        } else {
            candidates.add(trimmed)
        }
        for (raw in candidates) {
            val obj = runCatching { JSONObject(raw) }.getOrNull() ?: continue
            val wrapped = obj.optJSONArray("sources")
            if (wrapped != null) {
                for (i in 0 until wrapped.length()) {
                    OnlineSource.fromJson(wrapped.getJSONObject(i))?.let { out[it.baseUrl] = it }
                }
                continue
            }
            OnlineSource.fromJson(obj)?.let { out[it.baseUrl] = it }
        }
        return out.values.toList()
    }

    /** 导入：按 baseUrl 去重，已有则覆盖，没有则追加。返回导入后是否真的有新增/更新。 */
    fun importSources(context: Context, incoming: List<OnlineSource>): Int {
        if (incoming.isEmpty()) return 0
        val list = load(context).toMutableList()
        var changed = 0
        incoming.forEach { src ->
            val idx = list.indexOfFirst { it.baseUrl.trimEnd('/') == src.baseUrl.trimEnd('/') }
            if (idx >= 0) {
                if (list[idx] != src) {
                    list[idx] = src.copy(id = list[idx].id)
                    changed++
                }
            } else {
                list.add(src)
                changed++
            }
        }
        if (changed > 0) save(context, list)
        return changed
    }

    fun exportJson(context: Context): String {
        val array = JSONArray()
        load(context).forEach { array.put(OnlineSource.toJson(it)) }
        return array.toString(2)
    }
}
