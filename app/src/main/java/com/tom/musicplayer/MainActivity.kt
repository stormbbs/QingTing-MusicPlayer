package com.tom.musicplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tom.musicplayer.ui.AppRoot
import com.tom.musicplayer.ui.theme.MusicPlayerTheme
import com.tom.musicplayer.util.CrashLogger

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLogger.install(this)
        enableEdgeToEdge()
        setContent {
            MusicPlayerTheme {
                AppRoot()
            }
        }
    }
}
