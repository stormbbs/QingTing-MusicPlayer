package com.tom.musicplayer

import android.app.Application
import com.tom.musicplayer.data.online.Downloader
import com.tom.musicplayer.util.CrashLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 崩溃捕获放在 Application 而不是 Activity：
 * 播放服务有可能先于界面被系统拉起（恢复播放、通知栏控制），
 * 那种情况下 Activity 还没创建，崩溃就没人记录，事后无从查起。
 */
class MusicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
        // 旧版本把歌存在 App 私有目录里，卸载就没了。升级后搬到公共下载目录，
        // 放后台跑：几十上百兆的复制不该卡住启动。
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { Downloader.migrateLegacy(this@MusicApp) }
            // 卸载重装后私有记录没了，但公共目录里的歌还在，按目录把列表补回来
            runCatching { Downloader.rebuildIndex(this@MusicApp) }
            // 补完记录再算一次「哪些已经下过」，播放页的下载按钮一开始就是对的
            runCatching { Downloader.refreshDownloadedKeys(this@MusicApp) }
            // 老版本的收藏只存了 id，趁这里有曲库缓存和下载记录，把能补的信息补上，
            // 否则「我点心的歌曲」里那些歌没有歌名、也播不了
            runCatching {
                val songs = com.tom.musicplayer.data.LibraryCache.load(this@MusicApp) +
                    com.tom.musicplayer.data.online.DownloadStore.load(this@MusicApp)
                        .mapNotNull { com.tom.musicplayer.data.online.DownloadStore.toSong(this@MusicApp, it) }
                com.tom.musicplayer.data.FavoritesStore.enrich(this@MusicApp, songs)
            }
            // 自动检查更新：只在用户开着这个开关时做，失败了也绝不打扰
            runCatching { com.tom.musicplayer.data.update.UpdateCenter.autoCheck(this@MusicApp) }
        }
    }
}
