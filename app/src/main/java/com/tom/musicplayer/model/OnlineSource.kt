package com.tom.musicplayer.model

/**
 * 一个在线音源。
 *
 * 「轻听」自己没有抓音频的能力：它把歌曲标识交给音源，音源返回一个直链，App 拿来播。
 * 所以音源是什么、数据从哪来，完全由填进来的 [baseUrl] 决定 —— 可以是你自己的服务器，
 * 也可以是任何符合 `docs/ONLINE_SOURCE_PROTOCOL.md` 的第三方服务。
 *
 * @property baseUrl 音源根地址，末尾可带可不带斜杠，例如 https://music.example.com/api
 * @property token   填了就带 `Authorization: Bearer <token>`
 * @property user / password 没填 token 时，用 Basic 鉴权
 */
data class OnlineSource(
    val id: String,
    val name: String,
    val baseUrl: String,
    val token: String = "",
    val user: String = "",
    val password: String = "",
    val enabled: Boolean = true
) {
    val trimmedBase: String get() = baseUrl.trim().trimEnd('/')

    fun url(path: String): String =
        trimmedBase + if (path.startsWith("/")) path else "/$path"

    /** 从导入的 JSON 里解析。baseUrl 是唯一必填项。 */
    companion object {
        fun fromJson(o: org.json.JSONObject, fallbackId: String = ""): OnlineSource? {
            val base = o.optString("baseUrl").trim()
            if (base.isBlank()) return null
            return OnlineSource(
                id = o.optString("id").ifBlank { fallbackId.ifBlank { java.util.UUID.randomUUID().toString() } },
                name = o.optString("name").ifBlank { base },
                baseUrl = base,
                token = o.optString("token", ""),
                user = o.optString("user", ""),
                password = o.optString("password", ""),
                enabled = o.optBoolean("enabled", true)
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
            }
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
