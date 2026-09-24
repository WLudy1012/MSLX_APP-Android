package com.mslx.console.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mslx.console.data.ManagedServer
import com.mslx.console.data.ServerRef
import com.mslx.console.ui.statusColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 主页：**多 Daemon 平权**的一屏总览。
 *
 * - 顶部是一张 `HorizontalPager` 卡片：左右滑动切换服务端，指示器显示 n/N，与下方实例列表联动
 * - 卡片下方是聚合实例列表（本机实例 + 各 Daemon 实例），点开直达对应服务端的控制台
 * - 主页不再承担「连接向导」职责：没有配置服务端时卡片区只给空态与添加入口
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    /** 进入实例列表页（全部服务端）。 */
    onOpenInstances: () -> Unit,
    /** 进入连接页添加服务端。 */
    onOpenConnect: () -> Unit,
    /** 进入连接页编辑指定服务端。 */
    onEditDaemon: (String) -> Unit,
    /** 打开实例：路由自带 daemonId，本机与远程同一条通路。 */
    onOpenServer: (ServerRef) -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 每次回到主页：拉一轮最新状态（负载走 SignalR 实时推送，这里只补实例与探活）
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        // Dock 已提升至 NavHost 外层；页面 Scaffold 不再自绘底栏
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("主页", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.refreshing) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    DaemonPagerSection(
                        state = state,
                        onPageSelected = viewModel::selectDaemon,
                        onReconnect = viewModel::reconnect,
                        onEdit = onEditDaemon,
                        onOpenInstances = onOpenInstances,
                        onAddDaemon = onOpenConnect,
                    )
                }

                item {
                    ServersSectionHeader(
                        state = state,
                        onToggleOnlySelected = viewModel::setOnlySelected,
                        onOpenInstances = onOpenInstances,
                    )
                }
                if (state.visibleServers.isEmpty()) {
                    item { EmptyServersCard(state, onOpenInstances) }
                } else {
                    items(state.visibleServers, key = { it.key }) { server ->
                        ServerRow(
                            server = server,
                            highlighted = server.ref?.daemonId == state.selectedDaemonId,
                            onClick = { server.ref?.let(onOpenServer) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }

                item { QuoteCard(state) }

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
                    items(state.notifications, key = { "${it.ref.catalogKey}_${it.time}" }) { notification ->
                        NotificationRow(notification, onClick = { onOpenServer(notification.ref) })
                    }
                }
            }
        }
    }
}

