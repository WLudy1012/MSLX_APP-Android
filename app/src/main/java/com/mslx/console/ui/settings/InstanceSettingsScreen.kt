package com.mslx.console.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mslx.console.data.ServerRef
import com.mslx.console.data.model.ServerSettings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstanceSettingsScreen(
    ref: ServerRef,
    onBack: () -> Unit,
    onOpenPluginsMods: () -> Unit,
    onOpenServerProps: () -> Unit,
    onOpenFileManager: () -> Unit,
) {
    val viewModel: InstanceSettingsViewModel = viewModel(
        key = "instance_settings_${ref.catalogKey}",
        factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                InstanceSettingsViewModel(app, ref)
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.message.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        // 页面透明：透出全局毛玻璃背景层
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("实例设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.settings != null) {
                        TextButton(
                            onClick = viewModel::save,
                            enabled = !state.saving,
                        ) {
                            if (state.saving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(2.dp),
                                    strokeWidth = 2.dp,
                                )
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
        when {
            state.loading -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            state.error != null && state.settings == null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
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

            state.settings == null -> Unit

            else -> {
                val s = state.settings!!
                val javaType = parseJavaType(s.java, s.dockerImage, s.args, state.localJavas)
                val isDocker = javaType == JavaType.DOCKER_JAVA || javaType == JavaType.DOCKER_CUSTOM ||
                    javaType == JavaType.MCDR_DOCKER_JAVA || javaType == JavaType.MCDR_DOCKER_CUSTOM
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (state.updateProgress != null) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (state.updateError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                                ),
                            ) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(state.updateMessage ?: "正在更新配置", fontWeight = FontWeight.SemiBold)
                                    if (!state.updateError) {
                                        LinearProgressIndicator(
                                            progress = { ((state.updateProgress ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 快捷入口
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            EntryCard("插件 / 模组", Modifier.weight(1f)) { onOpenPluginsMods() }
                            EntryCard("服务器设置", Modifier.weight(1f)) { onOpenServerProps() }
                        }
                    }
                    item {
                        EntryCard("文件管理（查看/编辑 eula.txt 等）", Modifier.fillMaxWidth()) {
                            onOpenFileManager()
                        }
                    }

                    // 基本信息
                    item { SectionTitle("基本信息") }
                    item {
                        TextSetting("实例名称", s.name) { v -> viewModel.update { it.copy(name = v) } }
                    }
                    item {
                        TextSetting("基础路径", s.base) { v -> viewModel.update { it.copy(base = v) } }
                    }
                    item {
                        TextSetting("过期时间", s.expireTime, placeholder = "yyyy-MM-dd HH:mm:ss") { v ->
                            viewModel.update { it.copy(expireTime = v.ifBlank { null }) }
                        }
                    }

                    // 启动配置
                    item { SectionTitle("启动配置") }
                    item {
                        JavaEnvironmentSelector(
                            java = s.java,
                            dockerImage = s.dockerImage,
                            args = s.args,
                            onlineVersions = state.onlineJavaVersions,
                            localJavas = state.localJavas,
                            onJavaChanged = { v -> viewModel.update { it.copy(java = v) } },
                            onDockerImageChanged = { v -> viewModel.update { it.copy(dockerImage = v) } },
                        )
                    }
                    item {
                        TextSetting("核心文件名", s.core) { v -> viewModel.update { it.copy(core = v) } }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            NumberSetting("最小内存 MB", s.minM, Modifier.weight(1f)) { v ->
                                viewModel.update { it.copy(minM = v) }
                            }
                            NumberSetting("最大内存 MB", s.maxM, Modifier.weight(1f)) { v ->
                                viewModel.update { it.copy(maxM = v) }
                            }
                        }
                    }
                    item {
                        TextSetting("启动参数", s.args, placeholder = "额外 JVM 参数") { v ->
                            viewModel.update { it.copy(args = v) }
                        }
                    }

                    // 运行行为
                    item { SectionTitle("运行行为") }
                    item {
                        SwitchSetting("崩溃自动重启", s.autoRestart == true) { v ->
                            viewModel.update { it.copy(autoRestart = v) }
                        }
                    }
                    item {
                        SwitchSetting("强制自动重启", s.forceAutoRestart == true) { v ->
                            viewModel.update { it.copy(forceAutoRestart = v) }
                        }
                    }
                    item {
                        SwitchSetting("守护程序启动时自启", s.runOnStartup == true) { v ->
                            viewModel.update { it.copy(runOnStartup = v) }
                        }
                    }
                    item {
                        SwitchSetting("忽略 EULA", s.ignoreEula == true) { v ->
                            viewModel.update { it.copy(ignoreEula = v) }
                        }
                    }
                    item {
                        SwitchSetting("强制 JVM UTF-8", s.forceJvmUTF8 == true) { v ->
                            viewModel.update { it.copy(forceJvmUTF8 = v) }
                        }
                    }
                    item {
                        SwitchSetting("允许原始 ASCII 颜色", s.allowOriginASCIIColors == true) { v ->
                            viewModel.update { it.copy(allowOriginASCIIColors = v) }
                        }
                    }
                    item {
                        SwitchSetting("监控玩家", s.monitorPlayers == true) { v ->
                            viewModel.update { it.copy(monitorPlayers = v) }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            NumberSetting("强制退出延迟(秒)", s.forceExitDelay, Modifier.weight(1f)) { v ->
                                viewModel.update { it.copy(forceExitDelay = v) }
                            }
                            TextSetting("停止命令", s.stopCommand, Modifier.weight(1f)) { v ->
                                viewModel.update { it.copy(stopCommand = v) }
                            }
                        }
                    }
                    item {
                        TextSetting("外置登录地址(Yggdrasil)", s.yggdrasilApiAddr, placeholder = "留空使用正版/内置") { v ->
                            viewModel.update { it.copy(yggdrasilApiAddr = v) }
                        }
                    }

                    // 编码
                    item { SectionTitle("编码") }
                    item {
                        DropdownSetting("输入编码", listOf("utf-8", "gbk"), s.inputEncoding) { v ->
                            viewModel.update { it.copy(inputEncoding = v) }
                        }
                    }
                    item {
                        DropdownSetting("输出编码", listOf("utf-8", "gbk"), s.outputEncoding) { v ->
                            viewModel.update { it.copy(outputEncoding = v) }
                        }
                    }
                    item {
                        DropdownSetting("文件编码", listOf("utf-8", "utf-8-bom", "gbk"), s.fileEncoding) { v ->
                            viewModel.update { it.copy(fileEncoding = v) }
                        }
                    }

                    // 目录路径
                    item { SectionTitle("目录路径") }
                    item {
                        TextSetting("server.properties 路径", s.serverPropertiesPath) { v ->
                            viewModel.update { it.copy(serverPropertiesPath = v) }
                        }
                    }
                    item {
                        TextSetting("插件目录", s.pluginsPath) { v ->
                            viewModel.update { it.copy(pluginsPath = v) }
                        }
                    }
                    item {
                        TextSetting("模组目录", s.modsPath) { v ->
                            viewModel.update { it.copy(modsPath = v) }
                        }
                    }
                    item {
                        TextSetting("地图目录", s.worldPath) { v ->
                            viewModel.update { it.copy(worldPath = v) }
                        }
                    }
                    item {
                        TextSetting("Region 目录", s.regionPath) { v ->
                            viewModel.update { it.copy(regionPath = v) }
                        }
                    }

                    // 备份
                    item { SectionTitle("备份") }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            NumberSetting("备份数量上限", s.backupMaxCount, Modifier.weight(1f)) { v ->
                                viewModel.update { it.copy(backupMaxCount = v) }
                            }
                            NumberSetting("备份间隔(秒)", s.backupDelay, Modifier.weight(1f)) { v ->
                                viewModel.update { it.copy(backupDelay = v) }
                            }
                        }
                    }
                    item {
                        TextSetting("备份路径", s.backupPath) { v ->
                            viewModel.update { it.copy(backupPath = v) }
                        }
                    }

                    // FRP
                    item { SectionTitle("内网穿透(FRP)") }
                    item {
                        TextSetting("绑定 FRP ID", s.bindFrpId, placeholder = "8 位数字，多个用逗号分隔") { v ->
                            viewModel.update { it.copy(bindFrpId = v.ifBlank { null }) }
                        }
                    }

                    // Docker（仅当启动方式为 Docker 时显示）
                    if (isDocker) {
                        item { SectionTitle("Docker 运行配置") }
                        item {
                            TextSetting("镜像", s.dockerImage) { v ->
                                viewModel.update { it.copy(dockerImage = v) }
                            }
                        }
                        item {
                            TextSetting("工作目录", s.dockerWorkingDir) { v ->
                                viewModel.update { it.copy(dockerWorkingDir = v) }
                            }
                        }
                        item {
                            TextSetting("端口映射", s.dockerPorts, placeholder = "宿主机端口:容器端口") { v ->
                                viewModel.update { it.copy(dockerPorts = v) }
                            }
                        }
                        item {
                            DropdownSetting("网络模式", listOf("bridge", "host", "none"), s.dockerNetworkMode) { v ->
                                viewModel.update { it.copy(dockerNetworkMode = v) }
                            }
                        }
                        item {
                            TextSetting("网络别名", s.dockerNetworkAlias) { v ->
                                viewModel.update { it.copy(dockerNetworkAlias = v.ifBlank { null }) }
                            }
                        }
                        item {
                            TextSetting("挂载卷", s.dockerVolumes, placeholder = "/宿主机:/容器,多个用逗号") { v ->
                                viewModel.update { it.copy(dockerVolumes = v.ifBlank { null }) }
                            }
                        }
                        item {
                            TextSetting("环境变量", s.dockerEnvVars, placeholder = "KEY=VALUE,多个用逗号") { v ->
                                viewModel.update { it.copy(dockerEnvVars = v.ifBlank { null }) }
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                NumberSetting("CPU 限制 %", s.dockerCpuPercentage, Modifier.weight(1f)) { v ->
                                    viewModel.update { it.copy(dockerCpuPercentage = v) }
                                }
                                TextSetting("指定 CPU 核心", s.dockerCpuCores, Modifier.weight(1f), placeholder = "0,1 或 0-3") { v ->
                                    viewModel.update { it.copy(dockerCpuCores = v.ifBlank { null }) }
                                }
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                NumberSetting("最大内存 MB", s.dockerMaxMemoryMb, Modifier.weight(1f)) { v ->
                                    viewModel.update { it.copy(dockerMaxMemoryMb = v) }
                                }
                                NumberSetting("最大交换 MB", s.dockerMaxSwapMb, Modifier.weight(1f)) { v ->
                                    viewModel.update { it.copy(dockerMaxSwapMb = v) }
                                }
                            }
                        }
                        item {
                            TextSetting("磁盘限制", s.dockerMaxStorage, placeholder = "如 10g 或 500m") { v ->
                                viewModel.update { it.copy(dockerMaxStorage = v.ifBlank { null }) }
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                TextSetting("上传限速", s.dockerUploadRate, Modifier.weight(1f), placeholder = "如 1mb") { v ->
                                    viewModel.update { it.copy(dockerUploadRate = v.ifBlank { null }) }
                                }
                                TextSetting("下载限速", s.dockerDownloadRate, Modifier.weight(1f), placeholder = "如 1mb") { v ->
                                    viewModel.update { it.copy(dockerDownloadRate = v.ifBlank { null }) }
                                }
                            }
                        }
                        item {
                            TextSetting("额外参数", s.dockerExtraArgs, placeholder = "docker run 原生参数") { v ->
                                viewModel.update { it.copy(dockerExtraArgs = v.ifBlank { null }) }
                            }
                        }
                        item {
                            TextSetting("额外 Hosts", s.dockerExtraHosts, placeholder = "host.mslx.internal:host-gateway") { v ->
                                viewModel.update { it.copy(dockerExtraHosts = v.ifBlank { null }) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== 可复用小组件 ====================

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun TextSetting(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value.orEmpty(),
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun NumberSetting(
    label: String,
    value: Int?,
    modifier: Modifier = Modifier,
    onValueChange: (Int?) -> Unit,
) {
    OutlinedTextField(
        value = value?.toString() ?: "",
        onValueChange = { text -> onValueChange(text.toIntOrNull()) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchSetting(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ChipSetting(
    label: String,
    options: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    label = { Text(option) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownSetting(
    label: String,
    options: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = selected.orEmpty(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            shape = RoundedCornerShape(12.dp),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun EntryCard(
    title: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = modifier,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp, horizontal = 8.dp),
        )
    }
}
