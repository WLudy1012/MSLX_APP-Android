package com.mslx.console.ui.console

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mslx.console.ui.StatusBadge
import com.mslx.console.ui.theme.ConsoleBackground
import com.mslx.console.ui.theme.ConsoleSystem
import com.mslx.console.ui.theme.ConsoleText
import com.mslx.console.ui.theme.ConsoleTextStyle
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen(
    instanceId: Long,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel: ConsoleViewModel = viewModel(
        key = "console_$instanceId",
        factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                ConsoleViewModel(app, instanceId)
            }
        },
    )

    val state by viewModel.state.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var autoScroll by rememberSaveable { mutableStateOf(true) }
    var command by rememberSaveable { mutableStateOf("") }
    var confirmAction by remember { mutableStateOf<String?>(null) }
    var showEulaDialog by remember { mutableStateOf(false) }

    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= info.totalItemsCount - 1
        }
    }

    // 收到新日志时自动滚动到底部
    LaunchedEffect(logs.size) {
        if (autoScroll && logs.isNotEmpty()) {
            listState.scrollToItem(logs.size - 1)
        }
    }

    // 处理一次性事件(提示 / EULA 弹窗)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ConsoleEvent.Toast -> snackbarHostState.showSnackbar(event.message)
                ConsoleEvent.EulaRequired -> showEulaDialog = true
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.instanceName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "实例设置")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 状态与在线人数
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StatusBadge(status = state.status, statusText = state.statusText)
                Text(
                    text = "在线 ${state.onlinePlayers} 人",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.uptime?.takeIf { it.isNotBlank() }?.let { uptime ->
                    Text(
                        text = "运行 $uptime",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 操作按钮
            ActionButtonRow(
                status = state.status,
                busy = state.busy,
                onAction = { action ->
                    if (action in setOf("stop", "restart", "forceExit")) {
                        confirmAction = action
                    } else {
                        viewModel.sendAction(action)
                    }
                },
            )

            // 连接状态提示
            if (state.connecting) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在连接控制台…", style = MaterialTheme.typography.bodySmall)
                }
            } else if (state.connectionError != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "控制台连接失败：${state.connectionError}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::retryConnect) { Text("重连") }
                }
            }

            // 控制台日志区域(深色终端风格，圆角卡片)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(ConsoleBackground),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "控制台",
                        style = MaterialTheme.typography.labelMedium,
                        color = ConsoleSystem,
                    )
                    Spacer(Modifier.weight(1f))
                    Text("自动滚动", style = MaterialTheme.typography.labelSmall, color = ConsoleSystem)
                    Switch(
                        checked = autoScroll,
                        onCheckedChange = { checked ->
                            autoScroll = checked
                            if (checked && logs.isNotEmpty()) {
                                scope.launch { listState.scrollToItem(logs.size - 1) }
                            }
                        },
                    )
                    IconButton(onClick = viewModel::clearLogs, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "清空日志",
                            tint = ConsoleSystem,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                HorizontalDivider(color = Color(0xFF2A2E35))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    if (logs.isEmpty()) {
                        Text(
                            text = "暂无日志输出",
                            style = MaterialTheme.typography.bodySmall,
                            color = ConsoleSystem,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(16.dp),
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            items(logs.size) { index ->
                                val line = logs[index]
                                // ANSI 原彩：按转义序列拆段着色，未着色段继承默认前景色
                                val segments = remember(line.text) { parseAnsiLog(line.text) }
                                val baseColor = if (line.system) ConsoleSystem else ConsoleText
                                Text(
                                    text = buildAnnotatedString {
                                        segments.forEach { seg ->
                                            withStyle(
                                                SpanStyle(
                                                    color = seg.color ?: baseColor,
                                                    fontWeight = seg.ansiFontWeight,
                                                ),
                                            ) { append(seg.text) }
                                        }
                                    },
                                    style = ConsoleTextStyle,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }

                    // 未在底部时显示"回到最新"
                    if (!atBottom && logs.isNotEmpty()) {
                        Button(
                            onClick = {
                                scope.launch { listState.scrollToItem(logs.size - 1) }
                                autoScroll = true
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp),
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        ) {
                            Text("回到最新")
                        }
                    }
                }
            }

            // 命令输入栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = command,
                    onValueChange = { command = it },
                    placeholder = { Text("输入命令…") },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        viewModel.sendCommand(command)
                        command = ""
                    },
                    enabled = command.isNotBlank() && state.connected,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送命令",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }

    // 危险操作确认弹窗
    val pendingAction = confirmAction
    if (pendingAction != null) {
        val (title, message) = when (pendingAction) {
            "stop" -> "停止实例" to "确定要停止该实例吗？"
            "restart" -> "重启实例" to "确定要重启该实例吗？"
            "forceExit" -> "强制结束" to "强制结束会立即终止服务器进程，可能导致数据丢失。确定继续吗？"
            else -> "确认操作" to "确定执行该操作吗？"
        }
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.sendAction(pendingAction)
                        confirmAction = null
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) { Text("取消") }
            },
        )
    }

    // EULA 未签署弹窗
    if (showEulaDialog) {
        AlertDialog(
            onDismissRequest = { showEulaDialog = false },
            title = { Text("需要签署 EULA 协议") },
            text = { Text("检测到该实例尚未同意 Minecraft EULA 协议，服务器无法启动。是否同意并启动？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEulaDialog = false
                        viewModel.agreeEulaAndStart()
                    },
                ) { Text("同意并启动") }
            },
            dismissButton = {
                TextButton(onClick = { showEulaDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ActionButtonRow(
    status: Int,
    busy: Boolean,
    onAction: (String) -> Unit,
) {
    val transitioning = status in listOf(1, 3, 4)
    val isRunning = status == 2
    val isStopped = status == 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ActionButton(
            text = if (transitioning) "操作中…" else "启动",
            enabled = isStopped && !busy,
            color = if (transitioning) Color.Gray else MaterialTheme.colorScheme.primary,
            onClick = { onAction("start") },
        )
        ActionButton(
            text = "停止",
            enabled = isRunning && !busy,
            color = Color(0xFFF57C00),
            onClick = { onAction("stop") },
        )
        ActionButton(
            text = "重启",
            enabled = isRunning && !busy,
            color = Color(0xFF1976D2),
            onClick = { onAction("restart") },
        )
        ActionButton(
            text = "强制结束",
            enabled = isRunning && !busy,
            color = MaterialTheme.colorScheme.error,
            onClick = { onAction("forceExit") },
        )
        ActionButton(
            text = "备份",
            enabled = isRunning && !busy,
            color = Color(0xFF5D6D7E),
            onClick = { onAction("backup") },
        )
    }
}

/** tonal 风格操作按钮（浅色背景 + 主色文字，更协调精致）。 */
@Composable
private fun ActionButton(
    text: String,
    enabled: Boolean,
    color: Color,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = color.copy(alpha = 0.16f),
            contentColor = color,
            disabledContainerColor = color.copy(alpha = 0.08f),
            disabledContentColor = color.copy(alpha = 0.5f),
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}
