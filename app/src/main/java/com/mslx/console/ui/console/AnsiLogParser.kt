package com.mslx.console.ui.console

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/**
 * 服务器日志 ANSI 原彩解析。
 *
 * 守护进程以 `-Dterminal.jline=false -Dterminal.ansi=true` 启动服务端时，
 * `ReceiveLog` 推送的是带 ANSI 转义序列（`\x1b[...m`）的原始日志；
 * 本解析器把转义序列转换为 Compose 颜色/字重，实现终端原彩显示。
 * 不支持/未识别的序列按重置或忽略处理，绝不把转义字符显示到界面。
 */

/** 一段已着色日志片段。color 为 null 表示继承默认控制台前景色。 */
data class AnsiSegment(
    val text: String,
    val color: Color? = null,
    val bold: Boolean = false,
)

private val ANSI_ESCAPE_REGEX = Regex("\u001B\\[[0-9;]*m")

/** 解析单行日志，返回带颜色的片段列表；无 ANSI 序列时返回单段原文。 */
fun parseAnsiLog(line: String): List<AnsiSegment> {
    if (line.isBlank()) return listOf(AnsiSegment(line))
    val matches = ANSI_ESCAPE_REGEX.findAll(line).toList()
    if (matches.isEmpty()) return listOf(AnsiSegment(line))

    val segments = mutableListOf<AnsiSegment>()
    var cursor = 0
    var currentColor: Color? = null
    var currentBold = false

    fun appendSegment(end: Int) {
        if (end > cursor) {
            segments += AnsiSegment(line.substring(cursor, end), currentColor, currentBold)
        }
        cursor = end
    }

    for (match in matches) {
        appendSegment(match.range.first)
        val params = match.value
            .substringAfter('\u001B')
            .removePrefix("[")
            .removeSuffix("m")
            .split(';')
            .mapNotNull { it.trim().toIntOrNull() }
        // 空参数（ESC[m）等价于重置
        if (params.isEmpty()) {
            currentColor = null
            currentBold = false
        }
        params.forEach { code ->
            when (code) {
                0 -> {
                    currentColor = null
                    currentBold = false
                }
                1 -> currentBold = true
                22 -> currentBold = false
                39 -> currentColor = null
                in 30..37 -> currentColor = ansiColor(code - 30, bright = false)
                in 90..97 -> currentColor = ansiColor(code - 90, bright = true)
                // 背景色 40-47 / 100-107 与其余控制码：忽略
                else -> Unit
            }
        }
        cursor = match.range.last + 1
    }
    appendSegment(line.length)
    return segments.filter { it.text.isNotEmpty() }
}

/** 标准 16 色 ANSI 调色板（适配深色控制台背景）。 */
private fun ansiColor(index: Int, bright: Boolean): Color = when (index) {
    0 -> if (bright) Color(0xFFBFBFBF) else Color(0xFF7F7F7F) // 黑/灰
    1 -> if (bright) Color(0xFFFF8A80) else Color(0xFFEF5350) // 红
    2 -> if (bright) Color(0xFFB9F6CA) else Color(0xFF66BB6A) // 绿
    3 -> if (bright) Color(0xFFFFFF59) else Color(0xFFFFD54F) // 黄
    4 -> if (bright) Color(0xFF82B1FF) else Color(0xFF42A5F5) // 蓝
    5 -> if (bright) Color(0xFFEA80FC) else Color(0xFFAB47BC) // 品红
    6 -> if (bright) Color(0xFF80DEEA) else Color(0xFF26C6DA) // 青
    7 -> if (bright) Color(0xFFFFFFFF) else Color(0xFFE0E0E0) // 白
    else -> Color.Unspecified
}

/** 加粗字重。 */
val AnsiSegment.ansiFontWeight: FontWeight
    get() = if (bold) FontWeight.Bold else FontWeight.Normal
