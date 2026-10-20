package com.tom.musicplayer.data.online

import android.content.Context
import com.tom.musicplayer.model.OnlineSong
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 自建歌单。存在本机，和音源无关——音源那边挂了、换了个源，你自己攒的歌单还在。
 *
 * 每首歌只存「标识 + 展示信息」，不存播放地址：地址每次播都要现解析，存下来只会过期。
 */
data class LocalPlaylist(
    val id: String,
    val name: String,
    val songs: List<OnlineSong> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

object LocalPlaylistStore {

    private const val FILE = "local_playlists.json"

    @Volatile
    private var memory: List<LocalPlaylist>? = null

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun load(context: Context): List<LocalPlaylist> {
        memory?.let { return it }
        val text = runCatching { file(context).readText() }.getOrNull() ?: return emptyList()
        if (text.isBlank()) return emptyList()
        val list = runCatching {
            val array = JSONArray(text)
            val out = ArrayList<LocalPlaylist>(array.length())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val songs = ArrayList<OnlineSong>()
                o.optJSONArray("songs")?.let { arr ->
                    for (j in 0 until arr.length()) {
                        val s = arr.getJSONObject(j)
                        val id = s.optString("songId")
                        if (id.isBlank()) continue
                        songs.add(
                            OnlineSong(
                                songId = id,
                                name = s.optString("name"),
                                artist = s.optString("artist"),
                                album = s.optString("album"),
                                durationMs = s.optLong("durationMs", 0L),
                                cover = s.optString("cover").takeIf { it.isNotBlank() },
                                sourceId = s.optString("sourceId")
                            )
                        )
                    }
                }
                out.add(
                    LocalPlaylist(
                        id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = o.optString("name").ifBlank { "我的歌单" },
                        songs = songs,
                        createdAt = o.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
            out
        }.getOrDefault(emptyList())
        memory = list
        return list
    }

    private fun save(context: Context, list: List<LocalPlaylist>) {
        memory = list
        val array = JSONArray()
        list.forEach { p ->
            val songs = JSONArray()
            p.songs.forEach { s ->
                songs.put(JSONObject().apply {
                    put("songId", s.songId)
                    put("name", s.name)
                    put("artist", s.artist)
                    put("album", s.album)
                    put("durationMs", s.durationMs)
                    put("cover", s.cover ?: "")
                    put("sourceId", s.sourceId)
                })
            }
            array.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("songs", songs)
                put("createdAt", p.createdAt)
            })
        }
        runCatching { file(context).writeText(array.toString()) }
            .onFailure { android.util.Log.e("LocalPlaylistStore", "save failed", it) }
    }

    fun create(context: Context, name: String): LocalPlaylist {
        val p = LocalPlaylist(id = UUID.randomUUID().toString(), name = name)
        save(context, load(context) + p)
        return p
    }

    fun rename(context: Context, id: String, name: String) {
        save(context, load(context).map { if (it.id == id) it.copy(name = name) else it })
    }

    fun remove(context: Context, id: String) {
        save(context, load(context).filterNot { it.id == id })
    }

    /** 加歌。同一首（同一音源 + 同一 id）不重复加。 */
    fun addSong(context: Context, id: String, song: OnlineSong) {
        save(context, load(context).map { p ->
            if (p.id != id) return@map p
            if (p.songs.any { it.sourceId == song.sourceId && it.songId == song.songId }) return@map p
            p.copy(songs = p.songs + song)
        })
    }

    fun removeSong(context: Context, id: String, song: OnlineSong) {
        save(context, load(context).map { p ->
            if (p.id != id) return@map p
            p.copy(songs = p.songs.filterNot { it.songId == song.songId && it.sourceId == song.sourceId })
        })
    }

    /** 直接存一整份（用于把导入的在线歌单「收藏」成本地歌单）。 */
    fun saveAll(context: Context, playlists: List<LocalPlaylist>) = save(context, playlists)
}
