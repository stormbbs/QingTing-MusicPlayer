package com.tom.musicplayer.data.webdav

import android.net.Uri
import com.tom.musicplayer.model.SourceKind
import com.tom.musicplayer.model.Song
import com.tom.musicplayer.model.WebDavServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 把 WebDAV 远端目录树转成统一的 [Song] 列表。 */
object WebDavScanner {

    private val SUPPORTED_EXT = setOf(
        "mp3", "wav", "flac", "m4a", "aac", "ogg", "opus", "wma", "mka"
    )
    private const val MAX_DEPTH = 6

    suspend fun scanTree(
        server: WebDavServer,
        startPath: String = server.rootPath,
        maxDepth: Int = MAX_DEPTH
    ): List<Song> = withContext(Dispatchers.IO) {
        val out = ArrayList<Song>()
        val seen = HashSet<String>()
        walk(server, startPath, 0, maxDepth, out, seen)
        out.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }

    /** 只列出单层的音频文件，用于「进目录就播这一层」的场景。 */
    suspend fun listSongs(server: WebDavServer, path: String): List<Song> =
        withContext(Dispatchers.IO) {
            val entries = WebDavClient.list(server, path).getOrDefault(emptyList())
            val lyrics = HashMap<String, String>()
            entries.filter { !it.isDirectory && it.name.endsWith(".lrc", true) }
                .forEach { lyrics[it.name.substringBeforeLast('.').lowercase()] = it.path }
            entries.filter { !it.isDirectory && isAudio(it.name) }
                .map { it.toSong(server, lyrics[it.name.substringBeforeLast('.').lowercase()]) }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        }

    private suspend fun walk(
        server: WebDavServer,
        path: String,
        depth: Int,
        maxDepth: Int,
        out: MutableList<Song>,
        seen: MutableSet<String>
    ) {
        if (depth > maxDepth) return
        val entries = WebDavClient.list(server, path).getOrNull() ?: return
        // 同名 .lrc 优先在这一层找出来，之后音频条目就能直接挂上歌词地址
        val lyrics = HashMap<String, String>()
        entries.filter { !it.isDirectory && it.name.endsWith(".lrc", true) }
            .forEach { lyrics[it.name.substringBeforeLast('.').lowercase()] = it.path }
        for (entry in entries) {
            if (entry.isDirectory) {
                walk(server, entry.path, depth + 1, maxDepth, out, seen)
            } else if (isAudio(entry.name) && seen.add(entry.path)) {
                out.add(entry.toSong(server, lyrics[entry.name.substringBeforeLast('.').lowercase()]))
            }
        }
    }

    private fun isAudio(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in SUPPORTED_EXT

    /**
     * 远端文件只有文件名可依赖，因此这里做一次常见命名规范的拆解：
     * 「歌手 - 曲名.mp3」「歌手—曲名.mp3」都能还原出演唱者；
     * 目录层级也可以顺手当作专辑名。
     */
    private fun WebDavEntry.toSong(server: WebDavServer, lyricPath: String? = null): Song {
        val raw = name.substringBeforeLast('.', name)
        val (artist, title) = splitArtistTitle(raw)
        val albumGuess = path.trim('/').split('/')
            .dropLast(1).lastOrNull()?.takeIf { it.isNotBlank() && it != server.rootPath.trim('/') }
        val id = "webdav:${server.id}:$path"
        return Song(
            id = id,
            uri = Uri.parse(server.urlFor(path)),
            title = title,
            artist = artist,
            album = albumGuess ?: "",
            // 远端不提前探测时长：那是真的一次 HTTP 请求，列表入几百首会拖垮扫描
            durationMs = 0L,
            fileName = name,
            kind = SourceKind.WEBDAV,
            remotePath = path,
            sizeBytes = sizeBytes,
            lyricUri = lyricPath?.let { Uri.parse(server.urlFor(it)) }
        )
    }

    private fun splitArtistTitle(raw: String): Pair<String, String> {
        val separators = listOf(" - ", " — ", " – ", "-", "—")
        for (sep in separators) {
            val idx = raw.indexOf(sep)
            if (idx > 0) {
                val left = raw.substring(0, idx).trim()
                val right = raw.substring(idx + sep.length).trim()
                if (left.isNotBlank() && right.isNotBlank()) return left to right
            }
        }
        return "未知艺术家" to raw.ifBlank { "未知曲目" }
    }

    /** 递归扫描随时可能在某层遇到权限或超时，界面据此给出可读提示而不是整个失败。 */
    suspend fun safeScanTree(server: WebDavServer, startPath: String): Result<List<Song>> =
        runCatching { scanTree(server, startPath) }
}
