package com.tom.musicplayer.data.online

import android.content.Context
import com.tom.musicplayer.model.SourceKind
import com.tom.musicplayer.model.Song
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 一首下载好的歌。
 *
 * 记的是「下到哪了」而不是「下没下完」：App 私有目录记 [path]，
 * 授权文件夹记 [treeUri] + [displayName]（SAF 的 Uri 不能长期硬存，
 * 但 treeUri 的读权限是可持久授权的，重新拼一次就能拿到文件）。
 */
data class DownloadedSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    /** App 私有目录下的相对路径；存到授权文件夹时为空。 */
    val path: String?,
    /** 存到授权文件夹时的目录树授权 Uri。 */
    val treeUri: String?,
    /** 存到授权文件夹时的文件名（含扩展名）。 */
    val displayName: String?,
    val sizeBytes: Long,
    val sourceId: String?,
    val songId: String?,
    val quality: String,
    val finishedAt: Long
) {
    val isInAppDir: Boolean get() = !path.isNullOrBlank()
}

/** 已下载歌曲的本地账本。只记 App 私有目录那一半 + 授权文件夹那一半的索引。 */
object DownloadStore {

    private const val FILE = "downloads.json"

    @Volatile
    private var memory: List<DownloadedSong>? = null

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun load(context: Context): List<DownloadedSong> {
        memory?.let { return it }
        val text = runCatching { file(context).readText() }.getOrNull() ?: return emptyList()
        if (text.isBlank()) return emptyList()
        val list = runCatching {
            val array = JSONArray(text)
            val out = ArrayList<DownloadedSong>(array.length())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val path = o.optString("path").takeIf { it.isNotBlank() }
                val name = o.optString("displayName").takeIf { it.isNotBlank() }
                if (path == null && name == null) continue
                out.add(
                    DownloadedSong(
                        id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                        title = o.optString("title"),
                        artist = o.optString("artist"),
                        album = o.optString("album"),
                        durationMs = o.optLong("durationMs", 0L),
                        path = path,
                        treeUri = o.optString("treeUri").takeIf { it.isNotBlank() },
                        displayName = name,
                        sizeBytes = o.optLong("sizeBytes", 0L),
                        sourceId = o.optString("sourceId").takeIf { it.isNotBlank() },
                        songId = o.optString("songId").takeIf { it.isNotBlank() },
                        quality = o.optString("quality", "high"),
                        finishedAt = o.optLong("finishedAt", System.currentTimeMillis())
                    )
                )
            }
            out
        }.getOrDefault(emptyList())
        memory = list
        return list
    }

    private fun save(context: Context, list: List<DownloadedSong>) {
        memory = list
        val array = JSONArray()
        list.forEach { d ->
            array.put(JSONObject().apply {
                put("id", d.id)
                put("title", d.title)
                put("artist", d.artist)
                put("album", d.album)
                put("durationMs", d.durationMs)
                put("path", d.path ?: "")
                put("treeUri", d.treeUri ?: "")
                put("displayName", d.displayName ?: "")
                put("sizeBytes", d.sizeBytes)
                put("sourceId", d.sourceId ?: "")
                put("songId", d.songId ?: "")
                put("quality", d.quality)
                put("finishedAt", d.finishedAt)
            })
        }
        runCatching { file(context).writeText(array.toString()) }
            .onFailure { android.util.Log.e("DownloadStore", "save failed", it) }
    }

    fun add(context: Context, item: DownloadedSong) {
        save(context, load(context) + item)
    }

    fun remove(context: Context, id: String) {
        save(context, load(context).filterNot { it.id == id })
    }

    fun isDownloaded(context: Context, sourceId: String?, songId: String?): Boolean {
        if (sourceId.isNullOrBlank() || songId.isNullOrBlank()) return false
        return load(context).any { it.sourceId == sourceId && it.songId == songId }
    }

    /** 「我的下载」页要能把已下载的歌直接拿去播，所以这里还原成一个 [Song]。 */
    fun toSong(context: Context, d: DownloadedSong): Song? {
        return when {
            d.isInAppDir -> {
                val file = File(context.filesDir, d.path!!)
                if (!file.exists()) return null
                Song(
                    id = d.id,
                    uri = android.net.Uri.fromFile(file),
                    title = d.title,
                    artist = d.artist,
                    album = d.album,
                    durationMs = d.durationMs,
                    fileName = file.name,
                    kind = SourceKind.LOCAL,
                    sizeBytes = file.length()
                )
            }
            else -> null // 存进授权文件夹的那些，交给正常的本地扫描去收录
        }
    }
}
