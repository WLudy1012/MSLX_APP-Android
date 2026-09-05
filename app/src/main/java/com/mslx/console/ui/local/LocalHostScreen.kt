package com.mslx.console.ui.local

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mslx.console.ui.theme.ConsoleBackground
import com.mslx.console.ui.theme.ConsoleSystem
import com.mslx.console.ui.theme.ConsoleText
import com.mslx.console.ui.theme.ConsoleTextStyle

/**
 * 本机开服（P1）：JRE 自动下载 + MSLAPI 核心下载 + daemon 式服务端配置，在设备上直接跑 Java 服务端。
 * 无 Termux、无守护进程、无 WebUI —— 全部内置于 App。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalHostScreen(
    onBack: () -> Unit,
    viewModel: LocalHostViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("本机开服") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                "本机开服引擎：自动下载 JRE 与 MSLAPI 服务端核心，在本机直接运行 Java 服务端（无需 Termux / 守护进程 / WebUI）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            // ---------- 1. JRE ----------
            Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Java 运行环境（JRE）", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        if (state.jreInstalled) {
                            Text(
                                "已安装",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Text(
                                "未安装",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = state.javaPath,
                        onValueChange = { v -> viewModel.update { it.copy(javaPath = v) } },
                        label = { Text("Java 可执行文件") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.jreZipUrl,
                        onValueChange = { v -> viewModel.update { it.copy(jreZipUrl = v) } },
                        label = { Text("Android JRE .zip 下载地址") },
                        placeholder = { Text("https://…/jre8-android.zip（留空则用上方已装路径）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (state.jreInstalling) {
                        LinearProgressIndicator(progress = { state.jreProgress }, modifier = Modifier.fillMaxWidth())
                        Text(
                            "正在下载并解压 JRE… ${(state.jreProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = viewModel::downloadJre,
                        enabled = !state.jreInstalling,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (state.jreInstalled) "重新安装 JRE" else "下载并安装 JRE")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---------- 2. 服务端核心（MSLAPI） ----------
            Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Build,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("服务端核心（MSLAPI）", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = viewModel::refreshCores, enabled = !state.coresLoading) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新核心列表")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        DropdownField(
                            label = "核心类型",
                            items = state.coreNames,
                            selected = state.coreName,
                            onSelect = viewModel::selectCore,
                            enabled = state.coreNames.isNotEmpty() && !state.coresLoading,
                            modifier = Modifier.weight(1f),
                        )
                        DropdownField(
                            label = "游戏版本",
                            items = state.coreVersions,
                            selected = state.coreVersion,
                            onSelect = viewModel::selectVersion,
                            enabled = state.coreVersions.isNotEmpty() && !state.coresLoading,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (state.coresLoading) {
                        Text(
                            "正在从 MSLAPI 拉取核心列表…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.coreDownloading) {
                        LinearProgressIndicator(progress = { state.coreProgress }, modifier = Modifier.fillMaxWidth())
                        Text(
                            "正在下载核心… ${(state.coreProgress * 100).toInt()}%（SHA-256 校验）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (state.jarInstalled) {
                        Text(
                            "核心已就绪：${state.jarPath}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    OutlinedButton(
                        onClick = viewModel::downloadCore,
                        enabled = !state.coreDownloading && state.coreName.isNotBlank() && state.coreVersion.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (state.jarInstalled) "重新下载核心" else "下载服务端核心")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ---------- 3. 服务端配置（参照 daemon 实例） ----------
            Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("服务端配置", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = state.serverName,
                        onValueChange = { v -> viewModel.update { it.copy(serverName = v) } },
                        label = { Text("服务器名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MemoryInput(
                            label = "最小内存 MB",
                            value = state.minMem,
                            onChange = { mb -> viewModel.update { s -> s.copy(minMem = mb) } },
                            modifier = Modifier.weight(1f),
                        )
                        MemoryInput(
                            label = "最大内存 MB",
                            value = state.maxMem,
                            onChange = { mb -> viewModel.update { s -> s.copy(maxMem = mb) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    OutlinedTextField(
                        value = state.jvmArgs,
                        onValueChange = { v -> viewModel.update { it.copy(jvmArgs = v) } },
                        label = { Text("额外 JVM 参数（空格分隔，可留空）") },
                        placeholder = { Text("如：-Ddisable.watchdog=true") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = viewModel::start,
                            enabled = !state.running,
                            modifier = Modifier.weight(1f),
                        ) { Text("启动") }
                        OutlinedButton(
                            onClick = viewModel::stop,
                            enabled = state.running,
                            modifier = Modifier.weight(1f),
                        ) { Text("停止") }
                    }
                }
            }

            state.message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(12.dp))
            Text("运行日志", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Card(
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = ConsoleBackground),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    if (state.logs.isEmpty()) {
                        Text("暂无日志。点击启动后此处会回显服务端输出。", color = ConsoleSystem, style = ConsoleTextStyle)
                    } else {
                        state.logs.takeLast(400).forEach { line ->
                            Text(line, color = ConsoleText, style = ConsoleTextStyle)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MemoryInput(
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { v -> v.toIntOrNull()?.let(onChange) },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
    )
}

/** 简易下拉选择框（避免引入 ExposedDropdownMenu 实验性 API）。 */
@Composable
private fun DropdownField(
    label: String,
    items: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            label = { Text(label) },
            singleLine = true,
            enabled = enabled,
            readOnly = true,
            trailingIcon = {
                IconButton(onClick = { if (enabled) expanded = true }, enabled = enabled) {
                    Text(if (expanded) "▲" else "▼", style = MaterialTheme.typography.labelMedium)
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        expanded = false
                        if (item != selected) onSelect(item)
                    },
                )
            }
        }
    }
}
