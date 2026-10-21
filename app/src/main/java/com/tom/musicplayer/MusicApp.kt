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
        }
    }
}
