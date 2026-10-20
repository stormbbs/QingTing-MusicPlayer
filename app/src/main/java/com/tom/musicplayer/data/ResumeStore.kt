package com.tom.musicplayer.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 每首歌各自的播放进度。
 *
 * 与 [Prefs] 里那个「最后一首 + 它的位置」不同：这里记的是所有听过的歌，
 * 所以从列表点开一首已经听过的歌，会从上次停下的地方继续，而不是从头来。
 */
object ResumeStore {

    private const val FILE = "resume.json"
    /** 只保留最近听过的这些首，防止听歌量大时文件无限膨胀。 */
    private const val MAX_ENTRIES = 600

    @Volatile
    private var memory: LinkedHashMap<String, Long>? = null

    private fun map(context: Context): LinkedHashMap<String, Long> {
        memory?.let { return it }
        val loaded = LinkedHashMap<String, Long>()
        runCatching {
            val text = File(context.filesDir, FILE).readText()
            if (text.isNotBlank()) {
                val json = JSONObject(text)
                json.keys().forEach { key ->
                    loaded[key] = json.optLong(key, 0L)
                }
            }
        }
        memory = loaded
        return loaded
    }

    fun get(context: Context, songId: String): Long = map(context)[songId] ?: 0L

    /** 只更新内存，落盘交给 [flush] —— 播放中每 2 秒就会更新一次进度。 */
    fun mark(context: Context, songId: String, positionMs: Long) {
        val data = map(context)
        data.remove(songId)
        data[songId] = positionMs
        while (data.size > MAX_ENTRIES) {
            val oldest = data.keys.firstOrNull() ?: break
            data.remove(oldest)
        }
    }

    suspend fun flush(context: Context) = withContext(Dispatchers.IO) {
        val data = memory ?: return@withContext
        runCatching {
            val json = JSONObject()
            data.forEach { (key, value) -> json.put(key, value) }
            File(context.filesDir, FILE).writeText(json.toString())
        }
    }
}
