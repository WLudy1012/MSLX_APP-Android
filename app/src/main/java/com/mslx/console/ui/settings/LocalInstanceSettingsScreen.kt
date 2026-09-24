package com.mslx.console.ui.settings

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mslx.console.data.localengine.InstanceStorage
import com.mslx.console.data.localengine.LocalInstanceStore
import com.mslx.console.data.localengine.LocalStorage
import com.mslx.console.data.localengine.ServerFiles
import kotlinx.coroutines.launch

/**
 * 本机实例设置：直接编辑实例目录（公共或应用私有）下的 `instance.json` 元数据
 * 与 `server.properties`，并提供 eula.txt / server.properties 的文本编辑入口（对应 Daemon 实例的「文件管理」）。
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

    // 从系统「所有文件访问」授权页回来时重读权限，否则公共目录实例会一直显示未授权
    LifecycleResumeEffect(Unit) {
        viewModel.refreshStorageAccess()
        onPauseOrDispose { }
    }

    Scaffold(
        // 页面透明：透出全局毛玻璃背景层
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
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
                    "直接读写下面这个实例目录，改动在下次启动生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // 存放位置：公共目录给出能在文件管理器里直接打开的绝对路径
                SettingCard("实例目录（${state.storage.label}）") {
                    Text(state.dirPath, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    if (state.storage == InstanceStorage.PUBLIC && !state.publicStorageGranted) {
                        Text(
                            "该实例在公共目录，但未授予「所有文件访问」，现在无法启动。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = {
                            if (!viewModel.openPublicStorageSettings()) {
                                scope.launch { snackbarHostState.showSnackbar("未能打开系统授权页，请手动到设置里查找") }
                            }
                        }) { Text("去系统授权") }
                    }
                }

                // 显示名不可改目录（目录名固定为 dirName），仅改 server 名称展示与 MOTD
                LabeledField("服务器名称（instance.json）", meta.name) { v ->
                    viewModel.update { it.copy(name = v) }
                }
                LabeledField("MOTD", meta.motd) { v -> viewModel.update { it.copy(motd = v) } }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("端口", meta.serverPort, Modifier.weight(1f)) { p -> viewModel.update { it.copy(serverPort = p) } }
                    NumberField("最大玩家", meta.maxPlayers, Modifier.weight(1f)) { p -> viewModel.update { it.copy(maxPlayers = p) } }
                }

                // 启动性能参数：默认跟随「设置 → 本机运行时与开服设置」的全局默认，需要差异时才独立覆盖
                val eff = state.effective ?: meta
                val inherit = meta.inheritGlobal
                SwitchRow("跟随全局默认（内存 / JVM 参数 / GC / 保活）", inherit) { v -> viewModel.setInheritGlobal(v) }
                if (inherit) {
                    Text(
                        "当前生效：最小 ${state.globals.minMemMb} MB · 最大 ${state.globals.maxMemMb} MB · " +
                            "JVM 参数「${state.globals.jvmArgs.ifBlank { "无" }}」；要改请去「设置 → 本机运行时与开服设置」。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("最小内存 MB", eff.minMemMb, Modifier.weight(1f), enabled = !inherit) { p -> viewModel.update { it.copy(minMemMb = p) } }
                    NumberField("最大内存 MB", eff.maxMemMb, Modifier.weight(1f), enabled = !inherit) { p -> viewModel.update { it.copy(maxMemMb = p) } }
                }
                LabeledField("额外 JVM 参数", eff.jvmArgs, enabled = !inherit) { v -> viewModel.update { it.copy(jvmArgs = v.trim()) } }

                SwitchRow("正版验证 online-mode", meta.onlineMode) { v -> viewModel.update { it.copy(onlineMode = v) } }
                SwitchRow("后台保活（常驻通知）", eff.keepAlive, enabled = !inherit) { v -> viewModel.update { it.copy(keepAlive = v) } }
                SwitchRow("Serial GC（省内存）", eff.useSerialGc, enabled = !inherit) { v -> viewModel.update { it.copy(useSerialGc = v) } }

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
                                    // 已选中项不可点（避免“点了没反应”的空按钮）
                                    Box(
                                        modifier = Modifier
                                            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp))
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                    ) {
                                        Text(
                                            opt.label + suffix,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
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
                            "未安装的请到「设置 → 本机运行时与开服设置」安装；标「不可用」的 Java 版本暂无 Android 构建。",
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

/**
 * 本机实例的二级子页兜底：「文件管理 / 插件模组 / server.properties」的 App 内统一体验只对接了 Daemon API；
 * 本机实例的配置文本与 Java 运行时在本机实例设置页提供，
 * 而**公共目录**实例本身就能用系统文件管理器直接放插件/改配置（路径在本机实例设置页顶部）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalInstancePendingScreen(
    dirName: String,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val storage = remember(dirName) { LocalInstanceStore.storageOf(context, dirName) }
    val dirPath = remember(dirName, storage) { LocalInstanceStore.dir(context, dirName).absolutePath }
    val canOpenInFileManager = storage == InstanceStorage.PUBLIC && LocalStorage.publicStorageGranted()

    Scaffold(
        // 页面透明：透出全局毛玻璃背景层
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("本机实例", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "本机的文件与插件直接在实例目录里管理",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "这一页对接的是远程 Daemon 的文件接口。本机实例的文件就在下面这个目录里，" +
                    "用系统文件管理器就能放 mods/、plugins/ 与改配置文件（改完重启服务生效）。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingCard("实例目录（${storage.label}）") {
                Text(dirPath, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("MSLX 实例目录", dirPath))
                        scope.launch { snackbarHostState.showSnackbar("已复制目录路径") }
                    }) { Text("复制路径") }
                    if (canOpenInFileManager) {
                        TextButton(onClick = {
                            val opened = openPublicDirInFileManager(context, dirPath)
                            if (!opened) {
                                scope.launch { snackbarHostState.showSnackbar("未找到可打开该目录的文件管理器，已改为复制路径") }
                                openPublicDirInFileManager(context, dirPath, copyOnly = true)
                            }
                        }) { Text("在文件管理器打开") }
                    }
                }
            }
            if (storage == InstanceStorage.PRIVATE) {
                Text(
                    "提示：该实例在应用私有目录，系统文件管理器看不到；新建时选「公共目录」可直接拖文件进去。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text("本机实例设置（server.properties / eula / Java 运行时）")
            }
            TextButton(onClick = onBack) { Text("返回") }
        }
    }
}

/**
 * 用系统文件管理器打开公共目录下的实例目录（SAF 的 primary:<相对路径> 文档 URI）。
 * [copyOnly] 为 true 时只把路径写回剪贴板（没有可用文件管理器时的兜底）；成功打开返回 true。
 */
private fun openPublicDirInFileManager(
    context: Context,
    dirPath: String,
    copyOnly: Boolean = false,
): Boolean {
    if (copyOnly) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("MSLX 实例目录", dirPath))
        return true
    }
    val externalRoot = runCatching { android.os.Environment.getExternalStorageDirectory().absolutePath }.getOrDefault("")
    val relative = dirPath.removePrefix(externalRoot).removePrefix("/")
    if (relative.isBlank() || relative == dirPath) return false
    return runCatching {
        val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$relative")
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
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
private fun LabeledField(label: String, value: String, enabled: Boolean = true, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun NumberField(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChange: (Int) -> Unit,
) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { t -> t.toIntOrNull()?.let(onValueChange) },
        enabled = enabled,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
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
