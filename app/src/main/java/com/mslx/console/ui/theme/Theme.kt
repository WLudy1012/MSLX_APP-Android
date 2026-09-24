package com.mslx.console.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import com.mslx.console.data.ThemeMode

data class ThemeConfig(
    val mode: ThemeMode = ThemeMode.SEED,
    val seedColor: Long = DEFAULT_SEED_COLOR,
    /** 玻璃面板不透明度（0.25-1.0，1.0 为不透明）。 */
    val glassAlpha: Float = DEFAULT_GLASS_ALPHA,
    /** 浅色模式自定义背景图绝对路径；空串表示未设置。 */
    val lightBackground: String = "",
    /** 深色模式自定义背景图绝对路径；空串表示未设置。 */
    val darkBackground: String = "",
)

data class PresetColor(val name: String, val argb: Long)

/**
 * 默认主题色：硫磺史莱姆黄绿（与 Launcher 图标同一色系）。
 * 历史默认色（青蓝 0xFF00838F）在 [com.mslx.console.data.SettingsStore] 里按「未自定义」升级到本值。
 */
const val DEFAULT_SEED_COLOR = 0xFF9FA83A

/** 玻璃面板默认不透明度：轻微透出背景，兼顾内容可读性。 */
const val DEFAULT_GLASS_ALPHA = 0.78f

/** 设置页可选的预设种子色（首项为当前品牌默认色）。 */
val PresetColors = listOf(
    PresetColor("硫磺史莱姆", DEFAULT_SEED_COLOR),
    PresetColor("青蓝", 0xFF00838F),
    PresetColor("海洋蓝", 0xFF1E88E5),
    PresetColor("森林绿", 0xFF43A047),
    PresetColor("紫罗兰", 0xFF7B1FA2),
    PresetColor("珊瑚红", 0xFFE53935),
    PresetColor("活力橙", 0xFFF57C00),
    PresetColor("咖啡棕", 0xFF6D4C41),
    PresetColor("蓝灰", 0xFF546E7A),
)

private fun Color.lighten(fraction: Float) = lerp(this, Color.White, fraction.coerceIn(0f, 1f))
private fun Color.darken(fraction: Float) = lerp(this, Color.Black, fraction.coerceIn(0f, 1f))
private fun Color.desaturate(fraction: Float) = lerp(this, Color(0xFF808080), fraction.coerceIn(0f, 1f))

/**
 * 基于种子色生成一套完整、协调的 Material3 配色。
 * 所有容器色 / 表面色都从种子色派生，保证整体和谐统一。
 */
private fun seedColorScheme(seed: Color, dark: Boolean): ColorScheme {
    return if (dark) {
        val primary = seed.lighten(0.18f)
        darkColorScheme(
            primary = primary,
            onPrimary = seed.darken(0.72f),
            primaryContainer = seed.darken(0.36f),
            onPrimaryContainer = seed.lighten(0.88f),
            inversePrimary = seed.darken(0.08f),
            secondary = seed.desaturate(0.35f).lighten(0.16f),
            onSecondary = seed.darken(0.7f),
            secondaryContainer = seed.desaturate(0.42f).darken(0.28f),
            onSecondaryContainer = seed.desaturate(0.2f).lighten(0.86f),
            tertiary = seed.lighten(0.30f),
            onTertiary = seed.darken(0.7f),
            tertiaryContainer = seed.darken(0.24f),
            onTertiaryContainer = seed.lighten(0.85f),
            background = seed.darken(0.93f),
            onBackground = Color(0xFFE1E3E6),
            surface = seed.darken(0.92f),
            onSurface = Color(0xFFE1E3E6),
            surfaceVariant = seed.desaturate(0.42f).darken(0.5f),
            onSurfaceVariant = seed.lighten(0.62f),
            surfaceTint = primary,
            outline = seed.desaturate(0.5f).lighten(0.45f),
            outlineVariant = seed.desaturate(0.5f).darken(0.22f),
        )
    } else {
        lightColorScheme(
            primary = seed,
            onPrimary = Color.White,
            primaryContainer = seed.lighten(0.86f),
            onPrimaryContainer = seed.darken(0.62f),
            inversePrimary = seed.lighten(0.36f),
            secondary = seed.desaturate(0.42f),
            onSecondary = Color.White,
            secondaryContainer = seed.desaturate(0.3f).lighten(0.86f),
            onSecondaryContainer = seed.desaturate(0.3f).darken(0.55f),
            tertiary = seed.darken(0.12f),
            onTertiary = Color.White,
            tertiaryContainer = seed.lighten(0.7f),
            onTertiaryContainer = seed.darken(0.52f),
            background = seed.lighten(0.97f),
            onBackground = Color(0xFF1A1C1E),
            surface = seed.lighten(0.98f),
            onSurface = Color(0xFF1A1C1E),
            surfaceVariant = seed.desaturate(0.55f).lighten(0.86f),
            onSurfaceVariant = seed.darken(0.45f),
            surfaceTint = seed,
            outline = seed.desaturate(0.5f).darken(0.15f),
            outlineVariant = seed.desaturate(0.5f).lighten(0.78f),
        )
    }
}

@Composable
fun MSLXConsoleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeConfig: ThemeConfig = ThemeConfig(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        // Android 12+ 动态取色(Monet)
        themeConfig.mode == ThemeMode.DYNAMIC && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> seedColorScheme(Color(themeConfig.seedColor), dark = true)
        else -> seedColorScheme(Color(themeConfig.seedColor), dark = false)
    }
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
