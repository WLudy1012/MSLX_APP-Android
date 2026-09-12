package com.mslx.console.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 本机开服的默认性能设置：内存、额外 JVM 参数、后台保活（前台服务）、GC 选择。
 * 这些值是本机开服页的默认值，页面内仍可临时覆盖。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalServerSettingsScreen(
    onBack: () -> Unit,
    viewModel: LocalServerSettingsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("本地开服设置") },
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
                "本机开服在 App 进程内启动 JVM（内置运行时为 Java 17，核心需 ≤1.20.4）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("内存", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = state.minMem,
                            onValueChange = { v -> viewModel.update { it.copy(minMem = v.filter(Char::isDigit).take(5)) } },
                            label = { Text("最小内存 MB") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = state.maxMem,
                            onValueChange = { v -> viewModel.update { it.copy(maxMem = v.filter(Char::isDigit).take(5)) } },
                            label = { Text("最大内存 MB") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        "建议 1024–2048MB：Android 会给每个 App 设内存上限，给太大反而会被系统直接杀掉。手机会同时跑服务端与客户端时留足余量。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("JVM 参数", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = state.jvmArgs,
                        onValueChange = { v -> viewModel.update { it.copy(jvmArgs = v) } },
                        label = { Text("额外 JVM 参数（空格分隔）") },
                        placeholder = { Text("如：-Ddisable.watchdog=true -XX:+UseCompressedOops") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("使用 SerialGC", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Android 上 G1 表现不稳，默认开启；关闭后走运行时默认 GC。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.useSerialGc,
                            onCheckedChange = { v -> viewModel.update { it.copy(useSerialGc = v) } },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("后台运行", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("保活（前台服务 + 常驻通知）", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "开启后退出 App / 划掉最近任务，服务端仍继续运行（通知栏可停止）；关闭则只在 App 前台时运行。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.keepAlive,
                            onCheckedChange = { v -> viewModel.update { it.copy(keepAlive = v) } },
                        )
                    }
                }
            }

            state.message?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = viewModel::save, modifier = Modifier.weight(1f)) { Text("保存") }
                OutlinedButton(onClick = viewModel::resetDefaults, modifier = Modifier.weight(1f)) { Text("恢复默认") }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
