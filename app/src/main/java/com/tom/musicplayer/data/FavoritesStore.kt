package com.tom.musicplayer.data

import android.content.Context
import android.net.Uri
import com.tom.musicplayer.data.online.DownloadStore
import com.tom.musicplayer.model.Song
import com.tom.musicplayer.model.SourceKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一首「点心」的歌。
 *
 * 以前这里只存一个歌曲 id，够用来画心形，但没法把歌还原出来 ——
 * 所以「我点心的歌曲」这个歌单里只剩一堆 id，根本播不了。
 * 现在把展示信息和身份一起存下来，它本身就是歌单里的一条记录。
 *
 * 只存身份和展示信息，**不存播放直链**：在线直链有时效，存下来会过期；
 * 本地文件存的是文件 Uri，播放时再确认文件还在。
 */
data class FavoriteSong(
    /** 规范 key：[keyOf]。在线歌是 `online:音源:歌曲`，本地歌是文件 Uri / 下载记录 id。 */
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val kind: SourceKind = SourceKind.LOCAL,
    /** 本地 / WebDAV 的可播 Uri。在线歌为 null（播放时向音源现要）。 */
    val uri: String? = null,
    val fileName: String = "",
    /** WebDAV 源文件路径，重算直链要用。 */
    val remotePath: String? = null,
    val onlineSourceId: String? = null,
    val onlineSongId: String? = null,
    val coverUrl: String? = null,
    val addedAt: Long = System.currentTimeMillis()
) {
    val isOnline: Boolean get() = kind == SourceKind.ONLINE

    /** 能对应到音源里的某一首歌（下载下来的在线歌也算）。 */
    val hasOnlineIdentity: Boolean
        get() = !onlineSourceId.isNullOrBlank() && !onlineSongId.isNullOrBlank()

    /** 还原成可播的 [Song]。 */
    fun toSong(): Song {
        val placeholder = if (hasOnlineIdentity) {
            Uri.parse("qingting://online").buildUpon()
                .appendPath(onlineSourceId!!)
                .appendPath(onlineSongId!!)
                .build()
        } else {
            Uri.EMPTY
        }
        val parsed = uri?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
        return Song(
            id = id,
            uri = parsed ?: placeholder,
            title = title.ifBlank { fileName.ifBlank { "未知歌曲" } },
            artist = artist.ifBlank { "未知艺术家" },
            album = album,
            durationMs = durationMs,
            fileName = fileName,
            kind = kind,
            remotePath = remotePath,
            onlineSourceId = onlineSourceId,
            onlineSongId = onlineSongId,
            coverUrl = coverUrl
        )
    }

    fun withMeta(song: Song): FavoriteSong = copy(
        title = song.title,
        artist = song.artist,
        album = song.album,
        durationMs = song.durationMs,
        kind = song.kind,
        uri = uri ?: song.uri.toString().takeIf { !song.isOnline && song.uri != Uri.EMPTY },
        fileName = fileName.ifBlank { song.fileName },
        remotePath = remotePath ?: song.remotePath,
        coverUrl = coverUrl ?: song.coverUrl
    )
}

/**
 * 点心（收藏）。
 *
 * 两层含义，其实是一件事：
 * 1. 列表 / 播放页上的心形图标亮不亮；
 * 2. 「我的歌单 → 我点心的歌曲」里的内容。
 *
 * 所以它存的必须是能播的东西，不能只是一个 id —— 见 [FavoriteSong]。
 */
object FavoritesStore {

    private const val FILE = "favorites.json"

    @Volatile
    private var memory: MutableList<FavoriteSong>? = null

    private fun file(context: Context) = File(context.filesDir, FILE)

    /**
     * 规范 key。
     *
     * 同一首歌可能有两副面孔：音源里的那一版，和下载到本地的那一版。
     * 二者的 song.id 不一样（下载版是下载记录自己的 uuid，扫描进来的是文件 Uri），
     * 如果各按各的 id 记，点过心的歌下载完就会在歌单里出现两条，取消一条另一条还在。
     *
     * 所以有音源身份时以音源身份为准。曲库扫描出来的文件不携带音源信息，
     * 这里再用文件名反查一次下载记录（见 [DownloadStore.identityOf]）——
     * 下过的歌已经从音源「落到」文件夹里，它仍然是同一首，收藏不该分成两套。
     */
    fun keyOf(song: Song): String {
        if (song.kind == SourceKind.LOCAL) {
            DownloadStore.identityOf(song.fileName, song.sizeBytes)?.let { (sourceId, songId) ->
                return keyOf(sourceId, songId, song.id)
            }
        }
        return keyOf(song.onlineSourceId, song.onlineSongId, song.id)
    }

