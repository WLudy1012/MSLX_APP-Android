package com.wludy.rolithax.launcher.ui.welcome

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.wludy.rolithax.launcher.RolithaxApplication
import com.wludy.rolithax.launcher.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class GuideStep(
    val title: String,
    val description: String,
    val hint: String,
    val icon: ImageVector,
)

private val guideSteps = listOf(
    GuideStep(
        title = "连接你的 Daemon",
        description = "从主页打开连接入口，可以扫描 MSLX 配对二维码，也可以手动填写地址和 API Key。",
        hint = "还没有远程 Daemon？可以直接使用手机本机开服。",
        icon = Icons.Filled.Info,
    ),
    GuideStep(
        title = "创建远程或本机服务端",
        description = "点击底部 Dock 的“新建”，选择运行位置，再按向导选择服务端核心、版本和 Java。",
        hint = "本机实例不需要连接远程 Daemon。",
        icon = Icons.Filled.Add,
    ),
    GuideStep(
        title = "在实例页管理服务端",
        description = "实例页汇总本机和 Daemon 上的实例。打开实例卡片即可进入控制台查看日志、发送命令。",
        hint = "列表中的来源信息可以帮助你区分实例所在位置。",
        icon = Icons.AutoMirrored.Filled.List,
    ),
    GuideStep(
        title = "按习惯调整设置",
        description = "你可以在设置中调整外观、管理 Daemon 连接并检查应用更新。",
        hint = "第三方免责声明与组件许可可在设置中的关于页面查看。",
        icon = Icons.Filled.Settings,
    ),
)

@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as RolithaxApplication }
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { guideSteps.size })
    var finishing by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    val currentPage = pagerState.currentPage

    fun finishGuide() {
        if (finishing) return
        finishing = true
        saveFailed = false
        scope.launch {
            try {
                app.container.settingsStore.markOnboarded()
                onStart()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                finishing = false
                saveFailed = true
            }
        }
    }

    Scaffold(containerColor = Color.Transparent) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.slime_logo),
                    contentDescription = "MSLX",
                    modifier = Modifier.size(42.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "首次使用引导",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "快速了解 Rolithax Launcher",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = ::finishGuide, enabled = !finishing) {
                    Text("跳过")
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                key = { it },
            ) { page ->
                GuideStepCard(guideSteps[page], page + 1)
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    guideSteps.indices.forEach { index ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (index <= currentPage) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                ),
                        )
                    }
                }
                Text(
                    text = "第" + (currentPage + 1) + "步，共" + guideSteps.size + "步",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { scope.launch { pagerState.animateScrollToPage(currentPage - 1) } },
                    enabled = currentPage > 0 && !finishing,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    Text("上一步")
                }
                Button(
                    onClick = {
                        if (currentPage == guideSteps.lastIndex) {
                            finishGuide()
                        } else {
                            scope.launch { pagerState.animateScrollToPage(currentPage + 1) }
                        }
                    },
                    enabled = !finishing,
                    modifier = Modifier.weight(1f).height(52.dp),
                ) {
                    if (finishing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(if (currentPage == guideSteps.lastIndex) "开始使用" else "下一步")
                    }
                }
            }

            if (saveFailed) {
                Text(
                    text = "未能保存引导状态，请重试。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            Text(
                text = "使用前仍需阅读并接受第三方免责声明。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun GuideStepCard(step: GuideStep, index: Int) {
    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier.size(108.dp),
                shape = RoundedCornerShape(34.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = step.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = "0" + index,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = step.title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = step.description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(22.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
            ) {
                Text(
                    text = step.hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}
