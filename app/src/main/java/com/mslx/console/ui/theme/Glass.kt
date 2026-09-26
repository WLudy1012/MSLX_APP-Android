package com.mslx.console.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 毛玻璃档位（按系统版本自动判定）：
 * - [NONE]：Android 13 (API 33) 以下不可用；
 * - [BASIC]：Android 13–16（API 33–36）基础档 —— 降采样静态模糊背景 + 半透明面板 + 描边；
 * - [FULL]：Android 17+（API 37+）全量档 —— 高质量静态模糊背景 + 高光层。
 *
 * 注：compileSdk 35 没有 API 37 常量，系统版本比较直接用数字。
 */
enum class GlassLevel(val label: String, val detail: String) {
    NONE("不可用", "需要 Android 13（API 33）及以上系统"),
    BASIC("基础档", "静态模糊背景 + 半透明面板 + 描边"),
    FULL("全量档", "实时模糊 + 高光层 + 过渡增强"),
}

/** 当前设备实际生效的毛玻璃档位（系统版本固定，无需 recomposition 监听）。 */
fun currentGlassLevel(): GlassLevel = when {
    Build.VERSION.SDK_INT < 33 -> GlassLevel.NONE
    Build.VERSION.SDK_INT >= 37 -> GlassLevel.FULL
    else -> GlassLevel.BASIC
}

/** 玻璃面板的不透明度；由 MainActivity 按用户设置提供，未提供时按不透明处理。 */
val LocalGlassAlpha = staticCompositionLocalOf { 1f }

/**
 * 页面背景层：铺满可用空间，自下而上由 主题色柔和渐变 → 自定义背景图（按档位模糊）→ 全量档高光 组成。
 * 未设置背景图时只保留渐变，保证任何档位都有协调底色。
 */
@Composable
fun GlassBackground(
    config: ThemeConfig,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkTheme()
    val level = remember { currentGlassLevel() }
    val path = if (dark) config.darkBackground else config.lightBackground
    val bitmap by rememberGlassBitmap(path = path, level = level)

    Box(modifier = modifier.fillMaxSize()) {
        val scheme = MaterialTheme.colorScheme
        // 1) 柔和渐变底：让面板即使没有背景图也有层次
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            scheme.primary.copy(alpha = 0.16f),
                            scheme.background,
                            scheme.tertiary.copy(alpha = 0.10f),
                        ),
                    ),
                ),
        )
        // 2) 自定义背景图（按档位处理模糊）
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // 自定义背景先压暗，避免高亮图片穿透面板后吞掉正文对比度。
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        if (dark) Color.Black.copy(alpha = 0.30f)
                        else Color.White.copy(alpha = 0.06f),
                    ),
            )
        }
        // 3) 全量档额外高光层：左上大范围柔光，增强玻璃质感
        if (level == GlassLevel.FULL && bitmap != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.14f),
                                Color.Transparent,
                            ),
                            radius = 1400f,
                        ),
                    ),
            )
        }
    }
}

/**
 * 玻璃面板：半透明表面色 + 细描边；全量档附顶部高光。
 * 面板之外的内容（页面 Scaffold）应保持透明，才能透出其后的 [GlassBackground]。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    alpha: Float = LocalGlassAlpha.current,
    content: @Composable BoxScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val level = remember { currentGlassLevel() }
    Box(
        modifier = modifier
            .clip(shape)
            .background(scheme.surface.copy(alpha = alpha.coerceIn(0f, 1f)))
            .border(1.dp, scheme.outlineVariant.copy(alpha = 0.45f), shape),
    ) {
        if (level == GlassLevel.FULL) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = 0.09f),
                            0.4f to Color.Transparent,
                        ),
                    ),
            )
        }
        content()
    }
}

/** 背景图描边圆角（与面板一致的视觉语言可用）。 */
val GlassCardShape = RoundedCornerShape(20.dp)

/** 面板描边宽度。 */
val GlassBorderWidth: Dp = 1.dp

/**
 * 记忆化加载背景图：
 * - 全量档：解码到长边 ≤ 960，后台预处理为静态模糊图，避免每帧执行全屏 GPU 模糊；
 * - 基础档：解码后降采样到长边 ≤ 200 并做两轮箱式模糊，作为静态模糊背景。
 */
// 当前 Compose/Lint 组合误报：下方 producer 已在加载完成后明确写入 value，仅在此处抑制。
@Suppress("ProduceStateDoesNotAssignValue")
@Composable
private fun rememberGlassBitmap(path: String, level: GlassLevel) = produceState<ImageBitmap?>(
    initialValue = null,
    path,
    level,
) {
    value = if (path.isBlank() || level == GlassLevel.NONE) {
        null
    } else {
        withContext(Dispatchers.IO) { loadGlassBitmap(path, level) }
    }
}

private fun loadGlassBitmap(path: String, level: GlassLevel): ImageBitmap? {
    return try {
        val file = File(path)
        if (!file.exists()) return null
        val maxSide = if (level == GlassLevel.FULL) 960 else 480
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxSide)
        }
        val decoded = BitmapFactory.decodeFile(path, opts) ?: return null
        val prepared = downsampleAndBlur(decoded, if (level == GlassLevel.FULL) 480 else 200)
        prepared.asImageBitmap()
    } catch (_: Throwable) {
        null
    }
}

private fun sampleSizeFor(width: Int, height: Int, maxSide: Int): Int {
    var sample = 1
    var longest = max(width, height)
    while (longest / 2 >= maxSide) {
        longest /= 2
        sample *= 2
    }
    return sample
}

/** 降采样到目标长边 + 两轮 3x3 箱式模糊（静态模糊背景，基础档使用）。 */
private fun downsampleAndBlur(src: Bitmap, targetSide: Int): Bitmap {
    val scale = targetSide.toFloat() / max(src.width, src.height)
    val w = max(1, (src.width * scale).roundToInt())
    val h = max(1, (src.height * scale).roundToInt())
    val small = Bitmap.createScaledBitmap(src, w, h, true)
    var current = small
    repeat(2) { current = boxBlur3x3(current) }
    return current
}

private fun boxBlur3x3(src: Bitmap): Bitmap {
    val w = src.width
    val h = src.height
    if (w < 3 || h < 3) return src
    val pixels = IntArray(w * h)
    src.getPixels(pixels, 0, w, 0, 0, w, h)
    val out = IntArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            var a = 0; var r = 0; var g = 0; var b = 0; var n = 0
            for (dy in -1..1) {
                val ny = y + dy
                if (ny < 0 || ny >= h) continue
                for (dx in -1..1) {
                    val nx = x + dx
                    if (nx < 0 || nx >= w) continue
                    val p = pixels[ny * w + nx]
                    a += (p ushr 24) and 0xFF
                    r += (p ushr 16) and 0xFF
                    g += (p ushr 8) and 0xFF
                    b += p and 0xFF
                    n++
                }
            }
            out[y * w + x] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
        }
    }
    return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
}
