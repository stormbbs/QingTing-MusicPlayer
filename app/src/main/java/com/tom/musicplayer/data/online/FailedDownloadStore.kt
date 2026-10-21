package com.tom.musicplayer.data.online

import android.content.Context
import com.tom.musicplayer.model.OnlineSong
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 一条「没下成」的记录。
 *
 * 以前下载失败只在通知栏闪一句，通知一划掉就再也找不到了：用户不知道是哪几首没下来，
 * 也没法再试一次，只能回「在线」页重新搜、重新点。这里把失败的那首歌连同原因留下来，
 * 「我的下载」页列出来，点一下就能重下。
 *
 * 存的是「哪个音源 + 音源里的哪个 id」和展示信息 —— 直链有时效，不能存。
 */
data class FailedDownload(
    val id: String,
    val sourceId: String,
    val songId: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    val cover: String? = null,
    val quality: String = "high",
    /** 失败原因，直接给用户看。 */
    val reason: String,
    val at: Long = System.currentTimeMillis()
) {
    fun toOnlineSong(): OnlineSong = OnlineSong(
        songId = songId,
        name = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        cover = cover,
        sourceId = sourceId
    )
}

/**
 * 下载失败的账本。
 *
 * 同一首歌反复失败只留最新那条：失败原因会变（先是超时、后来是"音源已被删除"），
 * 而用户要看的是「现在为什么不行」，翻五条一模一样的记录没有意义。
 */
object FailedDownloadStore {

    private const val FILE = "download_failures.json"

    /** 最多留这么多条，再多就没人会去逐条重试了。 */
    private const val LIMIT = 200

    @Volatile
    private var memory: List<FailedDownload>? = null

    private fun file(context: Context) = File(context.filesDir, FILE)

    @Synchronized
    fun load(context: Context): List<FailedDownload> {
        memory?.let { return it }
        val text = runCatching { file(context).readText() }.getOrNull()
        if (text.isNullOrBlank()) {
            memory = emptyList()
            return emptyList()
        }
        val list = runCatching {
            val array = JSONArray(text)
            val out = ArrayList<FailedDownload>(array.length())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val songId = o.optString("songId")
                if (songId.isBlank()) continue
                out.add(
                    FailedDownload(
                        id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                        sourceId = o.optString("sourceId"),
                        songId = songId,
                        title = o.optString("title"),
                        artist = o.optString("artist"),
                        album = o.optString("album"),
                        durationMs = o.optLong("durationMs", 0L),
                        cover = o.optString("cover").takeIf { it.isNotBlank() },
                        quality = o.optString("quality", "high"),
                        reason = o.optString("reason"),
                        at = o.optLong("at", System.currentTimeMillis())
                    )
                )
            }
            out
        }.getOrDefault(emptyList())
        memory = list
        return list
    }

    @Synchronized
    private fun save(context: Context, list: List<FailedDownload>) {
        memory = list
        val array = JSONArray()
        list.forEach { d ->
            array.put(JSONObject().apply {
                put("id", d.id)
                put("sourceId", d.sourceId)
                put("songId", d.songId)
                put("title", d.title)
                put("artist", d.artist)
                put("album", d.album)
                put("durationMs", d.durationMs)
                put("cover", d.cover ?: "")
                put("quality", d.quality)
                put("reason", d.reason)
                put("at", d.at)
            })
        }
        runCatching { file(context).writeText(array.toString()) }
            .onFailure { android.util.Log.e("FailedDownloadStore", "save failed", it) }
    }

    /** 记一次失败。同一首歌只保留最新那条。 */
    @Synchronized
    fun record(context: Context, item: FailedDownload) {
        val list = load(context).filterNot {
            it.sourceId == item.sourceId && it.songId == item.songId
        }
        // 新的排前面：用户最关心的是刚才那次
        save(context, (listOf(item) + list).take(LIMIT))
    }

    @Synchronized
    fun remove(context: Context, sourceId: String, songId: String) {
        val list = load(context)
        val next = list.filterNot { it.sourceId == sourceId && it.songId == songId }
        if (next.size != list.size) save(context, next)
    }

    @Synchronized
    fun removeById(context: Context, id: String) {
        val list = load(context)
        val next = list.filterNot { it.id == id }
        if (next.size != list.size) save(context, next)
    }

    @Synchronized
    fun clear(context: Context) {
        save(context, emptyList())
    }
}
