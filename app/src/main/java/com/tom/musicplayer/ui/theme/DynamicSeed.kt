package com.tom.musicplayer.ui.theme

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * 界面主色调：由当前播放歌曲的封面提取出来的种子色。
 *
 * 用 [mutableStateOf] 而不是 Flow，是为了让 Compose 能直接在重组里读到它，
 * 再配合主题里的 animateColorAsState 做平滑过渡，切歌时颜色是渐变而不是硬跳。
 */
object DynamicSeed {

    private var current = mutableStateOf<Color?>(null)

    fun update(color: Color?) {
        if (current.value == color) return
        current.value = color
    }

    fun reset() = update(null)

    val state: State<Color?> get() = current

    /** 从 Palette 取回来的色值转成 Compose 的 Color。 */
    fun fromArgb(argb: Int): Color = Color(argb)

    fun toArgb(color: Color): Int = color.toArgb()
}
