package com.tom.musicplayer.data.online

import android.content.Context
import com.tom.musicplayer.model.BuiltinProvider
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
 *
 * 关于内置源（酷我 / 网易云）：它们的能力是 App 自带的引擎，但**不带任何预置密钥**。
 * 用户必须自己填一次密钥，填完才算真正「装上」这个源——所以内置源不会默认出现在列表里，
 * 而是走「添加音源」入口、填完密钥后才落到配置中。这样打出来的 APK 谁拿到都得自备密钥，
 * 不会出现「装了包就能白嫖」的情况。
 */
object OnlineSourceStore {

    private const val FILE = "online_sources.json"
    private const val PREFS = "online_source_prefs"
    /** 旧版本用它做过「首次植入内置源」的一次性标记，现在只用来做迁移判断。 */
    private const val KEY_SEEDED = "builtin_seeded_v1"
    /** 已经清理过历史内置源里残留预置密钥的标记。 */
    private const val KEY_KEY_STRIPPED = "builtin_key_stripped_v1"

    @Volatile
    private var memory: List<OnlineSource>? = null

    private fun file(context: Context) = File(context.filesDir, FILE)

    /** 内置源的空白配置：没有密钥，密钥由用户在「设置」里自己填。 */
    fun defaultBuiltin(provider: BuiltinProvider): OnlineSource = OnlineSource(
        id = "builtin.${provider.key}",
        name = provider.displayName,
        provider = provider.key,
        apiKey = "",
        enabled = true
    )

    fun load(context: Context): List<OnlineSource> {
        memory?.let { return it }
        val text = runCatching { file(context).readText() }.getOrNull() ?: ""
        val list = if (text.isBlank()) {
            emptyList()
        } else {
            runCatching {
                val array = JSONArray(text)
                val out = ArrayList<OnlineSource>(array.length())
                for (i in 0 until array.length()) {
                    OnlineSource.fromJson(array.getJSONObject(i))?.let { out.add(it) }
                }
                out
            }.getOrDefault(emptyList())
        }

        // 迁移：老版本首次安装会把内置源连同预置密钥一起塞进列表，那批密钥是打包时注入的。
        // 这里一次性把它们清空，让用户重新填自己的——清空后列表长度不变，所以不需要通知 UI。
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val migrated = if (prefs.getBoolean(KEY_KEY_STRIPPED, false)) {
            list
        } else {
            prefs.edit().putBoolean(KEY_KEY_STRIPPED, true).apply()
            list.map {
                if (it.isBuiltin && it.apiKey.isNotBlank()) it.copy(apiKey = "") else it
            }
        }
        if (migrated !== list) write(context, migrated)

        // 老的「首次植入」标记要补上，否则从 1.x 升上来的用户下次进音源管理
        // 会被当成全新安装、又给塞一遍内置源。
        if (!prefs.getBoolean(KEY_SEEDED, false)) {
            prefs.edit().putBoolean(KEY_SEEDED, true).apply()
        }

        memory = migrated
        return migrated
    }

    fun enabled(context: Context): List<OnlineSource> = load(context).filter { it.enabled }

    fun byId(context: Context, id: String): OnlineSource? = load(context).firstOrNull { it.id == id }

    private fun write(context: Context, list: List<OnlineSource>) {
        val array = JSONArray()
        list.forEach { array.put(OnlineSource.toJson(it)) }
        runCatching { file(context).writeText(array.toString()) }
            .onFailure { android.util.Log.e("OnlineSourceStore", "save failed", it) }
    }

    private fun save(context: Context, list: List<OnlineSource>) {
        memory = list
        write(context, list)
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

    /** 把某个被删掉的内置源加回来；已经有就原样返回。 */
    fun restoreBuiltin(context: Context, provider: BuiltinProvider): List<OnlineSource> {
        val list = load(context)
        if (list.any { it.provider == provider.key }) return list
        val merged = list + defaultBuiltin(provider)
        save(context, merged)
        return merged
    }

    /** 当前还没加进来的内置平台，用于「添加音源」菜单里置灰。 */
    fun missingBuiltins(context: Context): List<BuiltinProvider> {
        val have = load(context).mapNotNull { it.provider }.toSet()
        return BuiltinProvider.entries.filter { it.key !in have }
    }

    fun newId(): String = UUID.randomUUID().toString()

    /**
     * 去重键：内置源按平台算，自定义源按地址算。
     * 内置源 baseUrl 是空的，如果还按 baseUrl 去重，几个内置源会互相覆盖。
     */
    private fun keyOf(s: OnlineSource): String =
        if (s.isBuiltin) "builtin:${s.provider}" else s.trimmedBase

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
                    OnlineSource.fromJson(wrapped.getJSONObject(i))?.let { out[keyOf(it)] = it }
                }
                continue
            }
            OnlineSource.fromJson(obj)?.let { out[keyOf(it)] = it }
        }
        return out.values.toList()
    }

    /** 导入：按 [keyOf] 去重，已有则覆盖，没有则追加。返回导入后是否真的有新增/更新。 */
    fun importSources(context: Context, incoming: List<OnlineSource>): Int {
        if (incoming.isEmpty()) return 0
        val list = load(context).toMutableList()
        var changed = 0
        incoming.forEach { src ->
            val key = keyOf(src)
            val idx = list.indexOfFirst { keyOf(it) == key }
            if (idx >= 0) {
                if (list[idx] != src) {
                    list[idx] = src.copy(id = list[idx].id)
                    changed++
                }
            } else {
                if (src.isBuiltin) {
                    // 导入配置里若带内置源，保留现有 id 语义，不重复塞
                    list.add(src.copy(id = "builtin.${src.provider}"))
                } else {
                    list.add(src)
                }
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
