package com.tom.musicplayer.data.webdav

import android.util.Xml
import com.tom.musicplayer.model.WebDavServer
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class WebDavEntry(
    val name: String,
    /** 相对服务器根的路径，始终以 / 开头，不带结尾斜杠。 */
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val contentType: String?
)

/**
 * 极简 WebDAV 客户端：只做「列目录」和「连通性测试」两件事。
 *
 * 自己实现的原因很简单：WebDAV 的本质就是带认证的 HTTP + PROPFIND 扩展，
 * 我们只需要发一个 XML body 再解析 207 Multi-Status 响应即可，
 * 引入 Sardine 这类完整库会平白多出上百 KB 依赖。
 */
object WebDavClient {

    private const val PROPFIND_BODY =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<d:propfind xmlns:d=\"DAV:\">\n" +
            "  <d:prop>\n" +
            "    <d:displayname/>\n" +
            "    <d:getcontentlength/>\n" +
            "    <d:getcontenttype/>\n" +
            "    <d:getlastmodified/>\n" +
            "    <d:resourcetype/>\n" +
            "  </d:prop>\n" +
            "</d:propfind>"

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 测试能否连通；失败时返回可读的错误原因给界面展示。 */
    suspend fun test(server: WebDavServer): Result<String> =
        withContext(Dispatchers.IO) { runTest(server) }

    private fun runTest(server: WebDavServer): Result<String> {
        return runCatching {
            val response = propfind(server, server.rootPath)
            try {
                val code = response.code
                if (code == 401 || code == 403) {
                    Result.failure<String>(IllegalStateException("认证失败 ($code)，请检查用户名和密码"))
                } else if (code == 404) {
                    Result.failure<String>(IllegalStateException("路径不存在 (404)，请检查服务器根目录"))
                } else if (code !in 200..299 && code != 207) {
                    Result.failure<String>(IllegalStateException("服务器返回 $code"))
                } else {
                    Result.success("连接成功")
                }
            } finally {
                runCatching { response.close() }
            }
        }.getOrElse { e ->
            Result.failure(IllegalStateException(friendlyError(e), e))
        }
    }

    /** 列出 [path] 下的直接子项（不含自身）。 */
    suspend fun list(server: WebDavServer, path: String): Result<List<WebDavEntry>> =
        withContext(Dispatchers.IO) { runList(server, path) }

    private fun runList(server: WebDavServer, path: String): Result<List<WebDavEntry>> =
        runCatching {
            val response = propfind(server, path)
            response.use {
                val code = response.code
                if (code == 401 || code == 403) {
                    throw IllegalStateException("认证失败 ($code)")
                }
                if (code == 404) {
                    throw IllegalStateException("目录不存在 (404)")
                }
                if (code != 207 && code !in 200..299) {
                    throw IllegalStateException("服务器返回 $code")
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) emptyList()
                else parseMultistatus(body, server, path)
            }
        }.recoverCatching { e ->
            throw IllegalStateException(friendlyError(e), e)
        }

