package com.mslx.console.ui.servers

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mslx.console.data.DaemonState
import com.mslx.console.data.DaemonStatus
import com.mslx.console.data.ManagedServer
import com.mslx.console.data.ServerRef
import com.mslx.console.ui.MslxCard

/**
 * 服务端总览：多 Daemon 连接状态 + 统一服务端列表。
 * 本机开服（进程内 JVM）与各 Daemon 上的实例在同一列表里展示与操作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersOverviewScreen(
    onBack: () -> Unit,
    /** 打开实例：本机与控制台走同一套带 daemonId 的路由（去主连接）。 */
    onOpenServer: (ServerRef) -> Unit,
    /** 进入新建实例向导（云端 / 本机由向导内的「创建目标」区分）。 */
    onOpenCreate: () -> Unit,
    viewModel: ServersOverviewViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        // 页面透明：透出全局毛玻璃背景层
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("服务端总览") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh, enabled = !state.loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            if (state.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
            }

            // ---------- Daemon 连接 ----------
            MslxCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Daemon 连接（${state.daemons.size}）",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "所有 Daemon 同时保持连接并并行探测状态，互不影响；各服务端完全对等，标「默认」的那台只影响新建实例的默认落点。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.daemons.isEmpty()) {
                        Text(
                            "还没有配置 Daemon。到设置页添加后，这里会并行显示每台的在线状态。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.daemons.forEach { daemon ->
                            DaemonRow(
                                status = daemon,
                                onSetDefault = { viewModel.setDefault(daemon.id) },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---------- 统一服务端列表 ----------
            MslxCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "服务端（本机 + 各 Daemon，共 ${state.servers.size}）",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (state.servers.isEmpty()) {
                        Text(
                            "暂无服务端。可以用下面的按钮新建本机实例或云端实例。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.servers.forEach { server ->
                            ServerRow(
                                server = server,
                                onOpen = { server.ref?.let(onOpenServer) },
                                onStopLocal = viewModel::stopLocal,
                                onDeleteLocal = { server.localDirName?.let(viewModel::deleteLocal) },
                            )
                        }
                    }
                }
            }

            state.message?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onOpenCreate, modifier = Modifier.weight(1f)) { Text("新建云端实例") }
                OutlinedButton(
                    onClick = onOpenCreate,
                    modifier = Modifier.weight(1f),
                ) { Text("本机开服") }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun DaemonRow(status: DaemonStatus, onSetDefault: () -> Unit) {
    val dotColor = when (status.state) {
        DaemonState.ONLINE -> MaterialTheme.colorScheme.primary
        DaemonState.OFFLINE -> MaterialTheme.colorScheme.error
        DaemonState.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("●", color = dotColor, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = status.name.ifBlank { status.id } + if (status.isDefault) "（默认）" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = buildString {
                    append(status.stateText)
                    status.version?.let { append(" · v").append(it) }
                    status.instanceCount?.let { append(" · ").append(it).append(" 个实例") }
                    status.latencyMs?.let { append(" · ").append(it).append("ms") }
                    if (status.state == DaemonState.OFFLINE && status.message.isNotBlank()) {
                        append(" · ").append(status.message)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!status.isDefault) {
            TextButton(onClick = onSetDefault) { Text("设为默认") }
        }
    }
}

@Composable
private fun ServerRow(
    server: ManagedServer,
    onOpen: () -> Unit,
    onStopLocal: () -> Unit,
    onDeleteLocal: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = server.name + if (server.running) " · 运行中" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = if (server.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "[${server.sourceLabel}] " + server.detail.ifBlank { "—" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (server.isLocal && server.running) {
            TextButton(onClick = onStopLocal) { Text("停止") }
        }
        TextButton(onClick = onOpen) { Text(if (server.isLocal) "管理" else "控制台") }
        if (server.isLocal) {
            IconButton(onClick = onDeleteLocal) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除本机实例",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
