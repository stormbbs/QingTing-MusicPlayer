package com.tom.musicplayer.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.tom.musicplayer.data.ArtworkLoader

/**
 * 通知栏 / 锁屏用的封面加载器。
 *
 * MediaItem 里的封面 URI 是我们自己导出的图片（FileProvider），
 * 这里直接按图片解码；若传入的是音频文件 Uri，则回退到读取内嵌封面。
 */
@OptIn(UnstableApi::class)
class ArtworkBitmapLoader(private val context: Context) : BitmapLoader {

    override fun supportsMimeType(mimeType: String): Boolean = true

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
        CallbackToFutureAdapter.getFuture { completer ->
            val bitmap = runCatching { BitmapFactory.decodeByteArray(data, 0, data.size) }
                .getOrNull()
            if (bitmap != null) completer.set(bitmap) else completer.setCancelled()
            "decodeBitmap"
        }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> =
        CallbackToFutureAdapter.getFuture { completer ->
            // 在线歌曲的封面是音源给的一个 http 地址，只能现下载；
            // 本地 / WebDAV 的封面走 contentResolver 或内嵌标签。
            val bitmap = when (uri.scheme) {
                "http", "https" -> decodeRemote(uri.toString(), 512)
                else -> decodeFile(context, uri) ?: ArtworkLoader.bitmap(context, uri, 512)
            }
            if (bitmap != null) completer.set(bitmap) else completer.setCancelled()
            "loadBitmap"
        }

    /** 在线封面。图片不大，直接整张读进来再采样解码。 */
    private fun decodeRemote(url: String, targetPx: Int): Bitmap? = runCatching {
        val conn = java.net.URL(url).openConnection().apply {
            connectTimeout = 10_000
            readTimeout = 15_000
        }
        conn.getInputStream().use { stream ->
            val bytes = stream.readBytes()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return@runCatching null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetPx)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
    }.getOrNull()

    private fun decodeFile(context: Context, uri: Uri): Bitmap? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = stream.readBytes()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return@use null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, 512)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }
    }.getOrNull()

    private fun sampleSize(width: Int, height: Int, targetPx: Int): Int {
        val maxEdge = maxOf(width, height)
        if (maxEdge <= targetPx) return 1
        var sample = 1
        while (maxEdge / (sample * 2) >= targetPx) sample *= 2
        return sample
    }
}
