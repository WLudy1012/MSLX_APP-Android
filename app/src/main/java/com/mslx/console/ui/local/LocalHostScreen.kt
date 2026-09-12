package com.mslx.console.ui.local

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mslx.console.ui.theme.ConsoleBackground
import com.mslx.console.ui.theme.ConsoleSystem
import com.mslx.console.ui.theme.ConsoleText
import com.mslx.console.ui.theme.ConsoleTextStyle

/**
 * 本机开服（进程内 JVM）：内嵌 PojavLauncher 的 Android JRE + MSLAPI 服务端核心，
 * 用 native dlopen(libjvm.so) + JNI_CreateJavaVM 在 App 进程内起服。
 * 不依赖 Termux、不依赖守护进程、不需要 root。
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
                "在手机上直接开服：JVM 在 App 进程内启动，无需 Termux / 守护进程 / WebUI。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            // ---------- 1. Java 运行时 ----------
            Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Java 运行时（JRE）", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.weight(1f))
                        Text(
                            if (state.jreInstalled) "已安装" else "未安装",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.jreInstalled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        "Java ${state.javaMajor} · ${state.jreAbi} · " +
                            if (state.jreEmbedded) "随 APK 内嵌（离线可用）" else "assets 未内嵌该 ABI，将按预设地址下载",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.jreInfo?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(
                        "运行时来自 PojavLauncher 的 Android OpenJDK 构建（固定 SHA-256 校验）。" +
                            "安卓 10+ 禁止应用 exec 自己的数据目录，故 JVM 由 native 层在进程内创建，不再使用 bin/java。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.jreInstalling) {
                        LinearProgressIndicator(progress = { state.jreProgress }, modifier = Modifier.fillMaxWidth())
                        Text(
                            "正在安装 JRE… ${(state.jreProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = viewModel::installJre,
                        enabled = !state.jreInstalling,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (state.jreInstalled) "重新安装 JRE" else "安装 JRE")
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
                    Text(
                        "内置运行时为 Java ${state.javaMajor}：请选 ≤1.20.4 的核心（Paper/原版），1.20.5+ 需要 Java 21，暂不支持。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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

            // ---------- 3. 服务端配置 ----------
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
                        ) { Text(if (state.running) "运行中" else "启动") }
                        OutlinedButton(
                            onClick = viewModel::stop,
                            enabled = state.running,
                            modifier = Modifier.weight(1f),
                        ) { Text("停止") }
                    }
                    Text(
                        "注意：JVM 创建后无法在进程内重建，停止服务端后需完全退出 App 才能再次启动；App 被系统杀进程时服务端也会停止。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.jvmCreated && !state.running) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "本进程已创建过 JVM：如需再次开服，请完全退出 App（从最近任务划掉）后重新进入。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            state.message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("运行日志", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = viewModel::clearLogs, enabled = state.logs.isNotEmpty()) { Text("清空") }
            }
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
                        state.logs.takeLast(500).forEach { line ->
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
