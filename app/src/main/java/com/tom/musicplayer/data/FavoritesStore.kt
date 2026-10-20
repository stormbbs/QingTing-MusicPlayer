package com.tom.musicplayer.data

import android.content.Context
import org.json.JSONArray
import java.io.File

/** 收藏（我的喜欢）：只记歌曲 id，方便跨音源复用。 */
object FavoritesStore {

    private const val FILE = "favorites.json"

    @Volatile
    private var memory: MutableSet<String>? = null

    private fun set(context: Context): MutableSet<String> {
        memory?.let { return it }
        val loaded = LinkedHashSet<String>()
        runCatching {
            val text = File(context.filesDir, FILE).readText()
            if (text.isNotBlank()) {
                val array = JSONArray(text)
                for (i in 0 until array.length()) loaded.add(array.optString(i))
            }
        }
        memory = loaded
        return loaded
    }

    fun all(context: Context): Set<String> = set(context).toSet()

    fun isFavorite(context: Context, songId: String): Boolean = songId in set(context)

    /** 切换收藏状态，返回切换后的结果。 */
    fun toggle(context: Context, songId: String): Boolean {
        val data = set(context)
        val added = if (songId in data) {
            data.remove(songId)
            false
        } else {
            data.add(songId)
            true
        }
        persist(context, data)
        return added
    }

    private fun persist(context: Context, data: Set<String>) {
        runCatching {
            val array = JSONArray()
            data.forEach { array.put(it) }
            File(context.filesDir, FILE).writeText(array.toString())
        }
    }
}
