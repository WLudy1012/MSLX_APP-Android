package com.mslx.console.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 顶层页面标识，用于底部导航高亮。 */
enum class TopPage(val label: String, val icon: ImageVector) {
    HOME("主页", Icons.Filled.Home),
    INSTANCES("实例", Icons.AutoMirrored.Filled.List),
    NEW_INSTANCE("新建", Icons.Filled.Add),
    SETTINGS("设置", Icons.Filled.Settings),
}

/** 四个顶层页共享的底部导航栏（Dock）：选中项图标弹跳缩放 + 指示 pill 淡入 + 颜色过渡。 */
@Composable
fun MainBottomNav(
    current: TopPage,
    onNavigate: (TopPage) -> Unit,
) {
    // 条目高度固定 64dp（不用 fillMaxHeight：当前 M3 的 NavigationBar 内层 Row 高度由子项决定，
    // fillMaxHeight 会把 Dock 撑满父容器；手势导航条的 inset 由 NavigationBar 自行叠加在内容高度之上）
    NavigationBar {
        TopPage.entries.forEach { page ->
            DockItem(
                page = page,
                selected = page == current,
                onNavigate = onNavigate,
            )
        }
    }
}

@Composable
private fun RowScope.DockItem(
    page: TopPage,
    selected: Boolean,
    onNavigate: (TopPage) -> Unit,
) {
    // 按压交互源：去掉默认矩形 ripple（点击时整块 item 会闪黑框），改为图标缩放/变淡反馈
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // 图标弹性缩放（选中弹跳，按压时轻微缩小）
    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.92f
            selected -> 1.22f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "dockIconScale",
    )
    // 指示 pill 淡入淡出
    val indicatorAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "dockIndicatorAlpha",
    )
    // 图标与文字颜色过渡
    val contentColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(durationMillis = 220),
        label = "dockContentColor",
    )

    Box(
        modifier = Modifier
            .height(64.dp)
            .weight(1f)
            // indication=null：不绘制默认 ripple 矩形框，按压反馈走图标缩放/变淡
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = { onNavigate(page) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                // 图标背后的圆角指示条
                Box(
                    modifier = Modifier
                        .size(width = 56.dp, height = 30.dp)
                        .graphicsLayer { alpha = indicatorAlpha }
                        .clip(RoundedCornerShape(15.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                )
                Icon(
                    imageVector = page.icon,
                    contentDescription = page.label,
                    tint = if (isPressed) contentColor.copy(alpha = 0.6f) else contentColor,
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = page.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = contentColor,
            )
        }
    }
}
