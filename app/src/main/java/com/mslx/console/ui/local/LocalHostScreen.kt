package com.mslx.console.ui.local

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mslx.console.ui.theme.ConsoleBackground
import com.mslx.console.ui.theme.ConsoleSystem
import com.mslx.console.ui.theme.ConsoleText
import com.mslx.console.ui.theme.ConsoleTextStyle

/**
 * 本机开服（P0 实验页）：填写 Android JRE 的 java 路径与本机 server.jar 路径，
 * 直接在本机启动 Minecraft 服务端并回显日志。仅作可行性验证，正式版需接入 JRE 自动下载与实例管理。
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
                title = { Text("本机开服（实验）") },
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
                "P0 实验：需在设备上准备 Android(bionic) JRE，并把 java 可执行文件与 server.jar 路径填到下方（默认在 App 外部目录 jre/bin/java 与 server.jar）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.javaPath,
                        onValueChange = { v -> viewModel.update { it.copy(javaPath = v) } },
                        label = { Text("Java 可执行文件") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.jarPath,
                        onValueChange = { v -> viewModel.update { it.copy(jarPath = v) } },
                        label = { Text("server.jar 路径") },
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
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = ConsoleBackground),
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
