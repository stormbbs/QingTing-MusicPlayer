package com.tom.musicplayer.data

import android.content.Context
import android.net.Uri
import com.tom.musicplayer.data.online.FailedDownloadStore
import com.tom.musicplayer.data.online.LocalPlaylist
import com.tom.musicplayer.data.online.LocalPlaylistStore
import com.tom.musicplayer.data.online.OnlineSourceStore
import com.tom.musicplayer.model.OnlineSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 收藏、歌单、音源配置的导出与导入。
 *
 * 这几样东西都只存在 App 私有目录里（`files/favorites.json` 等），换手机、
 * 卸载重装、或者清了 App 数据，全部一起消失 —— 而这些恰恰是用户自己攒出来的、
 * 重新找不回来的东西（音源密钥、自建歌单里的曲目顺序）。
 *
 * 所以导出一份 .json 到用户选的文件夹，导入时按「合并」处理：
 * - 收藏、歌单：按 id 去重，只补不删，导入不会把现有的东西清掉；
 * - 音源：同 id 覆盖（配置改了要以导入的为准），新 id 直接追加。
 *
 * 不导出**下载记录**和曲库缓存：前者指向的是这台手机上的具体文件路径，
 * 换个设备就是一张死账；后者每次启动都会重新扫。
 */
object BackupStore {

    /** 备份格式版本。以后加字段时靠它决定怎么兼容老文件。 */
    private const val VERSION = 1

    /** 导出的歌曲：优先存「音源身份」，本地文件另记文件名和 Uri 作兜底。 */
    private fun favoriteJson(f: FavoriteSong): JSONObject = JSONObject().apply {
        put("id", f.id)
        put("title", f.title)
        put("artist", f.artist)
        put("album", f.album)
        put("durationMs", f.durationMs)
        put("kind", f.kind.value)
        put("uri", f.uri ?: "")
        put("file", f.fileName)
        put("remote", f.remotePath ?: "")
        put("sourceId", f.onlineSourceId ?: "")
        put("songId", f.onlineSongId ?: "")
        put("cover", f.coverUrl ?: "")
        put("addedAt", f.addedAt)
    }

    private fun favoriteOf(o: JSONObject): FavoriteSong? {
        // 新备份的文件名带一个 "favorites" 前缀层级；老备份直接把字段写在对象上
        val id = o.optString("id")
        if (id.isBlank()) return null
        return FavoriteSong(
            id = id,
            title = o.optString("title"),
            artist = o.optString("artist"),
            album = o.optString("album"),
            durationMs = o.optLong("durationMs", 0L),
            kind = com.tom.musicplayer.model.SourceKind.parse(o.optString("kind")),
            uri = o.optString("uri").takeIf { it.isNotBlank() },
            fileName = o.optString("file"),
            remotePath = o.optString("remote").takeIf { it.isNotBlank() },
            onlineSourceId = o.optString("sourceId").takeIf { it.isNotBlank() },
            onlineSongId = o.optString("songId").takeIf { it.isNotBlank() },
            coverUrl = o.optString("cover").takeIf { it.isNotBlank() },
            addedAt = o.optLong("addedAt", System.currentTimeMillis())
        )
    }

