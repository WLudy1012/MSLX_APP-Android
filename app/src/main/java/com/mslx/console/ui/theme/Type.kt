package com.mslx.console.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Typography = Typography().copy(
    displaySmall = Typography().displaySmall.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = Typography().headlineSmall.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = Typography().titleLarge.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.1).sp,
    ),
    titleMedium = Typography().titleMedium.copy(
        fontWeight = FontWeight.Medium,
    ),
    bodyLarge = Typography().bodyLarge.copy(
        lineHeight = 24.sp,
    ),
    bodyMedium = Typography().bodyMedium.copy(
        lineHeight = 21.sp,
    ),
    labelLarge = Typography().labelLarge.copy(
        fontWeight = FontWeight.SemiBold,
    ),
)

val ConsoleFont = FontFamily.Monospace

val ConsoleTextStyle = TextStyle(
    fontFamily = ConsoleFont,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    fontWeight = FontWeight.Normal,
)
