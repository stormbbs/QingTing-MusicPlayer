package com.tom.musicplayer.data.online

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.tom.musicplayer.data.Prefs
import com.tom.musicplayer.data.UriGrant
import com.tom.musicplayer.model.OnlineSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 * - `app`：公共下载目录 `内部存储/Music/轻听/`。文件属于系统媒体库而不是 App，
 *   卸载重装后还在，任何文件管理器都能复制走；
 * - `saf`：用户授权过的音乐文件夹，下载完直接进本地曲库。
 *
 * 下载前先向音源解析直链（直链有时效，不能提前存），然后流式写盘，
 * 过程中用一条通知显示进度 —— 后台下载没有通知，用户会以为没在动。
 */
object Downloader {

    private const val CHANNEL = "download"

    /** 旧版私有目录，只用于迁移。 */
    private const val LEGACY_DIR = "Downloads"

    /** 正在跑 / 刚跑完的任务。key 是 `sourceId:songId`。 */
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks

    /**
     * 有歌落地了。界面收到后刷新本地曲库 ——
     * 否则刚下到音乐文件夹的歌要等下次重启或手动重扫才看得见。
     */
    private val _saved = MutableSharedFlow<Unit>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val saved: SharedFlow<Unit> = _saved.asSharedFlow()

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
                    fileUri = sink.fileUri,
                    location = sink.location,
                    treeUri = sink.treeUri,
                    displayName = sink.displayName,
                    sizeBytes = written,
                    sourceId = song.sourceId,
                    songId = song.songId,
                    quality = resolved.quality,
                    finishedAt = System.currentTimeMillis()
                )
                DownloadStore.add(context, record)

                upsert(DownloadTask(key, song.name, song.artist, 100, done = true))
                notify.done(song.name)
                _saved.tryEmit(Unit)
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
            body.byteStream().use { input ->
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
            val path = android.net.Uri.parse(url).path.orEmpty()
            val dot = path.lastIndexOf('.')
            if (dot >= 0 && dot < path.length - 1) path.substring(dot).lowercase() else ""
        }.getOrDefault("").let { if (it.length <= 5 && it.startsWith(".")) it else ".mp3" }
        val raw = "${song.artist} - ${song.name}$ext"
        return raw.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private fun mimeOf(fileName: String): String =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "ogg" -> "audio/ogg"
            else -> "audio/mpeg"
        }

    // --------------------------------------------------------------- 落点

    private class Sink(
        val stream: java.io.OutputStream,
        /** 公共下载目录 / 旧系统的文件 Uri；走 SAF 时为空。 */
        val fileUri: String?,
        /** 给人看的位置。 */
        val location: String?,
        val treeUri: String?,
        val displayName: String?,
        private val onFinish: (Long) -> Unit,
        private val onAbort: () -> Unit
    ) {
        fun finish(size: Long) = onFinish(size)
        fun abort() = onAbort()
    }

    private fun openSink(context: Context, target: String, fileName: String): Sink? {
        return if (target == "saf") openSafSink(context, fileName) else openPublicSink(context, fileName)
    }

    /**
     * 公共下载目录。
     *
     * Android 10 起走 MediaStore：系统负责建目录、分配 path，我们的文件落在
     * `内部存储/Music/轻听/`，卸载 App 不会连带删除。
     */
    private fun openPublicSink(context: Context, fileName: String): Sink? {
        val mime = mimeOf(fileName)
        if (PublicMusicStore.supported()) {
            val uri = PublicMusicStore.create(context, fileName, mime)
            if (uri != null) {
                val out = PublicMusicStore.openOutput(context, uri)
                if (out != null) {
                    return Sink(
                        stream = out,
                        fileUri = uri.toString(),
                        location = PublicMusicStore.displayPath(PublicMusicStore.RELATIVE_DIR, fileName),
                        treeUri = null,
                        displayName = fileName,
                        onFinish = { size ->
                            runCatching { out.close() }
                            PublicMusicStore.publish(context, uri, size)
                        },
                        onAbort = {
                            runCatching { out.close() }
                            runCatching { PublicMusicStore.delete(context, uri) }
                        }
                    )
                }
                runCatching { PublicMusicStore.delete(context, uri) }
            }
        }
        // 旧系统（或媒体库写入失败）退回普通文件
        val dir = PublicMusicStore.legacyDir(context)
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, fileName)
        val out = runCatching { file.outputStream() }.getOrNull() ?: return null
        return Sink(
            stream = out,
            fileUri = android.net.Uri.fromFile(file).toString(),
            location = PublicMusicStore.legacyDisplayPath(context, fileName),
            treeUri = null,
            displayName = fileName,
            onFinish = { runCatching { out.close() } },
            onAbort = {
                runCatching { out.close() }
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
        val doc = dir.findFile(fileName) ?: dir.createFile(mimeOf(fileName), fileName) ?: return null
        val stream = runCatching { context.contentResolver.openOutputStream(doc.uri, "w") }
            .getOrNull() ?: return null
        return Sink(
            stream = stream,
            fileUri = null,
            location = safLocation(treeUri, fileName),
            treeUri = treeUri.toString(),
            displayName = fileName,
            onFinish = { runCatching { stream.close() } },
            onAbort = {
                runCatching { stream.close() }
                runCatching { doc.delete() }
            }
        )
    }

    /** 把 SAF 的目录树 id（`primary:Music`）翻译成文件管理器里的位置。 */
    fun safLocation(treeUri: android.net.Uri, fileName: String): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return fileName
        val volume = docId.substringBefore(':', docId)
        val path = docId.substringAfter(':', "").trim('/')
        val root = if (volume == "primary") "内部存储"
        else runCatching { "/storage/$volume" }.getOrDefault(volume)
        return buildString {
            append(root)
            if (path.isNotEmpty()) append('/').append(path)
            append('/').append(fileName)
        }
    }

    /** 当前下载目标的展示位置，设置页直接显示这一句。 */
    fun targetLocation(context: Context): String {
        val target = Prefs.getDownloadTarget(context)
        if (target != "saf") return PublicMusicStore.DISPLAY_DIR
        val tree = Prefs.getTreeUri(context) ?: UriGrant.recoverTree(context)
            ?: return "还没选择音乐文件夹"
        val docId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            ?: return "已选文件夹"
        val volume = docId.substringBefore(':', docId)
        val path = docId.substringAfter(':', "").trim('/')
        val root = if (volume == "primary") "内部存储" else "/storage/$volume"
        return if (path.isEmpty()) root else "$root/$path"
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

    // --------------------------------------------------------------- 增删查

    /** 「我的下载」页删文件：按落点分别删。 */
    fun delete(context: Context, item: DownloadedSong): Boolean {
        val ok = when {
            !item.fileUri.isNullOrBlank() -> {
                val uri = android.net.Uri.parse(item.fileUri)
                if (uri.scheme == "file") {
                    uri.path?.let { File(it).delete() } == true
                } else {
                    PublicMusicStore.delete(context, uri)
                }
            }
            item.isSaf -> {
                val treeUri = android.net.Uri.parse(item.treeUri)
                val dir = DocumentFile.fromTreeUri(context, treeUri)
                val doc = dir?.findFile(item.displayName ?: "")
                doc != null && runCatching { doc.delete() }.getOrDefault(false)
            }
            else -> false
        }
        // 旧版私有目录里的残留也顺手清掉
        item.legacyPath?.let { runCatching { File(context.filesDir, it).delete() } }
        if (ok) DownloadStore.remove(context, item.id)
        return ok
    }

    /** 已下载歌曲总共占了多少字节。 */
    fun usedBytes(context: Context): Long =
        DownloadStore.load(context).sumOf { it.sizeBytes.coerceAtLeast(0L) }

    /**
     * 把已下载的歌复制到用户新选的文件夹。
     *
     * 公共目录里的歌本来就能被文件管理器拷走，这个入口是给「想一次拷到
     * SD 卡 / 电脑共享目录 / 另一个音乐 App 的曲库」这种情况用的。
     *
     * @return 成功复制的首数；第二个值是需要告诉用户的问题（没问题的为 null）
     */
    suspend fun exportAll(context: Context, targetTree: android.net.Uri): Pair<Int, String?> =
        withContext(Dispatchers.IO) {
            if (!UriGrant.isPersistedWrite(context, targetTree) &&
                !UriGrant.takeWrite(context, targetTree)
            ) {
                return@withContext 0 to "拿不到该文件夹的写权限，请重新选一次"
            }
            val dir = DocumentFile.fromTreeUri(context, targetTree)
                ?: return@withContext 0 to "无法打开目标文件夹"
            var done = 0
            var failed = 0
            DownloadStore.load(context).forEach { item ->
                val src = DownloadStore.resolveUri(context, item) ?: return@forEach
                val name = item.displayName
                    ?: item.location?.substringAfterLast('/')
                    ?: "${item.artist} - ${item.title}.mp3"
                val doc = dir.findFile(name) ?: dir.createFile(mimeOf(name), name)
                if (doc == null) {
                    failed++
                    return@forEach
                }
                val ok = runCatching {
                    context.contentResolver.openInputStream(src)?.use { input ->
                        context.contentResolver.openOutputStream(doc.uri, "w")?.use { output ->
                            input.copyTo(output, 64 * 1024)
                        }
                    } != null
                }.getOrDefault(false)
                if (ok) done++ else failed++
            }
            done to if (failed > 0) "$failed 首复制失败，其余已复制" else null
        }

    // --------------------------------------------------------------- 迁移

    /**
     * 把旧版私有目录（`filesDir/Downloads`）里的歌搬到公共下载目录。
     *
     * 用户升级前下载的歌是真的躺在私有目录里的，直接改路径等于把它们丢了，
     * 所以这里先复制到 `内部存储/Music/轻听/`，确认写完再删原件。
     */
    suspend fun migrateLegacy(context: Context): Int = withContext(Dispatchers.IO) {
        val records = DownloadStore.load(context)
        if (records.none { it.needsMigration }) return@withContext 0
        var moved = 0
        val kept = ArrayList<DownloadedSong>(records.size)
        records.forEach { item ->
            if (!item.needsMigration) {
                kept.add(item)
                return@forEach
            }
            val rel = item.legacyPath!!
            val file = File(context.filesDir, rel)
            if (!file.exists()) return@forEach   // 文件早就没了，记录也一并丢掉
            val migrated = migrateOne(context, item, file)
            if (migrated != null) {
                kept.add(migrated)
                moved++
            } else {
                kept.add(item)  // 迁移失败先留着原记录，下次再试
            }
        }
        DownloadStore.replaceAll(context, kept)
        // 空目录顺手删掉，免得看着像还有东西
        runCatching {
            val dir = File(context.filesDir, LEGACY_DIR)
            if (dir.isDirectory && dir.listFiles()?.isEmpty() == true) dir.delete()
        }
        moved
    }

    private fun migrateOne(context: Context, item: DownloadedSong, file: File): DownloadedSong? {
        val name = file.name
        val mime = mimeOf(name)
        if (PublicMusicStore.supported()) {
            val uri = PublicMusicStore.create(context, name, mime) ?: return null
            val out = PublicMusicStore.openOutput(context, uri)
            if (out == null) {
                PublicMusicStore.delete(context, uri)
                return null
            }
            val written = runCatching {
                out.use { sink -> file.inputStream().use { it.copyTo(sink, 64 * 1024) } }
                file.length()
            }.getOrDefault(-1L)
            if (written <= 0L) {
                PublicMusicStore.delete(context, uri)
                return null
            }
            PublicMusicStore.publish(context, uri, written)
            runCatching { file.delete() }
            return item.copy(
                fileUri = uri.toString(),
                location = PublicMusicStore.displayPath(PublicMusicStore.RELATIVE_DIR, name),
                legacyPath = null,
                displayName = item.displayName ?: name,
                sizeBytes = if (item.sizeBytes > 0) item.sizeBytes else written
            )
        }
        // 旧系统：搬到外部目录
        val dir = PublicMusicStore.legacyDir(context)
        val target = File(dir, name)
        val ok = runCatching { file.copyTo(target, overwrite = true) }.isSuccess
        if (!ok) return null
        runCatching { file.delete() }
        return item.copy(
            fileUri = android.net.Uri.fromFile(target).toString(),
            location = PublicMusicStore.legacyDisplayPath(context, name),
            legacyPath = null,
            displayName = item.displayName ?: name
        )
    }

    // ----------------------------------------------------------- 索引重建

    private class MediaEntry(
        val uri: String,
        val fileName: String,
        val title: String,
        val artist: String,
        val durationMs: Long,
        val size: Long,
        val location: String
    )

    /**
     * 重建「我的下载」列表。
     *
     * 下载记录本身存在 App 私有数据里，卸载 App 会被清掉；而歌在公共目录里好好活着。
     * 重装后若不补这一步，用户打开「我的下载」看到的是一片空白，会以为歌又没了。
     * 这里按目录去媒体库里把文件捞回来，按文件名补记录。
     *
     * @return 补回了几条
     */
    suspend fun rebuildIndex(context: Context): Int = withContext(Dispatchers.IO) {
        val current = DownloadStore.load(context)
        // 按文件名去重：同一条记录可能带着 external_primary / external 两种 Uri 写法，
        // 只比 Uri 会把同一个文件补成两条
        val knownNames = current
            .mapNotNull { it.displayName ?: it.location?.substringAfterLast('/') }
            .toHashSet()
        val safNames = current.filter { it.isSaf }.mapNotNull { it.displayName }.toHashSet()
        val found = queryPublicDownloads(context)
        if (found.isEmpty()) return@withContext 0
        var added = 0
        val list = ArrayList(current)
        found.forEach { entry ->
            if (entry.fileName in knownNames) return@forEach
            // 同一首歌可能已经有一条「下到音乐文件夹」的记录，按文件名去重
            if (entry.fileName in safNames) return@forEach
            list.add(
                DownloadedSong(
                    id = UUID.randomUUID().toString(),
                    title = entry.title.ifBlank { entry.fileName.substringBeforeLast('.') },
                    artist = entry.artist.ifBlank { "未知艺术家" },
                    album = "",
                    durationMs = entry.durationMs,
                    fileUri = entry.uri,
                    location = entry.location,
                    displayName = entry.fileName,
                    sizeBytes = entry.size,
                    quality = ""
                )
            )
            added++
        }
        if (added > 0) DownloadStore.replaceAll(context, list)
        added
    }

    private fun queryPublicDownloads(context: Context): List<MediaEntry> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE
        )
        val selection: String
        val args: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
            args = arrayOf("${PublicMusicStore.RELATIVE_DIR}/%")
        } else {
            @Suppress("DEPRECATION")
            selection = "${MediaStore.Audio.Media.DATA} LIKE ?"
            args = arrayOf("%/${PublicMusicStore.RELATIVE_DIR}/%")
        }
        val out = ArrayList<MediaEntry>()
        // 和写入时用同一个卷，Uri 才能和已有记录对上
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                args,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: continue
                    out.add(
                        MediaEntry(
                            uri = android.content.ContentUris.withAppendedId(
                                collection, id
                            ).toString(),
                            fileName = name,
                            // 媒体库把「读不出来」写成字面量 <unknown>，直接展示很难看
                            title = cursor.getString(titleCol).orEmpty()
                                .takeUnless { it == "<unknown>" }.orEmpty(),
                            artist = cursor.getString(artistCol).orEmpty()
                                .takeUnless { it == "<unknown>" }.orEmpty(),
                            durationMs = cursor.getLong(durCol),
                            size = cursor.getLong(sizeCol),
                            location = PublicMusicStore.displayPath(
                                PublicMusicStore.RELATIVE_DIR, name
                            )
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("QingTing", "queryPublicDownloads 失败", e)
        }
        android.util.Log.i("QingTing", "公共下载目录扫到 ${out.size} 个文件")
        return out
    }
}
