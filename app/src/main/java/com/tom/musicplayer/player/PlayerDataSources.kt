@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.tom.musicplayer.player

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.tom.musicplayer.data.SourceStore
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 播放器的「数据来源」中枢。
 *
 * 三条要点：
 * 1. content://（本地 SAF）继续走系统 ContentResolver；
 *    http(s):// 换成 OkHttp 数据源，这样 WebDAV 的 Basic 鉴权可以由拦截器统一注入。
 * 2. 只有网络流套一层 SimpleCache —— 听过的歌会被写进磁盘，
 *    第二次播放不再走流量，断网也能播。本地文件没必要再复制一份。
 * 3. 缓存层出错会自动回落到直连，避免一份写坏的缓存让整首歌再也放不了。
 */
object PlayerDataSources {

    private const val CACHE_DIR = "media_cache"
    private const val CACHE_BYTES = 512L * 1024 * 1024
    private const val USER_AGENT = "QingTingMusicPlayer/1.0"

    @Volatile
    private var okHttpClient: OkHttpClient? = null

    @Volatile
    private var simpleCache: SimpleCache? = null

    fun okHttp(context: Context): OkHttpClient =
        okHttpClient ?: synchronized(this) {
            okHttpClient ?: OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .addInterceptor(WebDavAuthInterceptor(context.applicationContext))
                .build()
                .also { okHttpClient = it }
        }

    fun cache(context: Context): SimpleCache =
        simpleCache ?: synchronized(this) {
            val dir = File(context.filesDir, CACHE_DIR)
            SimpleCache(
                dir,
                LeastRecentlyUsedCacheEvictor(CACHE_BYTES),
                StandaloneDatabaseProvider(context.applicationContext)
            ).also { simpleCache = it }
        }

    fun dataSourceFactory(context: Context): DataSource.Factory {
        val httpFactory = OkHttpDataSource.Factory(okHttp(context.applicationContext))
            .setUserAgent(USER_AGENT)
        val localFactory = DefaultDataSource.Factory(context.applicationContext, httpFactory)
        val cacheFactory = CacheDataSource.Factory()
            .setCache(cache(context.applicationContext))
            .setUpstreamDataSourceFactory(localFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DataSource.Factory {
            SchemeRouterDataSource(cacheFactory.createDataSource(), localFactory.createDataSource())
        }
    }

    fun mediaSourceFactory(context: Context): MediaSource.Factory =
        DefaultMediaSourceFactory(dataSourceFactory(context.applicationContext))

    /** 已占用缓存字节数，用于展示「清理缓存」。 */
    fun cachedBytes(context: Context): Long =
        runCatching { cache(context.applicationContext).cacheSpace }.getOrDefault(0L)

    fun clearCache(context: Context, uriFilter: ((String) -> Boolean)? = null) {
        runCatching {
            val cache = cache(context.applicationContext)
            val keys = cache.keys.toList()
            keys.filter { uriFilter?.invoke(it) ?: true }
                .forEach { cache.removeResource(it) }
        }
    }

    /** 这首歌是否已经落缓存（部分也算）。 */
    fun isCached(context: Context, uri: String): Boolean {
        val cache = runCatching { cache(context.applicationContext) }.getOrNull() ?: return false
        return runCatching { cache.getCachedLength(uri, 0, Long.MAX_VALUE) > 0 }.getOrDefault(false)
    }
}

/**
 * 给 WebDAV 请求自动补 Authorization。
 *
 * 之所以用拦截器而不是 [okhttp3.Authenticator]：不少 WebDAV 实现不会先返回带 challenge 的 401，
 * 主动带上凭据反而更「兼容一些」。
 */
private class WebDavAuthInterceptor(private val context: Context) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        val server = SourceStore.matchByUrl(context, url)?.takeIf { it.needsAuth }
            ?: return chain.proceed(request)
        val auth = request.newBuilder()
            .header("Authorization", Credentials.basic(server.user, server.password))
            .build()
        return chain.proceed(auth)
    }
}

/**
 * 按 URI 决定走缓存还是直连：本地 content:// 直接放行，网络地址套缓存层。
 * 缓存层一旦打开失败立刻降级到直连，绝不因为缓存问题让用户歌放不了。
 */
private class SchemeRouterDataSource(
    private val cached: DataSource,
    private val direct: DataSource
) : DataSource {

    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        cached.addTransferListener(transferListener)
        direct.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val useCache = dataSpec.uri.scheme == "http" || dataSpec.uri.scheme == "https"
        return if (useCache) {
            try {
                cached.open(dataSpec).also { active = cached }
            } catch (e: IOException) {
                direct.open(dataSpec).also { active = direct }
            }
        } else {
            direct.open(dataSpec).also { active = direct }
        }
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int =
        requireActive().read(target, offset, length)

    override fun getUri() = active?.uri

    override fun getResponseHeaders() = active?.responseHeaders ?: emptyMap()

    override fun close() {
        active?.close()
        active = null
    }

    private fun requireActive(): DataSource = active ?: throw IOException("数据源尚未打开")
}
