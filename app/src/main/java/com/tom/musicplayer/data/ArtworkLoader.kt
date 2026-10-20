package com.tom.musicplayer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.core.content.FileProvider
import java.io.File

/**
 * 从音频文件内嵌标签（ID3 / FLAC picture / WAV）中读取封面。
 * 全程使用采样解码，绝不把整张原始封面图放进内存。
 */
object ArtworkLoader {

    private val bitmapCache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** 按需采样后的封面；没有封面返回 null。 */
    fun bitmap(context: Context, uri: Uri, targetPx: Int): Bitmap? {
        val key = "$uri@$targetPx"
        bitmapCache.get(key)?.let { return it }
        val decoded = runCatching { decodeEmbedded(context, uri, targetPx) }.getOrNull() ?: return null
        bitmapCache.put(key, decoded)
        return decoded
    }

    /**
     * 在线封面（音源返回的 http 地址）。
     *
     * 和 [bitmap] 分开是因为取色只需要很小的图：在线图片按 128px 采样，
     * 既省流量也不会把一张 1000px 的封面塞进内存。
     */
    fun remoteBitmap(url: String, targetPx: Int = 128): Bitmap? {
        val key = "$url@$targetPx"
        bitmapCache.get(key)?.let { return it }
        val decoded = runCatching {
            val conn = java.net.URL(url).openConnection().apply {
                connectTimeout = 10_000
                readTimeout = 15_000
            }
            conn.getInputStream().use { stream ->
                val bytes = stream.readBytes()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }
        }.getOrNull() ?: return null
        bitmapCache.put(key, decoded)
        return decoded
    }

    /**
     * 采样压缩后的封面字节（JPEG）。
     * 用于放进 MediaMetadata.artworkData —— 通知栏 / 锁屏 / 车机等外部界面
     * 拿不到我们 SAF 目录的读权限，只有把图片数据随元数据一起给出去才显示得出来。
     */
    fun thumbBytes(context: Context, uri: Uri, targetPx: Int = 320): ByteArray? {
        val bitmap = bitmap(context, uri, targetPx) ?: return null
        return runCatching {
            java.io.ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                out.toByteArray()
            }
        }.getOrNull()
    }

    /**
     * 封面缩略图的对外图片 URI（FileProvider）。
     *
     * 系统界面（通知栏媒体卡片 / 锁屏）只会把它拿到的 artworkUri 当「图片」去解码，
     * 直接给它音频文件的 Uri 必然解码失败，所以这里先把内嵌封面导出成 JPEG 文件。
     */
    fun coverUri(context: Context, audioUri: Uri, targetPx: Int = 512): Uri? {
        val file = coverFile(context, audioUri, targetPx) ?: return null
        return runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.artwork", file)
        }.getOrNull()
    }

    private fun coverFile(context: Context, audioUri: Uri, targetPx: Int): File? {
        val dir = File(context.filesDir, "artwork")
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, "${md5(audioUri.toString())}.jpg")
        if (file.exists() && file.length() > 0) return file
        val bytes = thumbBytes(context, audioUri, targetPx) ?: return null
        return runCatching {
            file.writeBytes(bytes)
            file
        }.getOrNull()
    }

    private fun md5(value: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun decodeEmbedded(context: Context, uri: Uri, targetPx: Int): Bitmap? {
        val data = readEmbeddedData(context, uri) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(data, 0, data.size, options)
    }

    private fun readEmbeddedData(context: Context, uri: Uri): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.embeddedPicture
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun sampleSizeFor(width: Int, height: Int, targetPx: Int): Int {
        val maxEdge = maxOf(width, height)
        if (maxEdge <= targetPx) return 1
        var sample = 1
        while (maxEdge / (sample * 2) >= targetPx) sample *= 2
        return sample
    }
}
