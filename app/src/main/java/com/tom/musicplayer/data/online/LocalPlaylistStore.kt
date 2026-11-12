package com.tom.musicplayer.data.online

import android.content.Context
import com.tom.musicplayer.data.FavoriteSong
import com.tom.musicplayer.data.FavoritesStore
import com.tom.musicplayer.model.OnlineSong
import com.tom.musicplayer.model.Song
import com.tom.musicplayer.model.SourceKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 自建歌单。存在本机，和音源无关——音源那边挂了、换了个源，你自己攒的歌单还在。
 *
 * 一首歌只存「身份 + 展示信息」，不存播放地址：在线直链有时效，本地文件可能被挪走，
 * 播之前都要按 [com.tom.musicplayer.data.SongRefResolver] 重新认一遍。
 *
 * 早期版本这里只放得下在线歌（[OnlineSong]）。现在曲库里扫到的、下载到本地的歌
 * 也能进歌单，所以条目换成和点心同一套 [FavoriteSong]：三种来源都描述得了。
 * 磁盘上的老格式照样读，旧记录会被当成在线歌升级上来。
 */
data class LocalPlaylist(
    val id: String,
    val name: String,
    val songs: List<FavoriteSong> = emptyList(),
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
                out.add(
                    LocalPlaylist(
                        id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = o.optString("name").ifBlank { "我的歌单" },
                        songs = readSongs(o.optJSONArray("songs")),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
            out
        }.getOrDefault(emptyList())
        memory = list
        return list
    }

    private fun readSongs(arr: JSONArray?): List<FavoriteSong> {
        if (arr == null) return emptyList()
        val songs = ArrayList<FavoriteSong>(arr.length())
        for (j in 0 until arr.length()) {
            val s = arr.optJSONObject(j) ?: continue
            entryOf(s)?.let { songs.add(it) }
        }
        return songs
    }

    private fun save(context: Context, list: List<LocalPlaylist>) {
        memory = list
        val array = JSONArray()
        list.forEach { p ->
            val songs = JSONArray()
            p.songs.forEach { songs.put(entryJson(it)) }
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

    /** 歌单里已经有哪些 key，选歌页据此把「已在歌单里」的置灰。 */
    fun keysOf(context: Context, id: String): Set<String> =
        load(context).firstOrNull { it.id == id }?.songs?.mapTo(HashSet()) { it.id } ?: emptySet()

    /** 在线页「加到歌单」走这里：把 [OnlineSong] 转成通用条目。 */
    @Synchronized
    fun addSong(context: Context, id: String, song: OnlineSong): Boolean {
        val src = song.sourceId
        val sid = song.songId
        val entry = FavoriteSong(
            id = FavoritesStore.keyOf(src, sid, song.globalId),
            title = song.name,
            artist = song.artist,
            album = song.album,
            durationMs = song.durationMs,
            kind = SourceKind.ONLINE,
            onlineSourceId = src.takeIf { it.isNotBlank() },
            onlineSongId = sid.takeIf { it.isNotBlank() },
            coverUrl = song.cover?.takeIf { it.isNotBlank() }
        )
        return addEntries(context, id, listOf(entry)) > 0
    }

    /**
     * 从曲库 / 下载页往歌单里加歌。
     *
     * 同一首歌可能既在「我的下载」、又被曲库扫到一次，两者的 [Song.id] 不同；
     * [FavoritesStore.keyOf] 会按文件名反查下载记录归一到音源身份，
     * 再按文件名兜底一次，避免同一份文件占两行。
     *
     * @return 真正加进去几首（重复的会跳过）
     */
    @Synchronized
    fun addSongs(context: Context, id: String, songs: List<Song>): Int {
        val entries = songs.map { FavoritesStore.from(it) }
        return addEntries(context, id, entries)
    }

    @Synchronized
    private fun addEntries(context: Context, id: String, entries: List<FavoriteSong>): Int {
        val list = load(context)
        val at = list.indexOfFirst { it.id == id }
        if (at < 0) return 0
        val current = list[at]
        val seenIds = current.songs.mapTo(HashSet()) { it.id }
        val seenNames = current.songs.mapNotNullTo(HashSet()) {
            it.fileName.takeIf { n -> n.isNotBlank() }?.lowercase()
        }
        val extra = ArrayList<FavoriteSong>()
        entries.forEach { e ->
            val name = e.fileName.takeIf { it.isNotBlank() }?.lowercase()
            if (e.id in seenIds || (name != null && name in seenNames)) return@forEach
            seenIds.add(e.id)
            name?.let { seenNames.add(it) }
            extra.add(e)
        }
        if (extra.isEmpty()) return 0
        val updated = list.toMutableList()
        updated[at] = current.copy(songs = current.songs + extra)
        save(context, updated)
        return extra.size
    }

    @Synchronized
    fun removeSong(context: Context, id: String, entryId: String) {
        save(context, load(context).map { p ->
            if (p.id != id) return@map p
            p.copy(songs = p.songs.filterNot { it.id == entryId })
        })
    }

    /** 直接存一整份（用于把导入的在线歌单「收藏」成本地歌单、以及备份导入）。 */
    fun saveAll(context: Context, playlists: List<LocalPlaylist>) = save(context, playlists)

    // ---------------------------------------------------------------- 条目序列化

    /**
     * 新旧两种条目格式都认：
     * - 新版有 `id`（规范 key）和 `kind`，是完整的 [FavoriteSong]；
     * - 旧版是 [OnlineSong] 的 `songId / name / ...`，升级成一条在线记录。
     *
     * 备份文件里也可能混着这两种，所以这个判断放在这里，两处共用。
     */
    fun entryOf(o: JSONObject): FavoriteSong? {
        if (o.has("id") && o.optString("id").isNotBlank()) {
            val id = o.optString("id")
            return FavoriteSong(
                id = id,
                title = o.optString("title"),
                artist = o.optString("artist"),
                album = o.optString("album"),
                durationMs = o.optLong("durationMs", 0L),
                kind = SourceKind.parse(o.optString("kind")),
                uri = o.optString("uri").takeIf { it.isNotBlank() },
                fileName = o.optString("file"),
                remotePath = o.optString("remote").takeIf { it.isNotBlank() },
                onlineSourceId = o.optString("sourceId").takeIf { it.isNotBlank() },
                onlineSongId = o.optString("songId").takeIf { it.isNotBlank() },
                coverUrl = o.optString("cover").takeIf { it.isNotBlank() },
                addedAt = o.optLong("addedAt", System.currentTimeMillis())
            )
        }
        val songId = o.optString("songId")
        if (songId.isBlank()) return null
        val src = o.optString("sourceId")
        return FavoriteSong(
            id = FavoritesStore.keyOf(src, songId, "online:$src:$songId"),
            title = o.optString("name"),
            artist = o.optString("artist"),
            album = o.optString("album"),
            durationMs = o.optLong("durationMs", 0L),
            kind = SourceKind.ONLINE,
            onlineSourceId = src.takeIf { it.isNotBlank() },
            onlineSongId = songId,
            coverUrl = o.optString("cover").takeIf { it.isNotBlank() }
        )
    }

    fun entryJson(e: FavoriteSong): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("title", e.title)
        put("artist", e.artist)
        put("album", e.album)
        put("durationMs", e.durationMs)
        put("kind", e.kind.value)
        put("uri", e.uri ?: "")
        put("file", e.fileName)
        put("remote", e.remotePath ?: "")
        put("sourceId", e.onlineSourceId ?: "")
        put("songId", e.onlineSongId ?: "")
        put("cover", e.coverUrl ?: "")
        put("addedAt", e.addedAt)
    }
}