    private fun propfind(server: WebDavServer, path: String): okhttp3.Response {
        val normalized = if (path == "/") "/" else "/${path.trim('/')}/"
        val body = PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType())
        val builder = Request.Builder()
            .url(server.urlFor(normalized))
            .method("PROPFIND", body)
            .header("Depth", "1")
        if (server.needsAuth) {
            builder.header("Authorization", Credentials.basic(server.user, server.password))
        }
        return client.newCall(builder.build()).execute()
    }

    /**
     * 解析 207 Multi-Status 响应。
     *
     * 坑：服务端返回的 href 可能是相对路径、绝对路径，甚至已经是 URL 编码过的，
     * 而且 baseUrl 里可能带着子路径前缀。所以这里是「两种前缀都试一遍」的兼容写法，
     * 实在对不上就退回「按多级路径的最后一段」来推断。
     */
    private fun parseMultistatus(
        xml: String,
        server: WebDavServer,
        parentPath: String
    ): List<WebDavEntry> {
        val rawPrefix = server.urlPathPrefix()
        val decodedPrefix = runCatching { URLDecoder.decode(rawPrefix, "UTF-8") }
            .getOrDefault(rawPrefix)
        val parent = if (parentPath == "/") "/" else "/${parentPath.trim('/')}"

        val out = ArrayList<WebDavEntry>()
        val factory = XmlPullParserFactoryHolder.factory
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var hrefRaw = ""
        var displayName: String? = null
        var length: Long = 0L
        var contentType: String? = null
        var lastModified: Long = 0L
        var isDirectory = false
        var currentTag: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val tag = localName(parser.name)
                    if (tag == "response") {
                        hrefRaw = ""; displayName = null; length = 0L
                        contentType = null; lastModified = 0L; isDirectory = false
                    }
                    if (tag == "collection") isDirectory = true
                    if (tag == "href" || tag == "displayname" || tag == "getcontentlength" ||
                        tag == "getcontenttype" || tag == "getlastmodified"
                    ) {
                        currentTag = tag
                    }
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    when (currentTag) {
                        "href" -> hrefRaw = text
                        "displayname" -> displayName = text
                        "getcontentlength" -> length = text.toLongOrNull() ?: 0L
                        "getcontenttype" -> contentType = text.ifBlank { null }
                        "getlastmodified" -> lastModified = runCatching {
                            HttpDate.parse(text)
                        }.getOrDefault(0L)
                    }
                }

                XmlPullParser.END_TAG -> {
                    val tag = localName(parser.name)
                    if (tag == "response") {
                        val rel = relative(hrefRaw, rawPrefix, decodedPrefix)
                        val isSelf = rel.trimEnd('/') == parent.trimEnd('/')
                        if (!isSelf && rel.isNotBlank()) {
                            val name = displayName?.takeIf { it.isNotBlank() }
                                ?: rel.trimEnd('/').substringAfterLast('/')
                            if (name.isNotBlank()) {
                                val dir = isDirectory ||
                                    (contentType?.contains("directory", true) == true) ||
                                    hrefRaw.endsWith("/")
                                out.add(
                                    WebDavEntry(
                                        name = name,
                                        path = rel.trimEnd('/').ifBlank { "/" },
                                        isDirectory = dir,
                                        sizeBytes = length,
                                        modifiedMs = lastModified,
                                        contentType = contentType
                                    )
                                )
                            }
                        }
                    }
                    currentTag = null
                }
            }
            event = parser.next()
        }

        return out
            .distinctBy { it.path }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    private fun relative(hrefRaw: String, rawPrefix: String, decodedPrefix: String): String {
        var raw = hrefRaw.trim()
        if (raw.contains("://")) {
            val hostEnd = raw.indexOf('/', raw.indexOf("://") + 3)
            raw = if (hostEnd >= 0) raw.substring(hostEnd) else "/"
        }
        val rawPath = raw.substringBefore('#').substringBefore('?')

        val stripped = when {
            decodedPrefix.isNotBlank() && URLDecoder.decode(rawPath, "UTF-8")
                .startsWith(decodedPrefix) -> {
                URLDecoder.decode(rawPath, "UTF-8").removePrefix(decodedPrefix)
            }
            rawPrefix.isNotBlank() && rawPath.startsWith(rawPrefix) -> {
                URLDecoder.decode(rawPath.removePrefix(rawPrefix), "UTF-8")
            }
            else -> URLDecoder.decode(rawPath, "UTF-8")
        }
        return if (stripped.startsWith("/")) stripped else "/$stripped"
    }

    private fun localName(raw: String): String = raw.substringAfter(':')

    private fun friendlyError(e: Throwable): String {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("404") -> "路径不存在：请检查服务器地址和根目录"
            msg.contains("401") || msg.contains("403") -> "认证失败：请检查用户名和密码"
            msg.contains("Unable to resolve", true) || msg.contains("UnknownHost", true) ->
                "无法解析服务器地址：请检查网络或域名"
            msg.contains("timeout", true) -> "连接超时：服务器响应过慢或网络不通"
            msg.contains("SSL", true) || msg.contains("certificate", true) -> "证书校验失败：请检查 HTTPS 证书"
            else -> msg.ifBlank { e.javaClass.simpleName }
        }
    }
}

private object XmlPullParserFactoryHolder {
    val factory = runCatching {
        XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        }
    }.getOrElse { XmlPullParserFactory.newInstance() }
}

private object HttpDate {
    private val formats = listOf(
        java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US),
        java.text.SimpleDateFormat("EEE MMM dd HH:mm:ss zzz yyyy", java.util.Locale.US)
    )

    fun parse(text: String): Long {
        for (f in formats) {
            runCatching { return f.parse(text)?.time ?: 0L }
        }
        return 0L
    }
}
