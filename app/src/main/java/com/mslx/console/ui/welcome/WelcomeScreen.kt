package com.mslx.console.ui.welcome

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mslx.console.MSLXApplication
import com.mslx.console.R
import kotlinx.coroutines.launch

/** 首次启动欢迎页的能力清单（与 README、设置页口径一致：远程管理 + 本机开服）。 */
private val features = listOf(
    "连接你自己的 MSLX 守护程序（Daemon），随时管理服务器实例",
    "多台 Daemon 平级共存，所有入口按实例寻址，不再区分主连接",
    "实时控制台：日志保留原彩 ANSI，手机也能发命令",
    "不想连电脑？也可以在手机本机开服（公共或应用私有目录）",
    "应用内检查更新：稳定版 / Beta / Actions 调试构建三个渠道",
)

/** 首次启动欢迎页。 */
@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Scaffold(
        // 页面透明：透出全局毛玻璃背景层
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.slime_logo),
                contentDescription = "MSLX",
                modifier = Modifier.size(164.dp),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = "欢迎使用 MSLX 控制台",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "在手机上远程管理 Minecraft 服务器，或直接在本机开服",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))

            features.forEach { feature ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        text = feature,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
            Button(
                onClick = {
                    val app = context.applicationContext as MSLXApplication
                    scope.launch { app.container.settingsStore.markOnboarded() }
                    onStart()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text("开始使用", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(14.dp))
            // 第三方身份与许可指引（与开屏免责声明、关于页同一口径）
            Text(
                text = "本应用是独立第三方项目，与 MSLTeam 无隶属关系；" +
                    "使用前请阅读首次弹出的「第三方免责声明」，" +
                    "组件许可见「设置 → 关于 → 合规与许可」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
