package com.mslx.console.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.LinearProgressIndicator
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
import com.mslx.console.data.localengine.ShizukuStatus

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
                "本机开服默认在 App 进程内启动 JVM；运行时可选 Java 17 / 21（Java 8 暂无 Android 构建）。开启「增强模式(Shizuku)」后以独立子进程运行，支持多实例并发、停止后免重启 App 再起。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            // 本机 Java 运行时（JRE 安装/版本）
            Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("本机运行时（${state.jreAbi.ifBlank { "当前设备" }}）", style = MaterialTheme.typography.titleSmall)
                    state.runtimeOptions.forEach { rt ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(rt.label, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    when {
                                        !rt.supported -> rt.unavailableReason.ifBlank { "暂无 Android 构建" }
                                        rt.installed -> "已安装${if (rt.embedded) " · 内嵌" else ""}"
                                        rt.embedded -> "未安装 · 可离线安装（内嵌）"
                                        else -> "未安装 · 需下载"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (rt.supported) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                            }
                            when {
                                rt.installed -> Text("已就绪", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                !rt.supported -> Text("不可用", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                                state.jreInstalling && state.selectedRuntimeId == rt.id ->
                                    LinearProgressIndicator(progress = { state.jreProgress }, modifier = Modifier.width(96.dp))
                                else -> OutlinedButton(onClick = { viewModel.installJre(rt.id) }, enabled = !state.jreInstalling) { Text("安装") }
                            }
                        }
                    }
                    state.jreError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

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

            Spacer(Modifier.height(12.dp))

            Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("增强模式（Shizuku）", style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("以 shell 权限运行真正的 java 子进程", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "开启后服务端跑在独立子进程：可多实例并发、停止后无需重启 App 即可再起、支持 Java 17 / 21。需先安装并授权 Shizuku；不可用时启动会自动回退到进程内 JVM。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = state.useShizuku,
                            onCheckedChange = viewModel::toggleEnhanced,
                        )
                    }
                    Text(
                        state.shizukuStatusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.shizukuStatus == ShizukuStatus.READY) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (state.shizukuStatus == ShizukuStatus.NO_PERMISSION) {
                        OutlinedButton(onClick = viewModel::authorizeShizuku) { Text("授权 Shizuku") }
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
