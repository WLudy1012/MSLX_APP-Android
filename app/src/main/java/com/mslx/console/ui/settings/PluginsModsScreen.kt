package com.mslx.console.ui.settings

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.getValue
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory

enum class PmFilter { ALL, ENABLED, DISABLED, CLIENT }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PluginsModsScreen(
    instanceId: Long,
    onBack: () -> Unit,
) {
    val viewModel: PluginsModsViewModel = viewModel(
        key = "pm_$instanceId",
        factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                PluginsModsViewModel(app, instanceId)
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var filter by remember { mutableStateOf(PmFilter.ALL) }
    var showBatchDialog by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.upload(uri)
    }

    LaunchedEffect(Unit) {
        viewModel.message.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("插件 / 模组管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::load) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    TextButton(
                        onClick = { filePicker.launch("*/*") },
                        enabled = !state.busy,
                    ) {
                        if (state.busy) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text("上传")
                        }
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
            // 模式切换
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                SegmentedButton(
                    selected = state.mode == "plugins",
                    onClick = { viewModel.setMode("plugins") },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("插件") }
                SegmentedButton(
                    selected = state.mode == "mods",
                    onClick = { viewModel.setMode("mods") },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("模组") }
            }

            when {
                state.loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                state.error != null && state.data == null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = state.error.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = viewModel::load) { Text("重试") }
                    }
                }

                else -> {
                    val data = state.data
                    if (data != null && data.totalCount == 0) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "没有找到任何${if (state.mode == "plugins") "插件" else "模组"}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (data != null) {
                        val enabledFiles = if (filter == PmFilter.DISABLED || filter == PmFilter.CLIENT) emptyList() else data.jarFiles
                        val clientFiles = if (filter == PmFilter.DISABLED || filter == PmFilter.ENABLED) emptyList() else data.clientJarFiles
                        val disabledFiles = if (filter == PmFilter.ENABLED || filter == PmFilter.CLIENT) emptyList() else data.disableJarFiles
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            item {
                                StatsRow(data, filter, onFilter = { filter = it }, onLongPress = { showBatchDialog = true })
                            }

                            if (enabledFiles.isNotEmpty() || clientFiles.isNotEmpty()) {
                                item { GroupTitle("已启用") }
                                items(enabledFiles, key = { "enabled_$it" }) { f ->
                                    PmRow(
                                        fileName = f,
                                        subtitle = "已启用",
                                        accent = false,
                                        onToggle = { viewModel.toggle(f, currentlyDisabled = false) },
                                        onDelete = { viewModel.delete(f) },
                                        toggleLabel = "禁用",
                                    )
                                }
                                items(clientFiles, key = { "client_$it" }) { f ->
                                    PmRow(
                                        fileName = f,
                                        subtitle = "仅客户端",
                                        accent = true,
                                        onToggle = null,
                                        onDelete = { viewModel.delete(f) },
                                        toggleLabel = null,
                                    )
                                }
                            }

                            if (disabledFiles.isNotEmpty()) {
                                item { GroupTitle("已禁用") }
                                items(disabledFiles, key = { "disabled_$it" }) { f ->
                                    PmRow(
                                        fileName = f,
                                        subtitle = "已禁用",
                                        accent = false,
                                        onToggle = { viewModel.toggle(f, currentlyDisabled = true) },
                                        onDelete = { viewModel.delete(f) },
                                        toggleLabel = "启用",
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showBatchDialog) {
        AlertDialog(
            onDismissRequest = { showBatchDialog = false },
            title = { Text("批量操作") },
            text = { Text("对当前${if (state.mode == "plugins") "插件" else "模组"}列表执行批量操作。") },
            confirmButton = {
                TextButton(onClick = { viewModel.batch("enable"); showBatchDialog = false }) { Text("全部启用") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.batch("disable"); showBatchDialog = false }) { Text("全部禁用") }
                    TextButton(onClick = { showBatchDialog = false }) { Text("取消") }
                }
            },
        )
    }
}

@Composable
private fun StatsRow(data: com.mslx.console.data.model.PmListData, selected: PmFilter, onFilter: (PmFilter) -> Unit, onLongPress: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatChip("总数", data.totalCount, selected == PmFilter.ALL, Modifier.weight(1f), { onFilter(PmFilter.ALL) }, onLongPress)
        StatChip("已启用", data.activeCount, selected == PmFilter.ENABLED, Modifier.weight(1f), { onFilter(PmFilter.ENABLED) }, onLongPress)
        StatChip("已禁用", data.disabledCount, selected == PmFilter.DISABLED, Modifier.weight(1f), { onFilter(PmFilter.DISABLED) }, onLongPress)
        StatChip("仅客户端", data.clientOnlyCount, selected == PmFilter.CLIENT, Modifier.weight(1f), { onFilter(PmFilter.CLIENT) }, onLongPress)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatChip(label: String, value: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit, onLongPress: () -> Unit) {
    Card(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongPress),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "$value",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GroupTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun PmRow(
    fileName: String,
    subtitle: String,
    accent: Boolean,
    onToggle: (() -> Unit)?,
    onDelete: () -> Unit,
    toggleLabel: String?,
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (accent) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onToggle != null && toggleLabel != null) {
                OutlinedButton(onClick = onToggle) {
                    Text(toggleLabel)
                }
                Spacer(Modifier.width(6.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