    /** 导出用的默认文件名：带上日期，连导几次也认得出哪份是新的。 */
    fun suggestedFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        return "轻听备份-$stamp.json"
    }

    /**
     * 生成备份内容。
     *
     * @param withPlaylists 带上自建歌单
     * @param withSources 带上音源配置（**含密钥**，界面上必须先跟用户说清楚）
     */
    fun build(
        context: Context,
        withPlaylists: Boolean = true,
        withSources: Boolean = true
    ): String {
        val favorites = FavoritesStore.entries(context)
        val playlists = if (withPlaylists) LocalPlaylistStore.load(context) else emptyList()
        val sources = if (withSources) OnlineSourceStore.load(context) else emptyList()

        val root = JSONObject().apply {
            put("app", "qingting")
            put("version", VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("favorites", JSONArray().also { arr -> favorites.forEach { arr.put(favoriteJson(it)) } })
            put("playlists", JSONArray().also { arr ->
                playlists.forEach { p ->
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
                    arr.put(JSONObject().apply {
                        put("id", p.id)
                        put("name", p.name)
                        put("createdAt", p.createdAt)
                        put("songs", songs)
                    })
                }
            })
            put("sources", JSONArray().also { arr ->
                sources.forEach { arr.put(com.tom.musicplayer.model.OnlineSource.toJson(it)) }
            })
        }
        return root.toString(2)
    }

    /** 导入结果，用来给用户一句实话：到底并进去多少。 */
    data class ImportResult(
        val favoritesAdded: Int = 0,
        val playlistsAdded: Int = 0,
        val playlistsMerged: Int = 0,
        val sourcesAdded: Int = 0,
        val error: String? = null
    ) {
        val ok: Boolean get() = error == null
    }

    /**
     * 把一段备份文本并进当前数据。
     *
     * 认不出来的文件（不是本 App 导出的、或者格式坏了）返回 [ImportResult.error]，
     * 绝不半途改掉一半数据：先整个解析完，解析成功才落盘。
     */
    fun merge(context: Context, text: String): ImportResult {
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: return ImportResult(error = "这不是一个有效的备份文件")
        if (root.optString("app") != "qingting") {
            return ImportResult(error = "这份文件不是「轻听」导出的备份")
        }
        val version = root.optInt("version", 1)
        if (version > VERSION) {
            return ImportResult(error = "备份来自更新的版本，请先升级 App 再导入")
        }

        // ---- 收藏 ----
        var addedFav = 0
        root.optJSONArray("favorites")?.let { arr ->
            val existing = FavoritesStore.entries(context).mapTo(HashSet()) { it.id }
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val entry = favoriteOf(o) ?: continue
                if (entry.id in existing) continue
                FavoritesStore.putEntry(context, entry)
                existing.add(entry.id)
                addedFav++
            }
        }

        // ---- 歌单 ----
        var addedList = 0
        var mergedList = 0
        root.optJSONArray("playlists")?.let { arr ->
            val current = LocalPlaylistStore.load(context)
            val byId = current.associateBy { it.id }.toMutableMap()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isBlank()) continue
                val songs = ArrayList<OnlineSong>()
                o.optJSONArray("songs")?.let { sArr ->
                    for (j in 0 until sArr.length()) {
                        val s = sArr.optJSONObject(j) ?: continue
                        val songId = s.optString("songId")
                        if (songId.isBlank()) continue
                        songs.add(
                            OnlineSong(
                                songId = songId,
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
                val old = byId[id]
                if (old == null) {
                    byId[id] = LocalPlaylist(
                        id = id,
                        name = o.optString("name").ifBlank { "我的歌单" },
                        songs = songs,
                        createdAt = o.optLong("createdAt", System.currentTimeMillis())
                    )
                    addedList++
                } else {
                    // 同名歌单：按「音源 + 歌曲」合并曲目，顺序保留现有的在前
                    val seen = old.songs.mapTo(HashSet()) { "${it.sourceId}:${it.songId}" }
                    val extra = songs.filter { seen.add("${it.sourceId}:${it.songId}") }
                    byId[id] = old.copy(songs = old.songs + extra)
                    mergedList++
                }
            }
            LocalPlaylistStore.saveAll(context, byId.values.toList())
        }

        // ---- 音源 ----
        var addedSrc = 0
        root.optJSONArray("sources")?.let { arr ->
            val incoming = ArrayList<com.tom.musicplayer.model.OnlineSource>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                com.tom.musicplayer.model.OnlineSource.fromJson(o)?.let { incoming.add(it) }
            }
            if (incoming.isNotEmpty()) {
                addedSrc = OnlineSourceStore.importSources(context, incoming)
            }
        }

        return ImportResult(addedFav, addedList, mergedList, addedSrc)
    }

    /** 写到用户选的文件里。返回 null 表示成功，否则是给用户看的原因。 */
    suspend fun writeTo(context: Context, target: Uri, content: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openOutputStream(target, "wt")?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("打不开目标文件")
            }.exceptionOrNull()?.let { "导出失败：${it.message ?: "未知原因"}" }
        }

    /** 读用户选的文件。 */
    suspend fun readFrom(context: Context, uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                // 备份是纯文本，几 MB 顶天了；限制一下免得选了个视频文件把内存吃穿
                val bytes = input.readNBytes(8 * 1024 * 1024)
                String(bytes, Charsets.UTF_8)
            } ?: throw IllegalStateException("打不开这个文件")
        }
    }

    /** 「清空」用：把这三样彻底删掉。给「换了手机、不想留旧数据」的情况用。 */
    fun clearAll(context: Context) {
        FavoritesStore.clear(context)
        LocalPlaylistStore.saveAll(context, emptyList())
        FailedDownloadStore.clear(context)
    }
}
