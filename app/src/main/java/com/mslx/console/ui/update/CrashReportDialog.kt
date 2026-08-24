package com.mslx.console.ui.update

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mslx.console.data.AppLogger

/** GitHub Issue 提交地址（崩溃日志报告入口）。 */
const val GITHUB_ISSUE_URL = "https://github.com/WLudy1012/MSLX_APP-Android/issues/new"

/**
 * 崩溃报告弹窗：应用启动时若检测到上次会话发生未捕获异常，
 * 展示崩溃日志 + GitHub Issue 地址，提示用户提交 issue。
 */
@Composable
fun CrashReportDialog() {
    var crash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        crash = AppLogger.readLastCrash()
    }

    val content = crash ?: return
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val dismiss = {
        AppLogger.markCrashHandled()
        crash = null
    }

    AlertDialog(
        onDismissRequest = dismiss,
        title = {
            Text(
                text = "检测到上次运行崩溃",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = "应用上次运行时发生了异常。建议将以下信息提交到 GitHub Issue，帮助我们修复问题。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Issue 地址：$GITHUB_ISSUE_URL",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { openIssue(context) }) {
                Text("提交 Issue", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(content))
                dismiss()
            }) { Text("复制日志") }
        },
    )
}

private fun openIssue(context: android.content.Context) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_ISSUE_URL)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
