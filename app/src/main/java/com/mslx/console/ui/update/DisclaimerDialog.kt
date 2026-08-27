package com.mslx.console.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mslx.console.data.AppSettings
import kotlinx.coroutines.delay

/** 免责协议全文（与仓库根目录 DISCLAIMER.txt 保持一致）。 */
private val DISCLAIMER_TEXT = """
一、第三方项目声明

本软件(下称"本应用")是第三方开发者为 MSLX 项目(https://github.com/MSLTeam/MSLX)及其附属项目
(统称"MSLX")制作的独立第三方应用。本应用并非 MSLX 官方发布,亦非 MSLTeam 的官方产品。

依据 MSLTeam 审议要求,凡使用 MSLX 及其附属项目名称命名的第三方项目,必须满足以下两点:

1. 显式声明本应用为第三方项目,并确认本应用与 MSLTeam 之间不存在任何责任关系、
   关联关系、代理关系或隶属关系。
2. 因使用本应用而产生的一切后果,由本应用的使用者自行承担,与 MSLTeam 无关。

二、风险与责任

1. 本应用按"现状"(AS IS)提供,开发者不对本应用的适用性、可靠性、安全性作任何明示或暗示的担保。
2. 使用本应用管理 Minecraft 服务器实例时,可能涉及服务器数据修改、文件读写、进程启停等操作,
   由此造成的数据丢失、服务中断、系统损坏或其他损失,由使用者自行承担。
3. 本应用允许连接您自己的 MSLX 守护进程(MSLX Daemon)。请确保您拥有对该守护进程及其所管理
   资源的合法使用权限,并自行承担连接与操作带来的全部风险。
4. 在用户明确勾选"允许 HTTP"时,本应用会通过明文 HTTP 传输数据(含 API Key),仅建议在
   完全可信的内网环境使用;由此产生的被窃听、篡改等风险由使用者自行承担。

三、开源许可

本应用基于 GNU Affero General Public License v3.0 (AGPL-3.0) 开源,
源码见项目仓库 https://github.com/WLudy1012/MSLX_APP-Android。
上游 MSLX 项目同样基于 AGPL-3.0 开源,详见其项目仓库 https://github.com/MSLTeam/MSLX。

四、同意

点击"我已阅读并同意",即表示您已阅读、理解并同意本声明的全部内容。
""".trimIndent()

/**
 * 首次开屏免责协议弹窗：未同意时强制展示，5 秒后可点击确认。
 * 同意后持久化，之后不再弹出。
 */
@Composable
fun DisclaimerDialog(
    settings: AppSettings,
    onAccept: () -> Unit,
) {
    if (settings.disclaimerAccepted) return

    // 5 秒倒计时，结束后才允许确认
    var countdown by remember { mutableIntStateOf(5) }
    LaunchedEffect(Unit) {
        while (countdown > 0) {
            delay(1000)
            countdown--
        }
    }

    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(
                text = "第三方免责声明",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = DISCLAIMER_TEXT,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "请仔细阅读以上声明。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onAccept,
                enabled = countdown <= 0,
            ) {
                Text(
                    if (countdown > 0) "请等待 $countdown 秒" else "我已阅读并同意",
                    color = if (countdown > 0) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
    )
}
