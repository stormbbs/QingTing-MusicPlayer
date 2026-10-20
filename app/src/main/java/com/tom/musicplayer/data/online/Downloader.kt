package com.tom.musicplayer.data.online

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.tom.musicplayer.R
import com.tom.musicplayer.data.Prefs
import com.tom.musicplayer.data.UriGrant
import com.tom.musicplayer.model.OnlineSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** 一次下载的状态。界面靠 [Downloader.tasks] 画进度。 */
data class DownloadTask(
    val key: String,
    val title: String,
    val artist: String,
    val progress: Int,          // 0..100，未知大小时为 -1
    val done: Boolean = false,
    val error: String? = null
)

/**
 * 在线歌曲下载。
 *
 * 两个落点，设置里可切换：
 * - `app`：App 私有目录（files/Downloads），在「我的下载」页里管理，卸载 App 会一起清掉；
 * - `saf`：用户授权过的音乐文件夹，下载完立刻被现有扫描收录，和本地歌混在一起听。
 *
 * 下载前先向音源解析直链（直链有时效，不能提前存），然后流式写盘，
 * 过程中用一条通知显示进度 —— 后台下载没有通知，用户会以为没在动。
 */
object Downloader {

    private const val CHANNEL = "download"
    private const val DIR = "Downloads"

    /** 正在跑 / 刚跑完的任务。key 是 `sourceId:songId`。 */
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun taskKey(song: OnlineSong) = "${song.sourceId}:${song.songId}"

    private fun upsert(task: DownloadTask) {
        _tasks.value = _tasks.value.filterNot { it.key == task.key } + task
    }

    fun clearFinished() {
        _tasks.value = _tasks.value.filterNot { it.done || it.error != null }
    }

    /**
     * 下载一首歌。
     * @return 成功返回落盘记录，失败返回原因（直接能给用户看）
     */
    suspend fun download(context: Context, song: OnlineSong, quality: String): Result<DownloadedSong> =
        withContext(Dispatchers.IO) {
            val key = taskKey(song)
            upsert(DownloadTask(key, song.name, song.artist, -1))
            val notify = Notifier(context.applicationContext)
            notify.progress(song.name, 0)
            try {
                val source = OnlineSourceStore.byId(context, song.sourceId)
                    ?: return@withContext Result.failure(Exception("音源已被删除"))
                val resolved = OnlineApi.resolve(source, song.songId, quality)

                val target = Prefs.getDownloadTarget(context)
                val fileName = safeFileName(song, resolved.url)
                val sink = openSink(context, target, fileName)
                    ?: return@withContext Result.failure(
                        Exception(if (target == "saf") "授权文件夹不可写，请重新选择音乐文件夹"
                        else "无法创建下载目录")
                    )

                val written = stream(resolved.url, sink.stream) { pct ->
                    upsert(DownloadTask(key, song.name, song.artist, pct))
                    if (pct >= 0) notify.progress(song.name, pct)
                }
                sink.finish(written)

                if (written <= 0L) {
                    sink.abort()
                    throw Exception("下载到的文件是空的")
                }

                val record = DownloadedSong(
                    id = UUID.randomUUID().toString(),
                    title = song.name,
                    artist = song.artist,
                    album = song.album,
                    durationMs = song.durationMs,
                    path = sink.relativePath,
                    treeUri = sink.treeUri,
                    displayName = sink.displayName,
                    sizeBytes = written,
                    sourceId = song.sourceId,
                    songId = song.songId,
                    quality = resolved.quality,
                    finishedAt = System.currentTimeMillis()
                )
                // 存进授权文件夹的那些不用记：本地扫描会自己扫出来，记了反而重复
                if (record.isInAppDir) DownloadStore.add(context, record)

                upsert(DownloadTask(key, song.name, song.artist, 100, done = true))
                notify.done(song.name)
                Result.success(record)
            } catch (e: Exception) {
                val msg = e.message ?: "下载失败"
                upsert(DownloadTask(key, song.name, song.artist, 0, error = msg))
                notify.failed(song.name, msg)
                Result.failure(Exception(msg))
            }
        }

    /** 边下边写，回调里带上百分比（总大小未知时回调 -1）。 */
    private fun stream(url: String, out: java.io.OutputStream, onProgress: (Int) -> Unit): Long {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("下载失败：服务器返回 ${resp.code}")
            val body = resp.body ?: throw Exception("下载失败：没有内容")
            val total = body.contentLength()
            var written = 0L
            var lastPct = -2
            resp.body!!.byteStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    written += n
                    if (total > 0) {
                        val pct = (written * 100 / total).toInt().coerceIn(0, 100)
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
            out.flush()
            return written
        }
    }

