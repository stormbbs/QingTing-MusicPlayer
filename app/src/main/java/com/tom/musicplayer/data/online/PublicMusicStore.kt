package com.tom.musicplayer.data.online

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream

/**
 * 公共下载目录。
 *
 * 以前下载落 `filesDir/Downloads`，也就是 `/data/data/<包名>/files` —— 这个位置：
 *   - 文件管理器里根本看不到（连 adb 都要 run-as 才进得去）；
 *   - 卸载 App 时被系统一起删掉，用户下的歌全没了；
 *   - 也没法「拷到别处」，因为用户够不到它。
 *
 * 现在改成写在系统的媒体库里：`内部存储/Music/轻听/`。放在这里的好处是
 * 文件属于媒体库而不是 App —— 卸载重装后歌还在，任何文件管理器都能复制、
 * 任何播放器都能扫到，用户也不用记什么特殊路径。
 */
object PublicMusicStore {

    /** 相对路径，MediaStore 的约定是「一级目录/二级目录」。 */
    const val RELATIVE_DIR = "Music/轻听"

    /** 给人看的位置。 */
    const val DISPLAY_DIR = "内部存储/Music/轻听"

    /** Android 10 起才有 RELATIVE_PATH / IS_PENDING，之前的版本走别的路。 */
    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * 在媒体库里占一个待写的条目。
     *
     * IS_PENDING=1 期间别的 App 扫不到这个文件，所以正在下载的半成品不会被
     * 音乐 App 当成一首歌索引进去；[publish] 之后才对外可见。
     */
    fun create(context: Context, fileName: String, mime: String): Uri? {
        if (!supported()) return null
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, mime)
            put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_DIR)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return runCatching { context.contentResolver.insert(collection, values) }.getOrNull()
    }

    fun openOutput(context: Context, uri: Uri): OutputStream? =
        runCatching { context.contentResolver.openOutputStream(uri, "w") }.getOrNull()

    fun openInput(context: Context, uri: Uri) =
        runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()

    /** 下载写完、校验通过后再调用，文件这时才真正对外可见。 */
    fun publish(context: Context, uri: Uri, size: Long) {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.IS_PENDING, 0)
            if (size > 0) put(MediaStore.Audio.Media.SIZE, size)
        }
        runCatching { context.contentResolver.update(uri, values, null, null) }
    }

    fun delete(context: Context, uri: Uri): Boolean =
        runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)

    fun exists(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { true } ?: false
    }.getOrDefault(false)

    /** 组装给人看的完整位置，例如「内部存储/Music/轻听/周杰伦 - 晴天.mp3」。 */
    fun displayPath(relativePath: String?, fileName: String?): String {
        val dir = relativePath?.takeIf { it.isNotBlank() } ?: RELATIVE_DIR
        val clean = dir.trimEnd('/')
        return if (fileName.isNullOrBlank()) "内部存储/$clean" else "内部存储/$clean/$fileName"
    }

    /**
     * 旧系统（Android 9 及以下）没有 RELATIVE_PATH，只能老老实实写文件路径。
     *
     * 优先写公共的 Music 目录；没有存储权限时退回 App 外部目录 ——
     * 后者文件管理器同样能进（`Android/data/<包名>/files/Music`），
     * 但卸载会被清掉，所以界面上会把真实路径如实显示出来，不假装一样。
     */
    fun legacyDir(context: Context): File {
        val public = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val target = File(public, "轻听")
        if (runCatching { target.exists() || target.mkdirs() }.getOrDefault(false) && writable(target)) {
            return target
        }
        val fallback = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: context.filesDir,
            "轻听"
        )
        runCatching { fallback.mkdirs() }
        return fallback
    }

    /** 目录存在不代表能写（旧系统上没被授予存储权限时就是这样），实际试一次。 */
    private fun writable(dir: File): Boolean = runCatching {
        val probe = File(dir, ".probe")
        val ok = probe.createNewFile()
        if (ok) probe.delete()
        ok
    }.getOrDefault(false)

    /** 旧系统上文件在文件管理器里的位置。 */
    fun legacyDisplayPath(context: Context, fileName: String): String {
        val dir = legacyDir(context)
        val root = Environment.getExternalStorageDirectory().absolutePath
        val full = runCatching { dir.absolutePath }.getOrDefault("")
        return if (full.startsWith(root)) {
            "内部存储" + full.removePrefix(root) + "/" + fileName
        } else {
            full + "/" + fileName
        }
    }
}
