package com.tom.musicplayer.model

/**
 * 一个在线音源。
 *
 * 「轻听」自己不抓音频：它把歌曲标识交给音源，音源返回一个直链，App 拿来播。
 * 音源有两种形态，由 [provider] 区分：
 *
 * - **内置源**（[provider] 非空）：搜索、榜单、歌词、封面全部由 App 内的引擎直接实现，
 *   不走 HTTP 协议，也不需要用户填服务地址。目前内置 `kw`（酷我）和 `wy`（网易云）。
 *   这类源唯一需要配置的是 [apiKey]——解析播放直链用的密钥。
 * - **自定义源**（[provider] 为空）：任意符合 `docs/ONLINE_SOURCE_PROTOCOL.md` 的第三方服务，
 *   由 [baseUrl] 指定地址。
 *
 * @property provider 内置引擎标识（`kw` / `wy`），空串表示自定义 HTTP 音源
 * @property apiKey   内置源专用的直链解析密钥；自定义源用 [token] / [user] + [password]
 * @property baseUrl  自定义音源的根地址；内置源留空
 * @property token    填了就带 `Authorization: Bearer <token>`
 * @property user / password 没填 token 时，用 Basic 鉴权
 */
data class OnlineSource(
    val id: String,
    val name: String,
    val baseUrl: String = "",
    val token: String = "",
    val user: String = "",
    val password: String = "",
    val enabled: Boolean = true,
    val provider: String = "",
    val apiKey: String = ""
) {
    val trimmedBase: String get() = baseUrl.trim().trimEnd('/')

    /** 内置源：能力由 App 自带引擎提供，不依赖任何 HTTP 服务。 */
    val isBuiltin: Boolean get() = provider.isNotBlank()

    /** 内置源没配密钥时能搜索但播不了，UI 要据此提示。 */
    val needsKey: Boolean get() = isBuiltin && apiKey.isBlank()

    fun url(path: String): String =
        trimmedBase + if (path.startsWith("/")) path else "/$path"

    /** 卡片上那行灰色小字：内置源说明来源，自定义源显示地址。 */
    val subtitle: String
        get() = if (isBuiltin) "内置引擎 · 无需服务地址" else baseUrl

    /** 从导入的 JSON 里解析。自定义源要求 baseUrl，内置源要求 provider。 */
    companion object {
        fun fromJson(o: org.json.JSONObject, fallbackId: String = ""): OnlineSource? {
            val base = o.optString("baseUrl").trim()
            val provider = o.optString("provider").trim()
            if (base.isBlank() && provider.isBlank()) return null
            if (provider.isNotBlank() && BuiltinProvider.of(provider) == null) return null
            return OnlineSource(
                id = o.optString("id").ifBlank { fallbackId.ifBlank { java.util.UUID.randomUUID().toString() } },
                name = o.optString("name").ifBlank {
                    BuiltinProvider.of(provider)?.displayName ?: base
                },
                baseUrl = base,
                token = o.optString("token", ""),
                user = o.optString("user", ""),
                password = o.optString("password", ""),
                enabled = o.optBoolean("enabled", true),
                provider = provider,
                apiKey = o.optString("apiKey", "")
            )
        }

        fun toJson(s: OnlineSource): org.json.JSONObject =
            org.json.JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("baseUrl", s.baseUrl)
                put("token", s.token)
                put("user", s.user)
                put("password", s.password)
                put("enabled", s.enabled)
                put("provider", s.provider)
                put("apiKey", s.apiKey)
            }
    }
}

/**
 * 内置引擎清单。
 *
 * 放这里而不是放引擎包里，是因为模型层要给 UI 提供名字和说明，
 * 不能让 UI 反过来依赖数据层（不然导入校验会绕成环）。
 */
enum class BuiltinProvider(
    val key: String,
    val displayName: String,
    val description: String,
    /** 该平台在聆澜密钥下开放的音质档位 */
    val qualities: List<String>
) {
    KW(
        key = "kw",
        displayName = "酷我音乐",
        description = "华语曲库全，单曲搜索、榜单、歌词、封面",
        qualities = listOf("standard", "high", "lossless")
    ),
    WY(
        key = "wy",
        displayName = "网易云音乐",
        description = "歌单与乐评生态，单曲搜索、榜单、歌词、封面",
        qualities = listOf("standard", "high", "lossless")
    );

    companion object {
        fun of(key: String): BuiltinProvider? = entries.firstOrNull { it.key == key }
    }
}

/** 音源返回的榜单 / 推荐歌单条目。 */
data class OnlinePlaylist(
    val id: String,
    val name: String,
    val cover: String? = null,
    val count: Int = 0,
    val desc: String? = null,
    /** 来自哪个音源，点进去才知道该问谁要歌。 */
    val sourceId: String = ""
)

/** 音源返回的一首歌。它还没有播放地址 —— 地址要等真的要播时才去解析。 */
data class OnlineSong(
    val songId: String,
    val name: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val cover: String? = null,
    val qualities: List<String> = emptyList(),
    val sourceId: String = ""
) {
    /** 全局唯一 id：不同音源的歌 id 可能撞车，所以要把音源 id 拼进去。 */
    val globalId: String get() = "online:$sourceId:$songId"

    /**
     * 占位 URI。真正的直链在播放时由数据源现解析（直链通常有时效）。
     *
     * 用 appendPath 而不是字符串拼接：音源给的 songId 里可能带 `/` 或空格，
     * 拼进 URI 会截断；appendPath 会做转义，取出时再按 pathSegments 还原。
     */
    val placeholderUri: android.net.Uri get() =
        android.net.Uri.parse("qingting://online").buildUpon()
            .appendPath(sourceId)
            .appendPath(songId)
            .build()

    fun toSong(): Song = Song(
        id = globalId,
        uri = placeholderUri,
        title = name,
        artist = artist,
        album = album,
        durationMs = durationMs,
        fileName = "$artist - $name",
        kind = SourceKind.ONLINE,
        onlineSourceId = sourceId,
        onlineSongId = songId,
        coverUrl = cover?.takeIf { it.isNotBlank() },
        sizeBytes = 0L
    )
}

/**
 * 反向转换：拿到一首正在播的在线歌，还想再下载它时用。
 *
 * 下载只需要「哪个音源 + 音源里的哪个 id」这两样，其余字段只是拿来写文件名和
 * 媒体库元数据的，[Song] 里都留着，所以不用再回音源查一遍。
 * 本地 / WebDAV 的歌没有音源归属，返回 null。
 */
fun Song.toOnlineSong(): OnlineSong? {
    if (!isOnline) return null
    val src = onlineSourceId?.takeIf { it.isNotBlank() } ?: return null
    val sid = onlineSongId?.takeIf { it.isNotBlank() } ?: return null
    return OnlineSong(
        songId = sid,
        name = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        cover = coverUrl,
        sourceId = src
    )
}