    /** 同上，给只有身份信息、还没拼成 [Song] 的地方用（例如下载记录）。 */
    fun keyOf(sourceId: String?, songId: String?, fallback: String): String =
        if (!sourceId.isNullOrBlank() && !songId.isNullOrBlank()) "online:$sourceId:$songId" else fallback

    /** 由 [Song] 造一条收藏记录。 */
    fun from(song: Song): FavoriteSong {
        // 本地文件如果是下载来的，把音源身份一并写上：这样它能和在线版合并成一条，
        // 哪天下载的文件被删了，还能回退到在线播
        val ref = if (song.kind == SourceKind.LOCAL) {
            DownloadStore.identityOf(song.fileName, song.sizeBytes)
        } else null
        return FavoriteSong(
            id = keyOf(song),
            title = song.title,
            artist = song.artist,
            album = song.album,
            durationMs = song.durationMs,
            kind = song.kind,
            uri = song.uri.toString().takeIf { !song.isOnline && song.uri != Uri.EMPTY },
            fileName = song.fileName,
            remotePath = song.remotePath,
            onlineSourceId = song.onlineSourceId ?: ref?.first,
            onlineSongId = song.onlineSongId ?: ref?.second,
            coverUrl = song.coverUrl
        )
    }

    @Synchronized
    private fun list(context: Context): MutableList<FavoriteSong> {
        memory?.let { return it }
        val text = runCatching { file(context).readText() }.getOrNull()
        val loaded = if (text.isNullOrBlank()) ArrayList() else parse(text)
        memory = loaded
        return loaded
    }

    fun entries(context: Context): List<FavoriteSong> = list(context).toList()

    /** 收藏 key 集合，给心形图标和计数用。 */
    fun all(context: Context): Set<String> =
        list(context).mapTo(LinkedHashSet()) { it.id }

    fun isFavorite(context: Context, song: Song): Boolean =
        list(context).any { it.id == keyOf(song) }

    /**
     * 切换收藏，返回切换后的状态。
     *
     * 写文件是同步的（一次几 KB），但调用方一般都在 IO 线程，
     * 因为紧接着还要把新的集合推给界面。
     */
    @Synchronized
    fun toggle(context: Context, song: Song): Boolean {
        val key = keyOf(song)
        val data = list(context)
        val index = data.indexOfFirst { it.id == key }
        if (index >= 0) {
            data.removeAt(index)
            persist(context, data)
            return false
        }
        data.add(from(song))
        persist(context, data)
        return true
    }

    /**
     * 直接把一首歌补进歌单（不做切换）。
     *
     * 已经在了就顺手把元数据更新一遍：在线歌第一次收藏时可能没有专辑信息，
     * 后面从歌单详情、榜单里再看到它时信息就全了。
     */
    @Synchronized
    fun put(context: Context, song: Song) {
        val key = keyOf(song)
        val data = list(context)
        val index = data.indexOfFirst { it.id == key }
        if (index >= 0) {
            data[index] = data[index].withMeta(song)
        } else {
            data.add(from(song))
        }
        persist(context, data)
    }

    /**
     * 直接写入一条完整的收藏记录（导入备份用）。
     *
     * 和 [put] 的区别：这里不重新算 key、也不猜音源身份 —— 备份里的记录
     * 本来就是从这个结构序列化出去的，原样放回来最不容易出错。
     */
    @Synchronized
    fun putEntry(context: Context, entry: FavoriteSong) {
        val data = list(context)
        val index = data.indexOfFirst { it.id == entry.id }
        if (index >= 0) data[index] = entry else data.add(entry)
        persist(context, data)
    }

    /** 清空收藏（「重置」用）。 */
    @Synchronized
    fun clear(context: Context) {
        persist(context, emptyList())
    }

    @Synchronized
    fun remove(context: Context, keys: Collection<String>) {
        val data = list(context)
        if (data.removeAll { it.id in keys }) persist(context, data)
    }

