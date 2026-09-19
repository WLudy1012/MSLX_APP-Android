package com.mslx.console.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mslx.console.data.AppUpdateInfo
import com.mslx.console.data.localengine.LocalJreManager

/**
 * 全局更新弹窗宿主：挂载在导航根节点外层。
 * - 启动自动检查：发现新版本即弹窗；
 * - 设置页手动检查：结果也通过同一状态弹窗/提示；
 * - 所有渠道（稳定/测试/Actions）均在应用内下载并安装。
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
        val context = LocalContext.current
        // 当前安装包是否自带内嵌 JRE（决定推荐完整版还是精简版）
        val currentHasEmbeddedJre = remember(context) {
            LocalJreManager.hasEmbeddedAsset(context, LocalJreManager.DEFAULT_RUNTIME)
        }
        UpdateDialog(
            currentVersion = state.currentVersion,
            update = update,
            currentHasEmbeddedJre = currentHasEmbeddedJre,
            downloading = state.downloadingActions,
            downloadProgress = state.downloadProgress,
            onInstall = { viewModel.downloadAndInstall() },
            onInstallLite = { viewModel.downloadAndInstall(lite = true) },
            onSkip = { viewModel.skip() },
        )
    }
}

@Composable
private fun UpdateDialog(
    currentVersion: String,
    update: AppUpdateInfo,
    currentHasEmbeddedJre: Boolean,
    downloading: Boolean,
    downloadProgress: Float,
    onInstall: () -> Unit,
    onInstallLite: () -> Unit,
    onSkip: () -> Unit,
) {
    AlertDialog(
        // 强制更新时不可通过点击外部/返回键关闭，必须更新后才能继续使用
        onDismissRequest = { if (!update.forceUpdate && !downloading) onSkip() },
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
                    update.actions -> "Actions 调试构建"
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
                if (update.actions) {
                    Text(
                        text = "⚠ 该构建来自 GitHub Actions，为最新代码的调试版本，未经过正式测试，可能存在不稳定或功能异常，请谨慎安装。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                if (update.forceUpdate) {
                    Text(
                        text = "此版本必须更新，否则无法继续使用。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    text = when {
                        update.actions && currentVersion.isNotBlank() -> "当前版本 v$currentVersion → Actions v${update.version}"
                        currentVersion.isBlank() -> "更新内容："
                        else -> "当前版本 v$currentVersion → 新版本 v${update.version}"
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
                        text = "APK 大小：${formatSize(update.apkSize)}" +
                            if (update.hasLiteVariant) "（完整版）" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // 带 / 不带内嵌 JRE 的版本提示（同一 Release 同时提供两个包）
                Spacer(Modifier.height(4.dp))
                if (update.embeddedJre) {
                    Text(
                        text = "✓ 完整版内嵌 JRE 运行时：本机开服可直接离线开服，无需下载运行时。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        text = "⚠ 该包未内嵌 JRE 运行时：本机开服首次使用需联网下载约 36MB 运行时。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (!currentHasEmbeddedJre && update.hasLiteVariant) {
                    Text(
                        text = "当前安装的是精简版：如需离线开服请选择完整版（体积约 ${formatSize(update.apkSize)}）；只用守护进程功能可选精简版（约 ${formatSize(update.liteSize)}）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (update.hasLiteVariant) {
                    Text(
                        text = "同一版本提供两种包：完整版 ${formatSize(update.apkSize)}（含 JRE）/ 精简版 ${formatSize(update.liteSize)}（不含 JRE，本机开服需联网下载运行时）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (downloading) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { downloadProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "下载中 ${(downloadProgress.coerceIn(0f, 1f) * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (update.hasLiteVariant && !downloading) {
                    TextButton(onClick = onInstallLite) {
                        Text(
                            text = "精简版 ${formatSize(update.liteSize)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(
                    onClick = onInstall,
                    enabled = !downloading,
                ) {
                    Text(
                        text = when {
                            downloading -> "下载中…"
                            update.forceUpdate -> "立即下载并安装"
                            update.hasLiteVariant -> "完整版 ${formatSize(update.apkSize)}"
                            else -> "下载并安装"
                        },
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        dismissButton = {
            if (!update.forceUpdate && !downloading) {
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
