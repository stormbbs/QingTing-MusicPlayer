package com.tom.musicplayer

import android.app.Application
import com.tom.musicplayer.util.CrashLogger

/**
 * 崩溃捕获放在 Application 而不是 Activity：
 * 播放服务有可能先于界面被系统拉起（恢复播放、通知栏控制），
 * 那种情况下 Activity 还没创建，崩溃就没人记录，事后无从查起。
 */
class MusicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
    }
}