    /** 文件名里这些字符在各大文件系统上都会惹麻烦，统一换掉。 */
    private fun safeFileName(song: OnlineSong, url: String): String {
        val ext = runCatching {
            val path = Uri.parse(url).path.orEmpty()
            val dot = path.lastIndexOf('.')
            if (dot >= 0 && dot < path.length - 1) path.substring(dot).lowercase() else ""
        }.getOrDefault("").let { if (it.length <= 5 && it.startsWith(".")) it else ".mp3" }
        val raw = "${song.artist} - ${song.name}$ext"
        return raw.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    // --------------------------------------------------------------- 落点

    private class Sink(
        val stream: java.io.OutputStream,
        /** App 私有目录下的相对路径；走 SAF 时为空。 */
        val relativePath: String?,
        val treeUri: String?,
        val displayName: String?,
        private val onFinish: (Long) -> Unit,
        private val onAbort: () -> Unit
    ) {
        fun finish(size: Long) = onFinish(size)
        fun abort() = onAbort()
    }

    private fun openSink(context: Context, target: String, fileName: String): Sink? {
        return if (target == "saf") openSafSink(context, fileName) else openAppSink(context, fileName)
    }

    private fun openAppSink(context: Context, fileName: String): Sink? {
        val dir = File(context.filesDir, DIR)
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, fileName)
        val stream = runCatching { file.outputStream() }.getOrNull() ?: return null
        return Sink(
            stream = stream,
            relativePath = "$DIR/$fileName",
            treeUri = null,
            displayName = null,
            onFinish = { runCatching { stream.close() } },
            onAbort = {
                runCatching { stream.close() }
                runCatching { file.delete() }
            }
        )
    }

    private fun openSafSink(context: Context, fileName: String): Sink? {
        val treeUri = Prefs.getTreeUri(context) ?: UriGrant.recoverTree(context) ?: return null
        // 选文件夹时拿到的授权不一定带写权限，这里补一次；补不到就明确失败，
        // 免得写了一半才抛 SecurityException
        if (!UriGrant.isPersistedWrite(context, treeUri) && !UriGrant.takeWrite(context, treeUri)) {
            return null
        }
        val dir = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val mime = when (fileName.substringAfterLast('.', "").lowercase()) {
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            else -> "audio/mpeg"
        }
        val doc = dir.findFile(fileName) ?: dir.createFile(mime, fileName) ?: return null
        val stream = runCatching { context.contentResolver.openOutputStream(doc.uri, "w") }
            .getOrNull() ?: return null
        return Sink(
            stream = stream,
            relativePath = null,
            treeUri = treeUri.toString(),
            displayName = fileName,
            onFinish = { runCatching { stream.close() } },
            onAbort = {
                runCatching { stream.close() }
                runCatching { doc.delete() }
            }
        )
    }

    // --------------------------------------------------------------- 通知

    /** 下载进度通知。播放通知是 MediaSession 自己发的，这里单独开一个渠道。 */
    private class Notifier(private val context: Context) {

        private val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        init {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (manager.getNotificationChannel(CHANNEL) == null) {
                    manager.createNotificationChannel(
                        NotificationChannel(CHANNEL, "下载", NotificationManager.IMPORTANCE_LOW)
                    )
                }
            }
        }

        private fun id(title: String) = title.hashCode()

        fun progress(title: String, pct: Int) {
            val builder = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("正在下载：$title")
                .setContentText(if (pct > 0) "$pct%" else "准备中")
                .setProgress(100, pct.coerceAtLeast(0), pct < 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
            runCatching { manager.notify(id(title), builder.build()) }
        }

        fun done(title: String) {
            val builder = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("下载完成：$title")
                .setProgress(0, 0, false)
                .setAutoCancel(true)
            runCatching { manager.notify(id(title), builder.build()) }
        }

        fun failed(title: String, reason: String) {
            val builder = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("下载失败：$title")
                .setContentText(reason)
                .setAutoCancel(true)
            runCatching { manager.notify(id(title), builder.build()) }
        }
    }

    /** 「我的下载」页删文件：私有目录直接删，SAF 里的按名字找出来删。 */
    fun delete(context: Context, item: DownloadedSong): Boolean {
        return if (item.isInAppDir) {
            val file = File(context.filesDir, item.path!!)
            val ok = runCatching { file.delete() }.getOrDefault(false)
            if (ok) DownloadStore.remove(context, item.id)
            ok
        } else {
            val treeUri = item.treeUri?.let { Uri.parse(it) } ?: return false
            val dir = DocumentFile.fromTreeUri(context, treeUri) ?: return false
            val doc = dir.findFile(item.displayName ?: return false) ?: return false
            runCatching { doc.delete() }.getOrDefault(false)
        }
    }

    /** 私有下载目录当前占了多少字节。 */
    fun usedBytes(context: Context): Long {
        val dir = File(context.filesDir, DIR)
        if (!dir.exists()) return 0L
        return runCatching { dir.listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)
    }
}
