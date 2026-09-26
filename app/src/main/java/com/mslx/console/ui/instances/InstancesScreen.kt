package com.mslx.console.ui.instances

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mslx.console.data.ManagedServer
import com.mslx.console.data.ServerRef
import com.mslx.console.data.isStoppableStatus
import com.mslx.console.ui.StatusBadge
import com.mslx.console.ui.StatusDot
import com.mslx.console.ui.MslxCard
import com.mslx.console.ui.MslxEmptyState
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
    val snackbarHostState = remember { SnackbarHostState() }

    // 启停/停全部的结果提示：弹一次 Snackbar 后请 VM 清空，避免旋转屏/重组重复弹
    LaunchedEffect(state.actionMessage) {
        val message = state.actionMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearActionMessage()
    }

    // 每次回到本页(如新建实例完成后返回)时刷新实例列表
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        // 页面透明：透出全局毛玻璃背景层
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        // Dock 已提升至 NavHost 外层；页面 Scaffold 不再自绘底栏
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("实例列表", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                ),
                actions = {
                    // 一键停止全部：逐个按实例归属下发，不再依赖“主连接”
                    val hasStoppable = state.servers.any { isStoppableStatus(it.status) }
                    TextButton(
                        onClick = viewModel::stopAll,
                        enabled = hasStoppable && !state.stoppingAll,
                    ) {
                        Text(if (state.stoppingAll) "停止中…" else "停止全部")
                    }
                },
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
                    MslxEmptyState(
                        icon = Icons.Filled.Info,
                        title = "加载失败",
                        description = state.error.orEmpty(),
                        actionLabel = "重试",
                        onAction = { viewModel.refresh(initial = true) },
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                state.servers.isEmpty() -> {
                    MslxEmptyState(
                        icon = Icons.Filled.Info,
                        title = "暂无实例",
                        description = "点「新建」创建云端或本机实例（本机实例直接在手机上开服）",
                        actionLabel = "新建实例",
                        onAction = onOpenNewInstance,
                        modifier = Modifier.align(Alignment.Center),
                    )
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
                                start = 20.dp, end = 20.dp, top = 10.dp, bottom = 30.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            items(state.servers, key = { it.key }) { server ->
                                InstanceCard(
                                    server = server,
                                    icon = state.icons[server.key],
                                    busy = server.key in state.busyKeys,
                                    onClick = { server.ref?.let(onOpenServer) },
                                    onToggle = { viewModel.toggle(server, start = !server.running) },
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
private fun InstanceCard(
    server: ManagedServer,
    icon: Bitmap?,
    busy: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusCode = server.status
    MslxCard(
        onClick = onClick,
        highlighted = server.running,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 图标（有则显示，无则回退状态色块）
            if (icon != null) {
                Image(
                    bitmap = icon.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(13.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(statusColor(statusCode).copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    StatusDot(statusCode)
                }
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
                    StatusBadge(status = statusCode, statusText = null)
                    Spacer(Modifier.width(10.dp))
                    // 来源徽标：多服务端共存时一眼分辨实例属于哪台（本机/各 Daemon 完全对等）
                    Text(
                        text = server.sourceLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    if (server.detail.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = server.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // 启停：按实例归属的 ServerRef 下发，进行中的那一行显进度
            TextButton(onClick = onToggle, enabled = !busy) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        text = if (server.running) "停止" else "启动",
                        color = if (server.running) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
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