/** Daemon 卡片区：一台一张卡左右滑动；没有配置时给空态入口。 */
@Composable
private fun DaemonPagerSection(
    state: HomeUiState,
    onPageSelected: (String) -> Unit,
    onReconnect: (String) -> Unit,
    onEdit: (String) -> Unit,
    onOpenInstances: () -> Unit,
    onAddDaemon: () -> Unit,
) {
    val pages = state.pages
    if (pages.isEmpty()) {
        AddDaemonEmptyCard(
            loading = state.loading,
            onAddDaemon = onAddDaemon,
        )
        return
    }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    // 滑动 → 选中该页（列表联动由 state.selectedDaemonId 驱动）
    LaunchedEffect(pagerState.currentPage, pages.size) {
        val index = pagerState.currentPage.coerceIn(0, pages.lastIndex)
        onPageSelected(pages[index].daemonId)
    }
    // 选中项被外部改变（首屏默认落点、服务端被删除后的回落）→ 滚到对应页
    LaunchedEffect(state.selectedIndex, pages.size) {
        val target = state.selectedIndex
        if (pages.isNotEmpty() && pagerState.currentPage != target) {
            pagerState.animateScrollToPage(target)
        }
    }

    Column {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(296.dp),
        ) { page ->
            val item = pages[page]
            DaemonCard(
                page = item,
                index = page + 1,
                total = pages.size,
                onReconnect = { onReconnect(item.daemonId) },
                onEdit = { onEdit(item.daemonId) },
                onOpenInstances = onOpenInstances,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            pages.indices.forEach { index ->
                val selected = index == pagerState.currentPage
                // 外层 20dp 热区保证可点，内层才是圆点本身
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .clickable { scope.launch { pagerState.animateScrollToPage(index) } },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (selected) 9.dp else 7.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                            ),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${pagerState.currentPage + 1}/${pages.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 尚未配置任何服务端时的空态：只保留「添加服务端」入口（连接向导在连接页里）。 */
@Composable
private fun AddDaemonEmptyCard(loading: Boolean, onAddDaemon: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = if (loading) "正在读取配置…" else "还没有配置任何服务端",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (loading) "" else "添加 MSLX Daemon 后即可在这里查看状态与实例；本机开服实例无需连接也会显示。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!loading) {
                Spacer(Modifier.height(14.dp))
                Button(onClick = onAddDaemon, modifier = Modifier.fillMaxWidth().height(44.dp)) {
                    Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("添加服务端", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** 单张 Daemon 卡：名称/地址、在线与延迟、版本、实例数、CPU/内存负载、快捷操作。 */
@Composable
private fun DaemonCard(
    page: DaemonPage,
    index: Int,
    total: Int,
    onReconnect: () -> Unit,
    onEdit: () -> Unit,
    onOpenInstances: () -> Unit,
) {
    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OnlineDot(page.online)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = page.name.ifBlank { "MSLX Daemon" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(
                                when (page.online) {
                                    null -> "检测中…"
                                    true -> "在线"
                                    false -> "离线"
                                },
                            )
                            page.latencyMs?.let { append(" · ").append(it).append("ms") }
                            append(" · ").append(page.protocol)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (page.isDefault) {
                    // 「默认」徽标：只标记新建实例的默认落点，不代表优先级
                    Text(
                        text = "默认",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                } else {
                    Text(
                        text = "$index/$total",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = page.baseUrl,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoField("实例", "${page.instanceRunning}/${page.instanceTotal} 运行", Modifier.weight(1.2f))
                InfoField("在线玩家", "${page.onlinePlayers}", Modifier.weight(1f))
                InfoField(
                    "版本",
                    page.version.takeIf { it.isNotBlank() }?.let { "v$it" } ?: "—",
                    Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
            MetricBar("CPU", page.cpu, if (page.cpu != null) "${page.cpu.formatMetric()}%" else "未接入")
            Spacer(Modifier.height(8.dp))
            val memoryText = when {
                page.memoryUsedGb != null && page.memoryTotalGb != null && page.memoryTotalGb > 0 ->
                    "${page.memoryUsedGb.formatMetric()} / ${page.memoryTotalGb.formatMetric()} GB"
                page.memoryPercent != null -> "${page.memoryPercent.formatMetric()}%"
                else -> "未接入"
            }
            MetricBar("内存", page.memoryPercent, memoryText)
            page.message?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onReconnect) { Text("重连") }
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = onOpenInstances) { Text("实例列表") }
            }
        }
    }
}

/** 聚合实例列表标题：显示当前联动范围，并提供「只看这台 / 看全部」切换与整页入口。 */
@Composable
private fun ServersSectionHeader(
    state: HomeUiState,
    onToggleOnlySelected: (Boolean) -> Unit,
    onOpenInstances: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("服务端实例", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = if (state.onlySelected && state.selectedDaemonId.isNotBlank()) {
                    "只看「${state.selectedDaemonName}」· ${state.visibleServers.size}/${state.servers.size}"
                } else {
                    "本机 + 各服务端 · ${state.runningServers}/${state.servers.size} 运行中"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.hasDaemons) {
            OutlinedButton(onClick = { onToggleOnlySelected(!state.onlySelected) }) {
                Text(if (state.onlySelected) "看全部" else "只看这台")
            }
        }
        IconButton(onClick = onOpenInstances) {
            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "实例列表")
        }
    }
}

/** 聚合列表里的一个实例：来源徽标 + 状态 + 负载摘要，点开直达所属服务端的控制台。 */
@Composable
private fun ServerRow(
    server: ManagedServer,
    highlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusCode = server.status
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            },
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor(statusCode)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(server.sourceLabel)
                        if (server.detail.isNotBlank()) append(" · ").append(server.detail)
                        if (server.onlinePlayers > 0) append(" · ").append(server.onlinePlayers).append(" 人在线")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyServersCard(state: HomeUiState, onOpenInstances: () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = if (state.hasDaemons) "这台服务端还没有实例" else "还没有任何实例",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "到「新建」里创建云端实例或本机实例（本机实例直接在手机上开服）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            FilledTonalButton(onClick = onOpenInstances) { Text("去实例列表") }
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

@Composable
private fun MetricBar(label: String, percent: Double?, display: String) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(display, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { (percent ?: 0.0).coerceIn(0.0, 100.0).div(100.0).toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun InfoField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 单条开服/关服通知：点开直达对应实例（本机与远程同一条通路）。 */
@Composable
private fun NotificationRow(notification: ServerNotification, onClick: () -> Unit) {
    val color = if (notification.isOpened) Color(0xFF2E7D32) else Color(0xFFFB8C00)
    // 同一时间戳只格式化一次：避免每次重组都新建 SimpleDateFormat
    val timeText = remember(notification.time) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(notification.time))
    }
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
    ) {
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
                    text = "${notification.sourceLabel} · $timeText",
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

private fun Double.formatMetric(): String = String.format(Locale.US, "%.1f", this)
