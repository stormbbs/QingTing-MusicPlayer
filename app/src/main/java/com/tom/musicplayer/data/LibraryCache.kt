package com.tom.musicplayer.data

import android.content.Context
import android.net.Uri
import com.tom.musicplayer.model.Song
import com.tom.musicplayer.model.SourceKind
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 歌曲列表本地缓存。
 *
 * 目的：某些 ROM / 第三方文件管理器下，SAF 的持久化授权并不总是能在下一次启动时
 * 立刻生效（甚至 prefs 被清理）。若每次启动都只依赖实时扫描，用户就会看到
 * 「还没有音乐」并被迫重新选文件夹。这里把上一次扫描成功的列表落盘，
 * 启动时先用它把界面顶起来，再后台重扫刷新。
 */
object LibraryCache {

    private const val FILE = "library.json"

    private fun file(context: Context) = File(context.filesDir, FILE)

    fun save(context: Context, songs: List<Song>) {
        val array = JSONArray()
        songs.forEach { song ->
            array.put(
                JSONObject().apply {
                    put("id", song.id)
                    put("uri", song.uri.toString())
                    put("title", song.title)
                    put("artist", song.artist)
                    put("album", song.album)
                    put("duration", song.durationMs)
                    put("file", song.fileName)
                    put("kind", song.kind.value)
                    song.remotePath?.let { put("remote", it) }
                    song.lyricUri?.let { put("lyric", it.toString()) }
                    put("size", song.sizeBytes)
                    // 在线歌也要能缓存：队列里混着在线歌时（例如从榜单起播），
                    // 少了这几项重启后会被当成「没有地址的本地歌」
                    put("sourceId", song.onlineSourceId ?: "")
                    put("songId", song.onlineSongId ?: "")
                    put("cover", song.coverUrl ?: "")
                }
            )
        }
        runCatching {
            val target = file(context)
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeText(array.toString())
            if (tmp.renameTo(target).not()) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    fun load(context: Context): List<Song> {
        val text = runCatching { file(context).readText() }.getOrNull() ?: return emptyList()
        if (text.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(text)
            val out = ArrayList<Song>(array.length())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val rawUri = o.optString("uri")
                if (rawUri.isBlank()) continue
                out.add(
                    Song(
                        id = o.optString("id", rawUri),
                        uri = Uri.parse(rawUri),
                        title = o.optString("title").ifBlank { o.optString("file") },
                        artist = o.optString("artist").ifBlank { "未知艺术家" },
                        album = o.optString("album", ""),
                        durationMs = o.optLong("duration", 0L),
                        fileName = o.optString("file", ""),
                        kind = SourceKind.parse(o.optString("kind")),
                        remotePath = o.optString("remote").takeIf { it.isNotBlank() },
                        sizeBytes = o.optLong("size", 0L),
                        lyricUri = o.optString("lyric").takeIf { it.isNotBlank() }
                            ?.let { runCatching { Uri.parse(it) }.getOrNull() },
                        onlineSourceId = o.optString("sourceId").takeIf { it.isNotBlank() },
                        onlineSongId = o.optString("songId").takeIf { it.isNotBlank() },
                        coverUrl = o.optString("cover").takeIf { it.isNotBlank() }
                    )
                )
            }
            out
        }.getOrDefault(emptyList())
    }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
