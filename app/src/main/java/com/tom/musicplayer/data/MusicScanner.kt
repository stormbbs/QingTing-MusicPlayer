package com.tom.musicplayer.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.tom.musicplayer.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MusicScanner {

    private val SUPPORTED_EXT = setOf("mp3", "wav", "flac", "m4a", "aac", "ogg", "opus", "wma")
    private const val MAX_DEPTH = 6

    /**
     * 扫描用户选择的文件夹（含子文件夹）里的音频文件。
     *
     * [known] 是上一次扫描的结果。命中其中的文件直接复用已解析好的元数据，
     * 不再为它打开一次 MediaMetadataRetriever —— 大曲库（几百上千首）每次启动都
     * 全量解一遍标签是相当可观的 CPU / 内存开销，低端机上甚至会把播放进程挤掉。
     * 目录仍然会完整遍历，所以新加的歌照样能发现；只有新歌才真正解析。
     */
    suspend fun scan(
        context: Context,
        treeUri: Uri,
        known: List<Song> = emptyList()
    ): List<Song> = withContext(Dispatchers.IO) {
        val tree = runCatching { DocumentFile.fromTreeUri(context, treeUri) }.getOrNull()
            ?: return@withContext emptyList()
        val knownByUri = HashMap<String, Song>(known.size)
        known.forEach { knownByUri[it.uri.toString()] = it }
        val result = ArrayList<Song>()
        walk(context, tree, 0, result, knownByUri)
        result.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }

    /** 文件夹显示名，用于在界面上提示当前扫描位置。 */
    fun folderName(treeUri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return "已选文件夹"
        return docId.substringAfterLast(':', docId).ifBlank { "已选文件夹" }
    }

    private fun walk(
        context: Context,
        dir: DocumentFile,
        depth: Int,
        out: MutableList<Song>,
        known: Map<String, Song>
    ) {
        if (depth > MAX_DEPTH) return
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return
        // 先把这一层的 .lrc 收集起来：顺手做一遍，免得为了找歌词再把目录列一次
        val lyrics = HashMap<String, Uri>()
        val audios = ArrayList<androidx.documentfile.provider.DocumentFile>()
        for (file in files) {
            val name = file.name ?: continue
            if (file.isDirectory) continue
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext == "lrc") {
                lyrics[name.substringBeforeLast('.').lowercase()] = file.uri
            } else if (ext in SUPPORTED_EXT) {
                audios.add(file)
            }
        }
        audios.forEach { file ->
            val name = file.name ?: return@forEach
            val lyric = lyrics[name.substringBeforeLast('.').lowercase()]
            val previous = known[file.uri.toString()]
            // 上次解析成功的（duration > 0 说明当时读到了标签）就直接用，
            // 只有新歌和上次没解析出来的才真正去打开文件
            if (previous != null && previous.durationMs > 0L) {
                out.add(if (lyric != null && lyric != previous.lyricUri) {
                    previous.copy(lyricUri = lyric)
                } else previous)
                return@forEach
            }
            out.add(readMetadata(context, file.uri, name, lyric))
        }
        // 子目录放到最后递归，音乐比目录结构更值得先出现在列表里
        files.filter { it.isDirectory }.forEach { walk(context, it, depth + 1, out, known) }
    }

    private fun readMetadata(
        context: Context,
        uri: Uri,
        fileName: String,
        lyricUri: Uri? = null
    ): Song {
        var title = fileName.substringBeforeLast('.', fileName)
        var artist = "未知艺术家"
        var album = ""
        var duration = 0L

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }?.let { title = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }?.let { artist = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() }?.let { album = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.let { duration = it }
        } catch (_: Exception) {
            // 元数据读取失败时保留文件名作为标题
        } finally {
            runCatching { retriever.release() }
        }

        return Song(
            id = uri.toString(),
            uri = uri,
            title = title,
            artist = artist,
            album = album,
            durationMs = duration,
            fileName = fileName,
            lyricUri = lyricUri
        )
    }
}
