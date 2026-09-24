package com.mslx.console.ui.splash

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mslx.console.R
import com.mslx.console.ui.theme.BrandSulfur
import kotlinx.coroutines.delay

/**
 * 应用内品牌开屏动画(承接系统 SplashScreen 的硫磺史莱姆黄绿背景，
 * 图标淡入 + 弹性缩放 + 应用名浮现)。
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    var started by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (started) 1f else 0.6f,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "logoScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = 550),
        label = "logoAlpha",
    )

    LaunchedEffect(Unit) {
        started = true
        delay(1600)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandSulfur),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.slime_logo),
                contentDescription = "MSLX",
                modifier = Modifier
                    .size(144.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    },
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = "MSLX 控制台",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = alpha),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Minecraft Server Launcher X",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f * alpha),
            )
        }
    }
}
