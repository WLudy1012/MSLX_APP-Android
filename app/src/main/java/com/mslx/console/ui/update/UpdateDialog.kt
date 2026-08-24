package com.mslx.console.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mslx.console.data.AppUpdateInfo

/**
 * 全局更新弹窗宿主：挂载在导航根节点外层。
 * - 启动自动检查：发现新版本即弹窗；
 * - 设置页手动检查：结果也通过同一状态弹窗/提示。
 */
@Composable
fun UpdateHost(
    viewModel: UpdateViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 启动时自动检查一次（仅首次）
    LaunchedEffect(Unit) {
        viewModel.checkOnLaunch()
    }

    val update = state.update
    if (update != null) {
        UpdateDialog(
            currentVersion = state.currentVersion,
            update = update,
            onUpdate = { viewModel.openUpdate() },
            onSkip = { viewModel.skip() },
        )
    }
}

@Composable
private fun UpdateDialog(
    currentVersion: String,
    update: AppUpdateInfo,
    onUpdate: () -> Unit,
    onSkip: () -> Unit,
) {
    AlertDialog(
        // 强制更新时不可通过点击外部/返回键关闭，必须更新后才能继续使用
        onDismissRequest = { if (!update.forceUpdate) onSkip() },
        icon = {
            Icon(
                imageVector = Icons.Filled.Build,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                text = when {
                    update.forceUpdate -> "必须更新到 v${update.version}"
                    update.beta -> "发现测试版 v${update.version}"
                    else -> "发现新版本 v${update.version}"
                },
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
                if (update.forceUpdate) {
                    Text(
                        text = "此版本必须更新，否则无法继续使用。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    text = if (currentVersion.isBlank()) {
                        "更新内容："
                    } else {
                        "当前版本 v$currentVersion → 新版本 v${update.version}"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = update.notes.ifBlank { "暂无说明" },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (update.apkSize > 0) {
                    Text(
                        text = "APK 大小：${formatSize(update.apkSize)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onUpdate) {
                Text(if (update.forceUpdate) "立即更新" else "更新", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            if (!update.forceUpdate) {
                TextButton(onClick = onSkip) { Text("跳过") }
            }
        },
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
