package com.mslx.console.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mslx.console.data.localengine.ServerFiles
import kotlinx.coroutines.launch

/**
 * 本机实例设置：直接编辑私有目录下 `filesDir/mslx/servers/<dirName>` 的实例元数据与 `server.properties`，
 * 并提供 eula.txt / server.properties 的文本编辑入口（对应 Daemon 实例的「文件管理」）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalInstanceSettingsScreen(
    dirName: String,
    onBack: () -> Unit,
) {
    val viewModel: LocalInstanceSettingsViewModel = viewModel(
        key = "local_instance_settings_$dirName",
        factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                LocalInstanceSettingsViewModel(app, dirName)
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var editingFile by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.message.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("本机实例设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.meta != null) {
                        TextButton(onClick = viewModel::save, enabled = !state.saving) {
                            if (state.saving) {
                                CircularProgressIndicator(modifier = Modifier.padding(2.dp), strokeWidth = 2.dp)
                            } else {
                                Text("保存", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        val meta = state.meta
        when {
            state.loading -> Column(
                Modifier.fillMaxSize().padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }

            meta == null -> Column(
                Modifier.fillMaxSize().padding(innerPadding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(state.error ?: "无法加载实例", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::load) { Text("重试") }
            }

            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "直接读写本机私有目录（filesDir/mslx/servers），改动在下次启动生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // 显示名不可改目录（目录名固定为 dirName），仅改 server 名称展示与 MOTD
                LabeledField("服务器名称（instance.json）", meta.name) { v ->
                    viewModel.update { it.copy(name = v) }
                }
                LabeledField("MOTD", meta.motd) { v -> viewModel.update { it.copy(motd = v) } }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("端口", meta.serverPort, Modifier.weight(1f)) { p -> viewModel.update { it.copy(serverPort = p) } }
                    NumberField("最大玩家", meta.maxPlayers, Modifier.weight(1f)) { p -> viewModel.update { it.copy(maxPlayers = p) } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("最小内存 MB", meta.minMemMb, Modifier.weight(1f)) { p -> viewModel.update { it.copy(minMemMb = p) } }
                    NumberField("最大内存 MB", meta.maxMemMb, Modifier.weight(1f)) { p -> viewModel.update { it.copy(maxMemMb = p) } }
                }
                LabeledField("额外 JVM 参数", meta.jvmArgs) { v -> viewModel.update { it.copy(jvmArgs = v.trim()) } }

                SwitchRow("正版验证 online-mode", meta.onlineMode) { v -> viewModel.update { it.copy(onlineMode = v) } }
                SwitchRow("后台保活（常驻通知）", meta.keepAlive) { v -> viewModel.update { it.copy(keepAlive = v) } }
                SwitchRow("Serial GC（省内存）", meta.useSerialGc) { v -> viewModel.update { it.copy(useSerialGc = v) } }

                // Java 运行时
                if (state.runtimeOptions.size > 1) {
                    SettingCard("Java 运行时（${state.jreAbi}）") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.runtimeOptions.forEach { opt ->
                                val selected = opt.id == meta.runtimeId
                                val suffix = when {
                                    !opt.supported -> "·不可用"
                                    !opt.installed -> "·未装"
                                    else -> ""
                                }
                                if (selected) {
                                    OutlinedButton(onClick = {}) { Text(opt.label + suffix) }
                                } else {
                                    TextButton(
                                        onClick = { viewModel.selectRuntime(opt.id) },
                                        enabled = opt.supported,
                                    ) {
                                        Text(opt.label + suffix)
                                    }
                                }
                            }
                        }
                        Text(
                            "未安装的请到「设置 → 本机运行时」安装；标「不可用」的 Java 版本暂无 Android 构建。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // 文本文件编辑
                SettingCard("配置文本") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { editingFile = ServerFiles.PROPERTIES_NAME }, modifier = Modifier.weight(1f)) {
                            Text("server.properties")
                        }
                        OutlinedButton(onClick = { editingFile = ServerFiles.EULA_NAME }, modifier = Modifier.weight(1f)) {
                            Text("eula.txt")
                        }
                    }
                }

                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    editingFile?.let { name ->
        FileEditorDialog(
            title = name,
            initial = remember(name) { viewModel.readFile(name) },
            onDismiss = { editingFile = null },
            onSave = { content ->
                if (viewModel.writeFile(name, content)) {
                    scope.launch { snackbarHostState.showSnackbar("已写入 $name") }
                } else {
                    scope.launch { snackbarHostState.showSnackbar("写入失败") }
                }
                editingFile = null
            },
        )
    }
}

@Composable
private fun SettingCard(title: String, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun LabeledField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NumberField(label: String, value: Int, modifier: Modifier = Modifier, onValueChange: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { t -> t.toIntOrNull()?.let(onValueChange) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun FileEditorDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(title) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑 $title") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                minLines = 8,
                maxLines = 16,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
