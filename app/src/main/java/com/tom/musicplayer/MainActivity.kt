package com.tom.musicplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tom.musicplayer.ui.AppRoot
import com.tom.musicplayer.ui.theme.MusicPlayerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // CrashLogger 已在 MusicApp 里装好：Application 一定先于 Activity 创建，
        // 这里再装一次只会把处理器再包一层，没有额外作用。
        enableEdgeToEdge()
        setContent {
            MusicPlayerTheme {
                AppRoot()
            }
        }
    }
}
