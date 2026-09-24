package com.mslx.console.ui.instances

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mslx.console.data.ManagedServer
import com.mslx.console.data.ServerRef
import com.mslx.console.ui.StatusBadge
import com.mslx.console.ui.StatusDot
import com.mslx.console.ui.statusColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstancesScreen(
    onOpenHome: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNewInstance: () -> Unit,
    /** 打开实例（本机与远程统一：路由自带 daemonId，不再依赖“主连接”）。 */
    onOpenServer: (ServerRef) -> Unit,
    viewModel: InstancesViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<ManagedServer?>(null) }

    // 每次回到本页(如新建实例完成后返回)时刷新实例列表
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        // Dock 已提升至 NavHost 外层；页面 Scaffold 不再自绘底栏
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("实例列表", fontWeight = FontWeight.Bold) },
                actions = {},
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                state.loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                state.error != null && state.servers.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        EmptyIcon()
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "加载失败",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = state.error.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(16.dp))
                        FilledTonalButton(onClick = { viewModel.refresh(initial = true) }) {
                            Text("重试")
                        }
                    }
                }

                state.servers.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        EmptyIcon()
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "暂无实例",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "点「新建」创建云端或本机实例（本机实例直接在手机上开服）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        FilledTonalButton(onClick = onOpenNewInstance) {
                            Text("新建实例")
                        }
                    }
                }

                else -> {
                    PullToRefreshBox(
                        isRefreshing = state.refreshing,
                        onRefresh = { viewModel.refresh() },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(state.servers, key = { it.key }) { server ->
                                InstanceCard(
                                    server = server,
                                    onClick = { server.ref?.let(onOpenServer) },
                                    onDelete = { pendingDelete = server },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    pendingDelete?.let { target ->
        val displayName = target.name
        var confirmation by remember(target.key) { mutableStateOf("") }
        var deleteFiles by remember(target.key) { mutableStateOf(false) }
        val deleteError = state.deleteError
        AlertDialog(
            onDismissRequest = { if (!state.deleting) pendingDelete = null },
            title = { Text("删除实例") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("请输入实例名 $displayName 以确认删除。")
                    OutlinedTextField(confirmation, { confirmation = it }, label = { Text("实例名") }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(deleteFiles, { deleteFiles = it })
                        Text(if (target.isLocal) "同时删除本机上的服务端数据文件（含世界存档）" else "同时删除磁盘上的服务端数据文件")
                    }
                    if (!deleteError.isNullOrBlank()) {
                        Text(
                            text = deleteError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !state.deleting && confirmation == displayName,
                    onClick = { viewModel.delete(target, deleteFiles) { pendingDelete = null } },
                ) { Text(if (state.deleting) "删除中..." else "删除") }
            },
            dismissButton = { TextButton(enabled = !state.deleting, onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun EmptyIcon() {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

@Composable
private fun InstanceCard(
    server: ManagedServer,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusCode = if (server.running) 2 else 0
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 状态色块
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(statusColor(statusCode).copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                StatusDot(statusCode)
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(status = statusCode, statusText = if (server.running) "运行中" else "未启动")
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "[${server.sourceLabel}]" + server.detail.ifBlank { "" }.let { if (it.isBlank()) "" else " $it" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除实例", tint = MaterialTheme.colorScheme.error)
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
