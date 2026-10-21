package com.tom.musicplayer.data.update

import android.content.Context
import android.util.Log
import com.tom.musicplayer.BuildConfig
import com.tom.musicplayer.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 一个新版本。
 *
 * 字段全部来自更新清单（仓库根目录的 `latest.json`，格式见该文件注释）：
 * ```json
 * {
 *   "versionCode": 8,
 *   "versionName": "1.7-1021",
 *   "url": "https://example.com/轻听-1.7.apk",
 *   "mirrorUrl": "https://mirror.example.com/轻听-1.7.apk",
 *   "size": 21800000,
 *   "sha256": "……",
 *   "notes": "1. 播放页加下载按钮\n2. 支持应用内升级",
 *   "mandatory": false
 * }
 * ```
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    /** 主地址不通时再试这个（国内访问 GitHub 常常需要）。 */
    val mirrorUrl: String? = null,
    /** 文件大小，用来校验下完没下完；不知道就填 0。 */
    val sizeBytes: Long = 0L,
    /** 可选：安装包 sha256，填了就强制校验。 */
    val sha256: String? = null,
    val notes: String = "",
    val mandatory: Boolean = false
)

/**
 * 检查更新并下载安装包。
 *
 * 为什么不用「让用户去应用商店」：这个 App 是侧载分发的，没有商店条目。
 * 为什么不用「每次手动传 APK 让人卸载重装」：卸载会把下载记录、收藏、歌单、
 * 音源配置全部清掉。走系统安装器原地覆盖，数据全保留。
 */
object UpdateCenter {

    private const val TAG = "QingTing"

