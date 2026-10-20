package com.tom.musicplayer.data.lyrics

import android.content.Context
import android.net.Uri
import com.tom.musicplayer.model.LyricCandidate
import com.tom.musicplayer.model.LyricLine
import com.tom.musicplayer.model.LyricSource
import com.tom.musicplayer.model.Lyrics
import com.tom.musicplayer.model.Song
import com.tom.musicplayer.data.online.OnlineApi
import com.tom.musicplayer.data.online.OnlineSourceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 歌词仓库：统一决定「这一首歌的歌词从哪来」。
 *
 * 优先级：歌曲自带的 .lrc 文件 → 在线歌曲问音源要 → 本地已下载的缓存 → 在线搜索（LRCLIB）。
 * 下载到的歌词会落盘，下次打开不再联网；用户对不上的时候还可以手动挑条目 + 微调偏移。
 */
object LyricsRepository {

    private const val DIR = "lyrics"
    private const val OFFSETS = "offsets.json"

    private fun dir(context: Context) = File(context.filesDir, DIR).apply { mkdirs() }

    private fun key(songId: String): String = try {
        MessageDigest.getInstance("MD5").digest(songId.toByteArray())
            .joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        songId.hashCode().toString()
    }

    private fun cacheFile(context: Context, songId: String) = File(dir(context), key(songId) + ".lrc")

    // ------------------------------------------------------------ 读取

    /** 按优先级取得歌词；没有任何来源时返回空列表 + [LyricSource.NONE]。 */
    suspend fun load(context: Context, song: Song): Lyrics = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val offset = readOffset(app, song.id)

        // 1) 歌曲自带的同名 .lrc（扫描时就顺手记下来了）
        song.lyricUri?.let { uri ->
            val text = readText(app, uri)
            val lines = text?.let { LrcParser.parse(it) }?.takeIf { it.isNotEmpty() }
            if (lines != null) return@withContext Lyrics(lines, LyricSource.EMBEDDED_FILE, offset)
        }

        // 2) 在线歌曲先问音源要：音源自带歌词时不该再去公网瞎搜一遍
        if (song.isOnline && song.onlineSourceId != null && song.onlineSongId != null) {
            val source = OnlineSourceStore.byId(app, song.onlineSourceId)
            if (source != null) {
                val text = OnlineApi.lyric(source, song.onlineSongId)
                val lines = text?.let { LrcParser.parse(it) }?.takeIf { it.isNotEmpty() }
                if (lines != null) {
                    // 顺手落一份缓存，下次离线也能看
                    runCatching { cacheFile(app, song.id).writeText(text!!) }
                    return@withContext Lyrics(lines, LyricSource.DOWNLOADED, offset)
                }
            }
        }

        // 3) 之前下载过的
        val cached = runCatching { cacheFile(app, song.id).readText() }.getOrNull()
        val cachedLines = cached?.let { LrcParser.parse(it) }?.takeIf { it.isNotEmpty() }
        if (cachedLines != null) {
            return@withContext Lyrics(cachedLines, LyricSource.DOWNLOADED, offset)
        }

        // 3) 在线搜索（标题 + 演唱者 + 时长，命中率最高）
        val durationSec = song.durationMs.takeIf { it > 0 }?.let { it / 1000 }
        // 先按「专辑 + 时长」严格匹配；库里记录的时长常常是 0 或与原曲有出入，
        // 严格匹配落空时一定要退回只用「标题 + 演唱者」再试一次，否则命中率会明显下降。
        val result = LrcLibClient.exact(artistOf(song), song.title, albumOf(song), durationSec).getOrNull()
            ?: LrcLibClient.exact(artistOf(song), song.title, albumOf(song), null).getOrNull()
            ?: LrcLibClient.exact(artistOf(song), song.title, null, null).getOrNull()

        val downloaded = extractLines(result)
        if (downloaded != null) {
            runCatching {
                cacheFile(app, song.id).writeText(
                    result?.syncedLyrics ?: result?.plainLyrics.orEmpty()
                )
            }
            return@withContext Lyrics(downloaded, LyricSource.DOWNLOADED, offset)
        }
        Lyrics(emptyList(), LyricSource.NONE, offset)
    }

    // ------------------------------------------------------------ 手动选词

    suspend fun search(context: Context, query: String): Result<List<LyricCandidate>> =
        withContext(Dispatchers.IO) {
            LrcLibClient.search(query).map { list ->
                list.map { raw ->
                    LyricCandidate(
                        id = raw.id,
                        trackName = raw.trackName,
                        artistName = raw.artistName,
                        albumName = raw.albumName,
                        durationSec = raw.durationSec,
                        plainLyrics = raw.plainLyrics,
                        syncedLyrics = raw.syncedLyrics
                    )
                }
            }
        }

    /** 用户在候选里手动挑了一条：直接落盘并返回，下次打开用它。 */
    suspend fun applyCandidate(context: Context, song: Song, candidate: LyricCandidate): Lyrics =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val raw = candidate.syncedLyrics?.takeIf { it.isNotBlank() } ?: candidate.plainLyrics
            runCatching { cacheFile(app, song.id).writeText(raw) }
            val lines = LrcParser.parse(raw).takeIf { it.isNotEmpty() }
                ?: LrcParser.plainLines(raw)
            Lyrics(lines, LyricSource.DOWNLOADED, readOffset(app, song.id))
        }

    /** 丢弃这一首的缓存歌词，迫使下次重新搜索（换 API 源或改曲名时用得上）。 */
    suspend fun clear(context: Context, songId: String) = withContext(Dispatchers.IO) {
        runCatching { cacheFile(context.applicationContext, songId).delete() }
    }

    // ------------------------------------------------------------ 时间轴微调

    suspend fun saveOffset(context: Context, songId: String, offsetMs: Long) =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val file = File(dir(app), OFFSETS)
            val map = readOffsets(file)
            map.put(songId, offsetMs)
            runCatching { file.writeText(JSONObject(map as Map<*, *>).toString()) }
        }

    private fun readOffset(context: Context, songId: String): Long =
        readOffsets(File(dir(context), OFFSETS)).optLong(songId, 0L)

    private fun readOffsets(file: File): JSONObject =
        runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }

    // ------------------------------------------------------------ 内部

    private fun artistOf(song: Song): String =
        song.artist.takeIf { it.isNotBlank() && it != "未知艺术家" } ?: ""

    private fun albumOf(song: Song): String? = song.album.takeIf { it.isNotBlank() }

    private fun extractLines(raw: LrcLibClient.RawLyric?): List<LyricLine>? {
        if (raw == null) return null
        val synced = raw.syncedLyrics?.let { LrcParser.parse(it) }?.takeIf { it.isNotEmpty() }
        if (synced != null) return synced
        val plain = raw.plainLyrics.takeIf { it.isNotBlank() }
            ?.let { LrcParser.plainLines(it) }
            ?.takeIf { it.isNotEmpty() }
        return plain
    }

    /** 既支持本地 content:// 也支持远端 http(s) 的 .lrc 读取。 */
    private fun readText(context: Context, uri: Uri): String? {
        val scheme = uri.scheme
        return runCatching {
            when (scheme) {
                "http", "https" -> {
                    val request = Request.Builder().url(uri.toString()).get().build()
                    LyricsHttp.client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) response.body?.string() else null
                    }
                }
                else -> context.contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader().readText()
                }
            }
        }.getOrNull()
    }
}

private object LyricsHttp {
    val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()
}