    /**
     * 用眼前这些「活的」歌把收藏记录补全。
     *
     * 早先版本只存了 id，升级上来的老记录没有标题、没有歌手，播也播不了。
     * 曲库扫描完、下载记录载入后各调一次，能补上的就补上并落盘；
     * 补不上的（歌已经从文件夹里删了）留着 id，等下次再说 —— 总比直接丢掉强。
     *
     * @return 真的补上了几条
     */
    @Synchronized
    fun enrich(context: Context, songs: List<Song>): Int {
        if (songs.isEmpty()) return 0
        // 顺手把「按文件记的」老收藏改写成音源身份，免得和在线那份各占一条
        normalize(context)
        val data = list(context)
        var filled = 0
        for (i in data.indices) {
            val entry = data[i]
            val live = songs.firstOrNull { keyOf(it) == entry.id }
                ?: songs.firstOrNull {
                    entry.fileName.isNotBlank() && it.fileName == entry.fileName
                }
                ?: continue
            if (entry.title.isNotBlank() && entry.uri != null) continue
            data[i] = entry.withMeta(live)
            filled++
        }
        if (filled > 0) persist(context, data)
        return filled
    }

    // ---------------------------------------------------------------- 存取

    /**
     * 把「按文件记的」收藏升级成「按音源身份记的」。
     *
     * 早期版本在曲库里点心下载下来的歌时，只能记文件 Uri；同一天这首歌又从
     * 「在线」点心过一次的话，歌单里就有两条看起来一模一样的记录。现在下载记录里
     * 存了音源身份，启动时按文件名把老记录改写成规范 key，撞在一起的就合成一条。
     *
     * @return 改写的条数（0 表示不需要动）
     */
    @Synchronized
    fun normalize(context: Context): Int {
        val data = list(context)
        if (data.isEmpty()) return 0
        var changed = 0
        val merged = ArrayList<FavoriteSong>(data.size)
        data.forEach { raw ->
            var entry = raw
            if (!entry.hasOnlineIdentity && entry.fileName.isNotBlank()) {
                DownloadStore.identityOf(entry.fileName)?.let { (sourceId, songId) ->
                    entry = entry.copy(
                        id = keyOf(sourceId, songId, entry.id),
                        onlineSourceId = sourceId,
                        onlineSongId = songId
                    )
                    changed++
                }
            }
            val at = merged.indexOfFirst { it.id == entry.id }
            if (at >= 0) {
                // 同一首歌的两条记录：留下信息更全的那份（标题、时长通常来自在线那份）
                val old = merged[at]
                merged[at] = when {
                    entry.title.isNotBlank() && old.title.isBlank() -> entry.copy(addedAt = minOf(old.addedAt, entry.addedAt))
                    entry.title.isBlank() && old.title.isNotBlank() -> old.copy(addedAt = minOf(old.addedAt, entry.addedAt))
                    else -> if (entry.addedAt < old.addedAt) entry else old
                }
                changed++
            } else {
                merged.add(entry)
            }
        }
        if (changed > 0) persist(context, merged)
        return changed
    }

    private fun parse(text: String): MutableList<FavoriteSong> {
        val out = ArrayList<FavoriteSong>()
        val trimmed = text.trim()
        if (trimmed.startsWith("{")) {
            val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return out
            val array = root.optJSONArray("songs") ?: return out
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isBlank()) continue
                out.add(
                    FavoriteSong(
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
                )
            }
            return out
        }
        // 旧格式：一个纯字符串数组，只有 id
        val array = runCatching { JSONArray(trimmed) }.getOrNull() ?: return out
        for (i in 0 until array.length()) {
            val raw = array.optString(i).takeIf { it.isNotBlank() } ?: continue
            out.add(legacyEntry(raw))
        }
        return out
    }

    /** 老记录只有 id：能看出是哪种歌就先标好，剩下的等 [enrich] 补。 */
    private fun legacyEntry(raw: String): FavoriteSong {
        if (raw.startsWith("online:")) {
            val parts = raw.split(":", limit = 3)
            if (parts.size == 3) {
                return FavoriteSong(
                    id = raw,
                    title = "",
                    artist = "",
                    album = "",
                    durationMs = 0L,
                    kind = SourceKind.ONLINE,
                    onlineSourceId = parts[1],
                    onlineSongId = parts[2]
                )
            }
        }
        return FavoriteSong(
            id = raw,
            title = "",
            artist = "",
            album = "",
            durationMs = 0L,
            kind = SourceKind.LOCAL,
            uri = raw.takeIf { it.startsWith("content://") || it.startsWith("file://") }
        )
    }

    @Synchronized
    private fun persist(context: Context, data: List<FavoriteSong>) {
        memory = data.toMutableList()
        val array = JSONArray()
        data.forEach { e ->
            array.put(JSONObject().apply {
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
            })
        }
        runCatching {
            file(context).writeText(
                JSONObject().apply {
                    put("v", 2)
                    put("songs", array)
                }.toString()
            )
        }.onFailure { android.util.Log.e("FavoritesStore", "save failed", it) }
    }
}