    /**
     * 内置默认清单地址，按顺序试，第一个通的就用。
     *
     * jsDelivr 在国内通常能通，GitHub raw 作为兜底；两边都是同一个仓库的
     * `latest.json`，改一处就够。想换成自己的服务器，在「我的 → 关于与更新」
     * 里填自定义地址即可。
     */
    private val DEFAULT_MANIFESTS = listOf(
        "https://cdn.jsdelivr.net/gh/stormbbs/QingTing-MusicPlayer@main/latest.json",
        "https://raw.githubusercontent.com/stormbbs/QingTing-MusicPlayer/main/latest.json"
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** 更新流程的全部状态，界面直接订阅它。 */
    sealed interface State {
        /** 什么都没做（或用户关掉了提示）。 */
        data object Idle : State

        data object Checking : State

        /** 已经是最新版。 */
        data class UpToDate(val current: String) : State

        data class Available(val info: UpdateInfo) : State

        data class Downloading(val info: UpdateInfo, val percent: Int) : State

        /** 包已经下好，等用户点「安装」。 */
        data class Ready(val info: UpdateInfo, val file: File) : State

        /** 包下好了，但系统还没允许本应用安装 APK。 */
        data class NeedPermission(val info: UpdateInfo, val file: File) : State

        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val currentVersionName: String get() = BuildConfig.VERSION_NAME
    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE

    // ------------------------------------------------------------ 检查更新

    /** 启动时静默检查：只在开关打开时做，出错不打扰。 */
    fun autoCheck(context: Context) {
        if (!Prefs.getAutoUpdate(context)) return
        check(context, manual = false)
    }

    /**
     * @param manual 用户主动点「检查更新」。为 true 时任何结果都会反馈（已是最新 / 失败原因），
     *               为 false 时只在真有新版本时才改状态，其余情况保持安静。
     */
    fun check(context: Context, manual: Boolean = true) {
        if (_state.value is State.Checking || _state.value is State.Downloading) return
        val app = context.applicationContext
        _state.value = State.Checking
        scope.launch {
            val result = fetchLatest(app)
            val info = result.getOrNull()
            if (info == null) {
                val reason = result.exceptionOrNull()?.message ?: "无法获取更新信息"
                Log.i(TAG, "检查更新失败：$reason")
                _state.value = if (manual) State.Failed(reason) else State.Idle
                return@launch
            }
            if (info.versionCode <= BuildConfig.VERSION_CODE) {
                _state.value = State.UpToDate(BuildConfig.VERSION_NAME)
                return@launch
            }
            // 用户上次说过「这个版本先跳过」，静默检查就别再弹了；
            // 手动检查仍然要告诉他确实有新版本
            if (!manual && Prefs.getSkippedUpdate(app) == info.versionCode) {
                _state.value = State.Idle
                return@launch
            }
            _state.value = State.Available(info)
        }
    }

    /** 用户选「这次先不更新」。 */
    fun skip(context: Context) {
        val info = (state.value as? State.Available)?.info ?: return
        Prefs.saveSkippedUpdate(context, info.versionCode)
        _state.value = State.Idle
    }

    fun dismiss() {
        _state.value = State.Idle
    }

    private fun manifestUrls(context: Context): List<String> {
        val custom = Prefs.getUpdateUrl(context).trim()
        return if (custom.isNotEmpty()) listOf(custom) else DEFAULT_MANIFESTS
    }

    private fun fetchLatest(context: Context): Result<UpdateInfo> {
        var last: Exception = Exception("没有可用的更新源")
        for (url in manifestUrls(context)) {
            val text = try {
                httpGet(url)
            } catch (e: Exception) {
                last = e
                continue
            }
            val info = try {
                parseManifest(text, url)
            } catch (e: Exception) {
                last = Exception("清单格式不对：${e.message}")
                continue
            }
            if (info != null) return Result.success(info)
            last = Exception("清单里没有 versionCode / url")
        }
        return Result.failure(last)
    }

    private fun httpGet(url: String): String {
        val request = Request.Builder()
            .url(url)
            // 清单必须拿新的：CDN 会把旧版本缓存住，用户就永远「已是最新」
            .header("Cache-Control", "no-cache")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            return resp.body?.string() ?: throw Exception("空响应")
        }
    }

    /**
     * @param manifestUrl 清单自己的地址。清单里的 `url` 可以只写文件名，
     *                    这时按清单所在目录拼出完整地址——换个域名放同样的文件，
     *                    清单一个字都不用改。
     */
    private fun parseManifest(raw: String, manifestUrl: String): UpdateInfo? {
        val root = JSONObject(raw)
        // 允许把版本信息包在 "latest" 里，写清单时更不容易和别的字段搞混
        val o = root.optJSONObject("latest") ?: root
        val code = o.optInt("versionCode", 0)
        val url = o.optString("url").ifBlank { o.optString("apk") }
        if (code <= 0 || url.isBlank()) return null
        return UpdateInfo(
            versionCode = code,
            versionName = o.optString("versionName").ifBlank { "v$code" },
            url = absolute(url, manifestUrl),
            mirrorUrl = o.optString("mirrorUrl").takeIf { it.isNotBlank() }
                ?.let { absolute(it, manifestUrl) },
            sizeBytes = o.optLong("size", 0L),
            sha256 = o.optString("sha256").takeIf { it.isNotBlank() },
            notes = o.optString("notes").replace("\\n", "\n"),
            mandatory = o.optBoolean("mandatory", false)
        )
    }

    /** 相对地址按清单所在目录解析；本来就是完整地址就原样返回。 */
    private fun absolute(link: String, manifestUrl: String): String {
        if (link.startsWith("http://") || link.startsWith("https://")) return link
        return manifestUrl.toHttpUrlOrNull()?.resolve(link)?.toString() ?: link
    }

    // ------------------------------------------------------------ 下载安装

    /** 下载并（在允许的前提下）直接拉起安装器。 */
    fun download(context: Context) {
        val info = when (val s = state.value) {
            is State.Available -> s.info
            is State.Ready -> s.info
            is State.NeedPermission -> s.info
            else -> return
        }
        if (state.value is State.Downloading) return
        val app = context.applicationContext
        scope.launch {
            _state.value = State.Downloading(info, 0)
            val file = runCatching { fetchApk(app, info) }
                .getOrElse { e ->
                    _state.value = State.Failed(e.message ?: "下载失败")
                    return@launch
                }
            _state.value = State.Ready(info, file)
            install(app)
        }
    }

    /**
     * 拉起系统安装器。没权限时先引导用户去打开「安装未知应用」，
     * 状态切到 [State.NeedPermission]，页面上的按钮会变成「去授权」。
     */
    fun install(context: Context) {
        val ready = state.value as? State.Ready ?: return
        val app = context.applicationContext
        if (!ApkInstaller.canInstall(app)) {
            _state.value = State.NeedPermission(ready.info, ready.file)
            ApkInstaller.requestPermission(app)
            return
        }
        if (!ApkInstaller.install(app, ready.file)) {
            _state.value = State.Failed("打不开系统安装器，请用文件管理器手动安装：${ready.file.name}")
        }
    }

    /** 授权回来后用户再点一次「安装」。 */
    fun installAfterPermission(context: Context) {
        val s = state.value
        val info = when (s) {
            is State.NeedPermission -> s.info
            is State.Ready -> s.info
            else -> return
        }
        val file = when (s) {
            is State.NeedPermission -> s.file
            is State.Ready -> s.file
            else -> return
        }
        val app = context.applicationContext
        if (!ApkInstaller.canInstall(app)) {
            ApkInstaller.requestPermission(app)
            return
        }
        _state.value = State.Ready(info, file)
        if (!ApkInstaller.install(app, file)) {
            _state.value = State.Failed("仍然装不了，请把「安装未知应用」打开后重试")
        }
    }

    /** 用户从文件管理器里挑一个 APK：不联网也能升级，同样不用卸载。 */
    fun installFromFile(context: Context, uri: android.net.Uri) {
        val app = context.applicationContext
        scope.launch {
            val file = runCatching {
                ApkInstaller.copyToCache(app, uri, "local-update-${System.currentTimeMillis()}.apk")
            }.getOrNull()
            if (file == null) {
                _state.value = State.Failed("读取不了这个文件")
                return@launch
            }
            val info = UpdateInfo(
                versionCode = 0,
                versionName = "本地安装包",
                url = uri.toString()
            )
            _state.value = State.Ready(info, file)
            install(app)
        }
    }

    private suspend fun fetchApk(context: Context, info: UpdateInfo): File =
        withContext(Dispatchers.IO) {
            ApkInstaller.clear(context)
            val target = File(ApkInstaller.cacheDir(context), "update-${info.versionCode}.apk")
            val candidates = listOfNotNull(info.url, info.mirrorUrl)
            var lastError: Exception? = null
            for (url in candidates) {
                val ok = runCatching { downloadTo(url, target, info) }.getOrElse { e ->
                    lastError = e as? Exception ?: Exception(e.message)
                    false
                }
                if (ok) return@withContext target
            }
            throw lastError ?: Exception("下载失败")
        }

    /** @return 校验通过（大小 / sha256）才算成功 */
    private fun downloadTo(url: String, target: File, info: UpdateInfo): Boolean {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("下载失败：HTTP ${resp.code}")
            val body = resp.body ?: throw Exception("下载失败：没有内容")
            val total = body.contentLength().takeIf { it > 0 } ?: info.sizeBytes
            var written = 0L
            var lastPct = -2
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        written += n
                        if (total > 0) {
                            val pct = (written * 100 / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                _state.value = State.Downloading(info, pct)
                            }
                        }
                    }
                }
            }
            if (written <= 0) throw Exception("下载到的文件是空的")
            if (info.sizeBytes > 0 && written != info.sizeBytes) {
                throw Exception("安装包不完整（${written}/${info.sizeBytes} 字节），请重试")
            }
            info.sha256?.let { expected ->
                val actual = sha256Of(target)
                if (!actual.equals(expected, ignoreCase = true)) {
                    throw Exception("安装包校验失败，可能下载时被改坏了")
                }
            }
            return true
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
