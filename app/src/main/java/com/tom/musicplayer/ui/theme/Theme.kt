package com.tom.musicplayer.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = MintPrimary,
    onPrimary = MintOnPrimary,
    primaryContainer = MintContainer,
    onPrimaryContainer = MintOnContainer,
    secondary = SkyAccent,
    onSecondary = MintOnPrimary,
    secondaryContainer = SkyContainer,
    onSecondaryContainer = SkyOnContainer,
    background = AppBackground,
    onBackground = AppTextPrimary,
    surface = AppSurface,
    onSurface = AppTextPrimary,
    surfaceVariant = AppSurfaceVariant,
    onSurfaceVariant = AppTextSecondary,
    outline = AppOutline,
    outlineVariant = AppOutlineVariant
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6FDCB4),
    onPrimary = Color(0xFF003828),
    primaryContainer = Color(0xFF0B3B2E),
    onPrimaryContainer = Color(0xFFBDF2DF),
    secondary = Color(0xFF9DBBFF),
    onSecondary = Color(0xFF0B2A56),
    background = Color(0xFF101715),
    onBackground = Color(0xFFE3EDE9),
    surface = Color(0xFF16201D),
    onSurface = Color(0xFFE3EDE9),
    surfaceVariant = Color(0xFF1E2A26),
    onSurfaceVariant = Color(0xFFB4C4BE)
)

@Composable
fun MusicPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val seed = DynamicSeed.state.value
    val target = rememberSeedScheme(seed, darkTheme)
    MaterialTheme(
        colorScheme = target.animated(),
        typography = Typography(),
        content = content
    )
}

/**
 * 只把「主色系」换成封面取色，背景和文字层级保持原来的清新配色。
 * 直接整体套用 seed 会让背景也跟着变花，那反而不耐看。
 */
@Composable
private fun rememberSeedScheme(seed: Color?, darkTheme: Boolean): ColorScheme {
    val base = if (darkTheme) DarkColors else LightColors
    if (seed == null) return base
    val dynamic = if (darkTheme) {
        darkColorScheme(primary = seed, secondary = seed, tertiary = seed)
    } else {
        lightColorScheme(primary = seed, secondary = seed, tertiary = seed)
    }
    return base.copy(
        primary = dynamic.primary,
        onPrimary = dynamic.onPrimary,
        primaryContainer = dynamic.primaryContainer,
        onPrimaryContainer = dynamic.onPrimaryContainer,
        secondary = dynamic.secondary,
        onSecondary = dynamic.onSecondary,
        secondaryContainer = dynamic.secondaryContainer,
        onSecondaryContainer = dynamic.onSecondaryContainer
    )
}

/** 切歌时颜色平滑过渡，避免整个界面一闪。 */
@Composable
private fun ColorScheme.animated(): ColorScheme {
    val duration = 500
    return copy(
        primary = animateColorAsState(primary, tween(duration), label = "primary").value,
        onPrimary = animateColorAsState(onPrimary, tween(duration), label = "onPrimary").value,
        primaryContainer = animateColorAsState(
            primaryContainer, tween(duration), label = "primaryContainer"
        ).value,
        onPrimaryContainer = animateColorAsState(
            onPrimaryContainer, tween(duration), label = "onPrimaryContainer"
        ).value,
        secondary = animateColorAsState(secondary, tween(duration), label = "secondary").value,
        onSecondary = animateColorAsState(onSecondary, tween(duration), label = "onSecondary").value,
        secondaryContainer = animateColorAsState(
            secondaryContainer, tween(duration), label = "secondaryContainer"
        ).value,
        onSecondaryContainer = animateColorAsState(
            onSecondaryContainer, tween(duration), label = "onSecondaryContainer"
        ).value
    )
}
