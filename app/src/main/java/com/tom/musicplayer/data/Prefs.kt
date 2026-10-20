package com.tom.musicplayer.data

import android.content.Context
import android.net.Uri
import com.tom.musicplayer.player.PlayMode

object Prefs {
    private const val FILE = "music_player_prefs"
    private const val KEY_TREE_URI = "tree_uri"
    private const val KEY_MODE = "play_mode"
    private const val KEY_LAST_ID = "last_song_id"
    private const val KEY_LAST_POSITION = "last_position"
    private const val KEY_WAS_PLAYING = "was_playing"
    private const val KEY_QUALITY = "online_quality"
    private const val KEY_DOWNLOAD_TARGET = "download_target"

    private fun sp(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 在线音质：standard / high / lossless。播放和下载都用这一档。 */
    fun saveQuality(context: Context, quality: String) {
        if (sp(context).getString(KEY_QUALITY, null) == quality) return
        sp(context).edit().putString(KEY_QUALITY, quality).apply()
    }

    fun getQuality(context: Context): String = sp(context).getString(KEY_QUALITY, "high") ?: "high"

    /**
     * 下载落到哪：`app` = App 私有目录（「我的下载」页管理），
     * `saf` = 用户授权的音乐文件夹（下载完直接被现有扫描收录，和本地歌混在一起）。
     */
    fun saveDownloadTarget(context: Context, target: String) {
        if (sp(context).getString(KEY_DOWNLOAD_TARGET, null) == target) return
        sp(context).edit().putString(KEY_DOWNLOAD_TARGET, target).apply()
    }

    fun getDownloadTarget(context: Context): String =
        sp(context).getString(KEY_DOWNLOAD_TARGET, "app") ?: "app"

    /**
     * 低频且关键的配置（选过的文件夹、播放模式）用 commit 同步落盘：
     * 用户选完文件夹马上杀进程也不该丢，反正一整天也就写几次。
     */
    fun saveTreeUri(context: Context, uri: Uri) {
        sp(context).edit().putString(KEY_TREE_URI, uri.toString()).commit()
    }

    fun getTreeUri(context: Context): Uri? {
        val value = sp(context).getString(KEY_TREE_URI, null) ?: return null
        return runCatching { Uri.parse(value) }.getOrNull()
    }

    fun saveMode(context: Context, mode: PlayMode) {
        sp(context).edit().putString(KEY_MODE, mode.name).commit()
    }

    fun getMode(context: Context): PlayMode {
        val name = sp(context).getString(KEY_MODE, null) ?: return PlayMode.SEQUENTIAL
        return runCatching { PlayMode.valueOf(name) }.getOrDefault(PlayMode.SEQUENTIAL)
    }

    /**
     * 播放进度是「每几秒写一次」的高频字段，一律用 apply 异步落盘。
     *
     * 之前这里用的是 commit：它是同步的，会当场把整个 XML 重写一遍并 fsync。
     * 播放中每 2 秒就有两次这样的主线程磁盘写，长时间听下来会持续拖慢主线程，
     * 在存储较慢的机器上尤其明显。值没变时干脆不写，进一步减少无谓 I/O。
     */
    fun saveLastSongId(context: Context, id: String?) {
        val sp = sp(context)
        if (sp.getString(KEY_LAST_ID, null) == id) return
        sp.edit().putString(KEY_LAST_ID, id).apply()
    }

    fun getLastSongId(context: Context): String? = sp(context).getString(KEY_LAST_ID, null)

    fun saveLastPosition(context: Context, positionMs: Long) {
        val sp = sp(context)
        if (sp.getLong(KEY_LAST_POSITION, 0L) == positionMs) return
        sp.edit().putLong(KEY_LAST_POSITION, positionMs).apply()
    }

    fun getLastPosition(context: Context): Long =
        sp(context).getLong(KEY_LAST_POSITION, 0L).coerceAtLeast(0L)

    /**
     * 退出前是否正在播放。
     *
     * 服务被系统回收后会被重新拉起，但此时内存里的播放队列已经没了。
     * 靠这个标志才能区分「用户自己停的」和「系统杀掉的」——只有后者才该自动续播，
     * 否则用户明明按了暂停，重启服务后音乐自己响起来会很突然。
     */
    fun saveWasPlaying(context: Context, playing: Boolean) {
        val sp = sp(context)
        if (sp.getBoolean(KEY_WAS_PLAYING, false) == playing) return
        sp.edit().putBoolean(KEY_WAS_PLAYING, playing).commit()
    }

    fun getWasPlaying(context: Context): Boolean = sp(context).getBoolean(KEY_WAS_PLAYING, false)
}
