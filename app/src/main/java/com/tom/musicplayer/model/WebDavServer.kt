package com.tom.musicplayer.model

/**
 * 一台 WebDAV 服务器。
 *
 * @property baseUrl  服务根地址，末尾不带斜杠，例如 https://music.example.com/webdav
 * @property rootPath 浏览起点（相对 baseUrl 的路径），"/" 表示根目录
 */
data class WebDavServer(
    val id: String,
    val name: String,
    val baseUrl: String,
    val user: String,
    val password: String,
    val rootPath: String = "/"
) {
    val needsAuth: Boolean get() = user.isNotBlank() || password.isNotBlank()

    /** 把服务器上相对 [rawPath]（以 / 开头）拼成可直接请求的 URL，逐段编码避免空格/中文出错。 */
    fun urlFor(rawPath: String): String {
        val normalized = if (rawPath.startsWith("/")) rawPath else "/$rawPath"
        val encoded = normalized.trimEnd('/').split('/')
            .joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return baseUrl.trimEnd('/') + encoded
    }

    /** 把 baseUrl 里的路径前缀摘出来，用于把服务端返回的 href 还原成相对路径。 */
    fun urlPathPrefix(): String {
        val idx = baseUrl.indexOf("//")
        if (idx < 0) return baseUrl.trim().removeSuffix("/")
        val start = baseUrl.indexOf('/', idx + 2)
        return if (start < 0) "" else baseUrl.substring(start).trimEnd('/')
    }
}
