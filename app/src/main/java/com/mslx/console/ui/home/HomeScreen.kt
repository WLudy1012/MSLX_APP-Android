package com.mslx.console.ui.home

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mslx.console.ui.ConnectivityViewModel
import com.mslx.console.ui.MainBottomNav
import com.mslx.console.ui.TopPage
import com.mslx.console.ui.statusColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenInstances: () -> Unit,
    onOpenNewInstance: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenConnect: () -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 连接连通性（activity 作用域全局单例，与 MainActivity 的 ConnectivityHost 共用）
    val activity = LocalContext.current.findActivity()
    val connectivityViewModel: ConnectivityViewModel = if (activity != null) {
        viewModel(viewModelStoreOwner = activity)
    } else {
        viewModel()
    }
    val connectivityState by connectivityViewModel.state.collectAsStateWithLifecycle()

    // 每次回到主页时刷新负载与实例状态
    LifecycleResumeEffect(Unit) {
        viewModel.refreshMetrics()
        viewModel.refreshInstances()
        onPauseOrDispose { }
    }

    Scaffold(
        bottomBar = {
            MainBottomNav(
                current = TopPage.HOME,
                onNavigate = { page ->
                    when (page) {
                        TopPage.HOME -> {}
                        TopPage.INSTANCES -> onOpenInstances()
                        TopPage.NEW_INSTANCE -> onOpenNewInstance()
                        TopPage.SETTINGS -> onOpenSettings()
                    }
                },
            )
        },
        topBar = {
            TopAppBar(title = { Text("主页", fontWeight = FontWeight.Bold) })
        },
    ) { innerPadding ->
        if (state.connecting) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("正在连接守护进程…", style = MaterialTheme.typography.bodyMedium)
            }
        } else if (!state.connected) {
            NotConnectedContent(
                daemonName = state.daemonName,
                error = state.error,
                onOpenConnect = onOpenConnect,
                onRetry = viewModel::retryConnect,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { DaemonStatusCard(state, online = connectivityState.online, onRefresh = viewModel::refreshMetrics) }
                item { QuoteCard(state) }
                item { ResourceCard(state) }
                item { InstanceSummaryCard(state, onOpenInstances) }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("开服通知", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (state.notifications.isNotEmpty()) {
                            TextButton(onClick = viewModel::clearNotifications) { Text("清空") }
                        }
                    }
                }
                if (state.notifications.isEmpty()) {
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "暂无通知。实例状态变化(开服/关服)会在这里显示。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    items(state.notifications, key = { "${it.id}_${it.time}" }) { notification ->
                        NotificationRow(notification)
                    }
                }
            }
        }
    }
}

/** Daemon 未连接时的提示卡片。 */
@Composable
private fun NotConnectedContent(
    daemonName: String,
    error: String?,
    onOpenConnect: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Daemon 未连接",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (daemonName.isNotBlank()) {
                        "已保存的守护进程「$daemonName」无法连接"
                    } else {
                        "还没有连接任何守护进程"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!error.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = onOpenConnect,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text("去连接 Daemon", fontWeight = FontWeight.SemiBold)
                }
                if (daemonName.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("重试连接")
                    }
                }
            }
        }
    }
}

/** Daemon 状态卡：名称/在线状态/协议/版本。 */
@Composable
private fun DaemonStatusCard(state: HomeUiState, online: Boolean?, onRefresh: () -> Unit) {
    Card(shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.daemonName.ifBlank { "MSLX Daemon" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OnlineDot(online)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = when (online) {
                            null -> "检测中… · ${state.protocol}"
                            true -> "在线 · ${state.protocol}"
                            false -> "离线 · ${state.protocol}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = state.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.daemonVersion.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Daemon v${state.daemonVersion}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, "刷新")
            }
        }
    }
}

@Composable
private fun OnlineDot(online: Boolean?) {
    val color = when (online) {
        true -> statusColor(2)
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.outlineVariant
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** 每日一言卡：加载失败时展示兜底文案（不崩溃、不空白）。 */
@Composable
private fun QuoteCard(state: HomeUiState) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("每日一言", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            when {
                state.quote.isNotBlank() -> {
                    Text(
                        text = "「${state.quote}」",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (state.quoteSource.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "—— ${state.quoteSource}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                state.quoteFailed -> Text(
                    text = "一言加载失败，请检查网络连接",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text(
                    text = "一言加载中…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 负载监视卡：CPU / 内存 使用率进度条。 */
@Composable
private fun ResourceCard(state: HomeUiState) {
    val info = state.systemInfo
    Card(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("负载监视", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            val cpu = info?.cpuUsage?.coerceIn(0.0, 100.0)
            val memoryPercent = info?.memoryUsage?.coerceIn(0.0, 100.0)
            val memoryText = when {
                info?.memoryUsed != null && info.memoryTotal != null && info.memoryTotal > 0 ->
                    "${info.memoryUsed.formatMetric()} / ${info.memoryTotal.formatMetric()} GB"
                memoryPercent != null -> "${memoryPercent.formatMetric()}%"
                else -> null
            }
            MetricBar("CPU", cpu, if (cpu != null) "${cpu.formatMetric()}%" else "Daemon 未提供")
            Spacer(Modifier.height(10.dp))
            MetricBar("内存", memoryPercent, memoryText ?: "Daemon 未提供")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val os = info?.osType.orEmpty()
                val arch = info?.osArchitecture.orEmpty()
                InfoField("系统", if (os.isNotBlank() || arch.isNotBlank()) "$os ${arch}".trim() else "未知", Modifier.weight(1f))
                InfoField("协议", state.protocol, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MetricBar(label: String, percent: Double?, display: String) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(display, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        if (percent != null) {
            LinearProgressIndicator(
                progress = { (percent / 100f).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(progress = { 0f }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun InfoField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 实例概览卡：总数 / 运行中 / 在线人数。 */
@Composable
private fun InstanceSummaryCard(state: HomeUiState, onOpenInstances: () -> Unit) {
    val total = state.instances.size
    val running = state.instances.count { it.status == 2 }
    val onlinePlayers = state.instances.sumOf { it.extra?.onlinePlayers ?: 0 }
    Card(
        onClick = onOpenInstances,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.List, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(8.dp))
                Text("实例概览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                InfoField("实例总数", "$total", Modifier.weight(1f))
                InfoField("运行中", "$running", Modifier.weight(1f))
                InfoField("在线玩家", "$onlinePlayers", Modifier.weight(1f))
            }
        }
    }
}

/** 单条开服/关服通知。 */
@Composable
private fun NotificationRow(notification: ServerNotification) {
    val color = if (notification.isOpened) androidx.compose.ui.graphics.Color(0xFF2E7D32) else androidx.compose.ui.graphics.Color(0xFFFB8C00)
    Card(shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (notification.isOpened) "${notification.instanceName} 已开服" else "${notification.instanceName} 已关服",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatTime(notification.time),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (notification.isOpened) "开服" else "关服",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
        }
    }
}

private fun formatTime(time: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))

private fun Double.formatMetric(): String = String.format(Locale.US, "%.1f", this)

/** 从任意 Compose Context 向上查找宿主 Activity。 */
private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
