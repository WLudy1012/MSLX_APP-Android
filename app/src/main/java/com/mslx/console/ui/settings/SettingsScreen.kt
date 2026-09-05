package com.mslx.console.ui.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mslx.console.data.DaemonConfig
import com.mslx.console.data.UpdateChannel
import com.mslx.console.ui.update.UpdateViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenHome: () -> Unit,
    onOpenInstances: () -> Unit,
    onOpenNewInstance: () -> Unit,
    onAddDaemon: () -> Unit,
    onEditDaemon: (String) -> Unit,
    onOpenUserCenter: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenLocalServer: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<DaemonConfig?>(null) }
    var pendingActionsConfirm by remember { mutableStateOf(false) }

    // 手动检查更新：必须与 MainActivity 的 UpdateHost 共用同一个 activity 作用域 ViewModel
    val activity = LocalContext.current.findActivity()
    val updateViewModel: UpdateViewModel = if (activity != null) {
        viewModel(viewModelStoreOwner = activity)
    } else {
        viewModel()
    }
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        updateViewModel.message.collect { snackbarHostState.showSnackbar(it) }
    }
    val ctx = LocalContext.current
    val versionName = remember {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
    }

    Scaffold(
        // Dock 已提升至 NavHost 外层；页面 Scaffold 不再自绘底栏
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(title = { Text("设置") })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // ---- 用户中心入口 ----
            Card(
                onClick = onOpenUserCenter,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "用户中心",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            text = "查看头像与名称",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        )
                    }
                }
            }
            Spacer(Modifier.size(20.dp))

            // ---- 更新渠道 ----
            SectionTitle("更新渠道")
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    ChannelOption(
                        title = "稳定版",
                        subtitle = "仅接收正式稳定版更新（推荐）",
                        selected = settings.updateChannel == UpdateChannel.STABLE,
                        onClick = { viewModel.setUpdateChannel(UpdateChannel.STABLE) },
                    )
                    ChannelOption(
                        title = "测试版",
                        subtitle = "同时接收 Beta 测试版更新",
                        selected = settings.updateChannel == UpdateChannel.BETA,
                        onClick = { viewModel.setUpdateChannel(UpdateChannel.BETA) },
                    )
                    ChannelOption(
                        title = "Actions 调试构建",
                        subtitle = "直接安装 GitHub Actions 最新调试版（不稳定）",
                        selected = settings.updateChannel == UpdateChannel.ACTIONS,
                        onClick = { pendingActionsConfirm = true },
                    )
                }
            }

            Spacer(Modifier.size(20.dp))

            // ---- 通用 ----
            SectionTitle("通用")
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column {
                    EntryRow(
                        icon = { Icon(Icons.Filled.Star, null, tint = MaterialTheme.colorScheme.primary) },
                        title = "外观",
                        subtitle = "主题颜色与动态取色",
                        onClick = onOpenAppearance,
                    )
                    EntryRow(
                        icon = { Icon(Icons.AutoMirrored.Filled.List, null, tint = MaterialTheme.colorScheme.primary) },
                        title = "运行日志",
                        subtitle = "查看与导出应用日志",
                        onClick = onOpenLogs,
                    )
                    EntryRow(
                        icon = { Icon(Icons.Filled.Refresh, null, tint = MaterialTheme.colorScheme.primary) },
                        title = "检查更新",
                        subtitle = if (versionName.isBlank()) "MSLX 控制台" else "MSLX 控制台 v$versionName",
                        onClick = { if (!updateState.checking) updateViewModel.checkManually() },
                        trailing = {
                            if (updateState.checking) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        },
                    )
                    EntryRow(
                        icon = { Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.primary) },
                        title = "关于",
                        subtitle = "版本、更新说明与贡献者",
                        onClick = onOpenAbout,
                    )
                    EntryRow(
                        icon = { Icon(Icons.Filled.Build, null, tint = MaterialTheme.colorScheme.primary) },
                        title = "本机开服（实验）",
                        subtitle = "直接在本机启动服务端，需 Android JRE",
                        onClick = onOpenLocalServer,
                    )
                }
            }

            Spacer(Modifier.size(20.dp))

            // ---- Daemon 管理 ----
            SectionTitle("Daemon 管理")
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    if (settings.daemons.isEmpty()) {
                        Text(
                            text = "尚未添加任何 Daemon",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    } else {
                        settings.daemons.forEach { daemon ->
                            DaemonRow(
                                daemon = daemon,
                                isActive = daemon.id == settings.activeDaemonId,
                                onSelect = { viewModel.setActiveDaemon(daemon.id) },
                                onEdit = { onEditDaemon(daemon.id) },
                                onDelete = { pendingDelete = daemon },
                            )
                        }
                    }
                    TextButton(
                        onClick = onAddDaemon,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("添加 Daemon")
                    }
                }
            }
        }
    }

    // 删除确认
    val target = pendingDelete
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除 Daemon") },
            text = { Text("确定删除「${target.name.ifBlank { target.baseUrl }}」吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeDaemon(target.id)
                        pendingDelete = null
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    // Actions 渠道不稳定警告
    if (pendingActionsConfirm) {
        AlertDialog(
            onDismissRequest = { pendingActionsConfirm = false },
            title = { Text("选择 Actions 调试构建渠道") },
            text = {
                Text(
                    "该渠道直接安装 GitHub Actions 最新调试版本，代码未经正式测试，可能不稳定、存在 Bug 或导致数据异常。确定要切换到该渠道吗？",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setUpdateChannel(UpdateChannel.ACTIONS)
                        pendingActionsConfirm = false
                    },
                ) { Text("仍然使用", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingActionsConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun ChannelOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EntryRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing()
    }
}

@Composable
private fun DaemonRow(
    daemon: DaemonConfig,
    isActive: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = isActive, onClick = onSelect)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = daemon.name.ifBlank { daemon.baseUrl },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (daemon.name.isNotBlank()) {
                Text(
                    text = daemon.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onEdit) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "编辑",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "删除",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 从任意 Compose Context 向上查找宿主 Activity。 */
private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
