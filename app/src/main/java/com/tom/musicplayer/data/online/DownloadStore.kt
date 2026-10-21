package com.tom.musicplayer.data.online

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.tom.musicplayer.model.SourceKind
import com.tom.musicplayer.model.Song
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 一首下载好的歌。
 *
 * 记的是「下到哪了」而不是「下没下完」，三种落点各记各的：
 * - 公共下载目录（媒体库）：记 [fileUri]（content://），卸载重装后仍然有效；
 * - 旧版私有目录：记 [legacyPath]，下次启动会迁移到公共目录，迁移完就填 [fileUri]；
 * - 用户选的音乐文件夹（SAF）：记 [treeUri] + [displayName]（文档 Uri 不能长期硬存，
 *   但目录树的读权限是可持久授权的，重新拼一次就能拿到文件）。
 */
data class DownloadedSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    /** 可直接播放的 Uri（content:// 或 file://）。SAF 落点的这一项由目录树现解。 */
    val fileUri: String? = null,
    /** 给人看的位置，例如「内部存储/Music/轻听/周杰伦 - 晴天.mp3」。 */
    val location: String? = null,
    /** 旧版私有目录里的相对路径，仅用于迁移。 */
    val legacyPath: String? = null,
    /** SAF 落点：目录树 Uri。 */
    val treeUri: String? = null,
    /** SAF 落点：文件名（含扩展名）。 */
    val displayName: String? = null,
    val sizeBytes: Long = 0L,
    val sourceId: String? = null,
    val songId: String? = null,
    val quality: String = "high",
    val finishedAt: Long = System.currentTimeMillis()
) {
    val isSaf: Boolean get() = !treeUri.isNullOrBlank()

    /** 还躺在旧版私有目录里，等着搬到公共目录。 */
    val needsMigration: Boolean get() = fileUri.isNullOrBlank() && !legacyPath.isNullOrBlank()

    /** 有值的都算「有落点」，界面上据此决定要不要显示位置。 */
    val where: String
        get() = when {
            !location.isNullOrBlank() -> location
            isSaf -> displayName.orEmpty()
            !legacyPath.isNullOrBlank() -> legacyPath
            else -> ""
        }
}

/** 已下载歌曲的本地账本。 */
object DownloadStore {

    private const val FILE = "downloads.json"

    @Volatile
    private var memory: List<DownloadedSong>? = null

    private fun file(context: Context) = File(context.filesDir, FILE)

    @Synchronized
    fun load(context: Context): List<DownloadedSong> {
        memory?.let { return it }
        val text = runCatching { file(context).readText() }.getOrNull() ?: return emptyList()
        if (text.isBlank()) return emptyList()
        val list = runCatching {
            val array = JSONArray(text)
            val out = ArrayList<DownloadedSong>(array.length())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val fileUri = o.optString("fileUri").takeIf { it.isNotBlank() }
                val location = o.optString("location").takeIf { it.isNotBlank() }
                val tree = o.optString("treeUri").takeIf { it.isNotBlank() }
                val name = o.optString("displayName").takeIf { it.isNotBlank() }
                // "path" 是旧版字段（相对 filesDir 的路径），只当迁移线索用
                val legacy = o.optString("path").takeIf { it.isNotBlank() }
                if (fileUri == null && tree == null && legacy == null) continue
                out.add(
                    DownloadedSong(
                        id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                        title = o.optString("title"),
                        artist = o.optString("artist"),
                        album = o.optString("album"),
                        durationMs = o.optLong("durationMs", 0L),
                        fileUri = fileUri,
                        location = location,
                        legacyPath = legacy,
                        treeUri = tree,
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

    @Synchronized
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
                put("fileUri", d.fileUri ?: "")
                put("location", d.location ?: "")
                put("path", d.legacyPath ?: "")
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

    @Synchronized
    fun add(context: Context, item: DownloadedSong) {
        save(context, load(context) + item)
    }

    @Synchronized
    fun replaceAll(context: Context, list: List<DownloadedSong>) {
        save(context, list)
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        save(context, load(context).filterNot { it.id == id })
    }

    @Synchronized
    fun update(context: Context, item: DownloadedSong) {
        save(context, load(context).map { if (it.id == item.id) item else it })
    }

    fun isDownloaded(context: Context, sourceId: String?, songId: String?): Boolean {
        if (sourceId.isNullOrBlank() || songId.isNullOrBlank()) return false
        return load(context).any { it.sourceId == sourceId && it.songId == songId }
    }

    /** 把记录还原成可播的 Uri。返回 null 说明文件已经不在原处了。 */
    fun resolveUri(context: Context, d: DownloadedSong): Uri? {
        d.fileUri?.takeIf { it.isNotBlank() }?.let { raw ->
            val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
            return if (uri.scheme == "file") {
                val f = uri.path?.let { File(it) }
                if (f != null && f.exists()) uri else null
            } else if (PublicMusicStore.exists(context, uri)) uri else null
        }
        // 旧版私有目录
        d.legacyPath?.takeIf { it.isNotBlank() }?.let { rel ->
            val f = File(context.filesDir, rel)
            return if (f.exists()) Uri.fromFile(f) else null
        }
        // SAF：目录树还在就能重新找到文件
        val tree = d.treeUri?.takeIf { it.isNotBlank() } ?: return null
        val name = d.displayName?.takeIf { it.isNotBlank() } ?: return null
        val dir = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(tree)) }.getOrNull()
            ?: return null
        return runCatching { dir.findFile(name)?.uri }.getOrNull()
    }

    /** 「我的下载」页要能把已下载的歌直接拿去播，所以这里还原成一个 [Song]。 */
    fun toSong(context: Context, d: DownloadedSong): Song? {
        val uri = resolveUri(context, d) ?: return null
        val name = d.displayName
            ?: d.location?.substringAfterLast('/')
            ?: d.legacyPath?.substringAfterLast('/')
            ?: d.title
        return Song(
            id = d.id,
            uri = uri,
            title = d.title,
            artist = d.artist,
            album = d.album,
            durationMs = d.durationMs,
            fileName = name,
            kind = SourceKind.LOCAL,
            sizeBytes = d.sizeBytes
        )
    }
}
