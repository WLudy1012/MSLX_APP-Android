package com.mslx.console.ui.create

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CreateInstanceScreen(
    onOpenHome: () -> Unit,
    onOpenInstances: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenConsole: (Long) -> Unit,
    viewModel: CreateInstanceViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.message.collect { snackbarHostState.showSnackbar(it) }
    }

    // 重按"新建"tab（Dock 在 NavHost 外层）→ 重置表单
    LaunchedEffect(Unit) {
        CreateResetBus.events.collect { viewModel.reset() }
    }

    val jarLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val name = queryDisplayName(context, uri)
                viewModel.uploadCore(uri, name)
            }
        }
    }
    val packageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                val name = queryDisplayName(context, uri)
                viewModel.uploadPackage(uri, name)
            }
        }
    }

    Scaffold(
        // Dock 已提升至 NavHost 外层；页面 Scaffold 不再自绘底栏
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("新建实例", fontWeight = FontWeight.Bold) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when {
            state.success -> SuccessContent(
                serverId = state.createdServerId,
                onOpenConsole = {
                    val id = state.createdServerId.toLongOrNull() ?: 0L
                    // 离开创建页前重置表单，避免下次进入仍停留在成功页/残留旧数据
                    viewModel.reset()
                    onOpenConsole(id)
                },
                onReset = viewModel::reset,
                onBackToList = {
                    // 返回实例列表同样重置，防止删除实例后误进旧实例控制台
                    viewModel.reset()
                    onOpenInstances()
                },
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            )

            state.creating -> CreatingContent(
                serverId = state.createdServerId,
                progress = state.creationProgress,
                logs = state.creationLogs,
                onCancel = viewModel::cancelCreation,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            )

            else -> FormContent(
                state = state,
                onUpdate = viewModel::update,
                onModeChange = viewModel::setMode,
                onNext = viewModel::nextStep,
                onPrev = viewModel::prevStep,
                onOpenCoreSelector = viewModel::openCoreSelector,
                onClearCore = viewModel::clearCoreSelection,
                onRemoveUpload = viewModel::removeUploadedCore,
                onPickJar = { jarLauncher.launch("application/java-archive") },
                onPickPackage = { packageLauncher.launch("*/*") },
                onSubmit = viewModel::submit,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            )
        }
    }

    if (state.coreSelectorVisible) {
        // 基岩版模式（mode==3）只展示基岩版分类：全量分类保留在 state，显示时按 mode 过滤
        val visibleCategories = if (state.mode == 3) {
            state.coreCategories.filter { it.key == "bedrock" }
        } else {
            state.coreCategories
        }
        CoreSelectorDialog(
            categories = visibleCategories,
            selectedCategoryKey = state.selectedCategoryKey,
            selectedCoreName = state.selectedCoreName,
            versions = state.coreVersions,
            versionDescription = state.coreVersionDescription,
            builds = state.coreBuilds,
            buildsVisible = state.buildsVisible,
            loadingVersions = state.loadingVersions,
            loading = state.coreSelectorLoading,
            onDismiss = viewModel::closeCoreSelector,
            onSelectCategory = viewModel::selectCategory,
            onSelectCoreName = viewModel::selectCoreName,
            onSelectVersion = viewModel::selectVersion,
            onSelectBuild = viewModel::selectBuild,
        )
    }
}

private val MODES = listOf(
    1 to "快速模式",
    2 to "整合包",
    3 to "基岩版",
    4 to "MCDR",
    10 to "自定义",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FormContent(
    state: CreateInstanceUiState,
    onUpdate: ((CreateInstanceUiState) -> CreateInstanceUiState) -> Unit,
    onModeChange: (Int) -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onOpenCoreSelector: () -> Unit,
    onClearCore: () -> Unit,
    onRemoveUpload: () -> Unit,
    onPickJar: () -> Unit,
    onPickPackage: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val steps = wizardSteps(state.mode)
    val current = steps.getOrNull(state.step)
    Column(modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        // 模式选择
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MODES.forEach { (value, label) ->
                FilterChip(selected = state.mode == value, onClick = { onModeChange(value) }, label = { Text(label) })
            }
        }
        Spacer(Modifier.height(12.dp))
        StepIndicator(steps, state.step)
        Spacer(Modifier.height(12.dp))

        // 切换步骤时内容淡入淡出动画
        androidx.compose.animation.AnimatedContent(
            targetState = state.step,
            transitionSpec = {
                (fadeIn(tween(180)) + slideInHorizontally(tween(180)) { it / 8 })
                    .togetherWith(fadeOut(tween(120)) + slideOutHorizontally(tween(120)) { -it / 8 })
            },
            label = "stepContent",
        ) { _ ->
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (current?.key) {
                    "basic" -> BasicStep(state, onUpdate)
                    "core" -> CoreStep(state, onUpdate, onOpenCoreSelector, onClearCore, onRemoveUpload, onPickJar)
                    "package" -> PackageStep(state, onUpdate, onOpenCoreSelector, onClearCore, onPickPackage)
                    "java" -> JavaStep(state, onUpdate)
                    "mcdr" -> McdrStep(state, onUpdate)
                    "resource" -> ResourceStep(state, onUpdate)
                    "confirm" -> ConfirmStep(state)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.step > 0) {
                OutlinedButton(onClick = onPrev, modifier = Modifier.weight(1f)) { Text("上一步") }
            }
            if (current?.key == "confirm") {
                Button(onClick = onSubmit, enabled = !state.submitting, modifier = Modifier.weight(1f)) {
                    if (state.submitting) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                    }
                    Text(if (state.submitting) "提交中..." else "确认创建", fontWeight = FontWeight.SemiBold)
                }
            } else {
                Button(onClick = onNext, modifier = Modifier.weight(1f)) { Text("下一步") }
            }
        }
    }
}

@Composable
private fun StepIndicator(steps: List<WizardStep>, current: Int) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("步骤 ${current + 1} / ${steps.size}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            Text(steps.getOrNull(current)?.title.orEmpty(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(progress = { (current + 1).toFloat() / steps.size }, modifier = Modifier.fillMaxWidth())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BasicStep(state: CreateInstanceUiState, onUpdate: ((CreateInstanceUiState) -> CreateInstanceUiState) -> Unit) {
    SectionCard("基本信息") {
        OutlinedTextField(value = state.name, onValueChange = { v -> onUpdate { it.copy(name = v) } }, label = { Text("实例名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = state.path, onValueChange = { v -> onUpdate { it.copy(path = v) } }, label = { Text("实例路径（选填，Daemon 上的绝对路径）") }, placeholder = { Text("例如: /home/user/下载/") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text("留空时默认创建在 Daemon 数据目录下的 Server 文件夹中", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text("常用 Daemon 路径", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("/home/user/下载/", "/home/user/", "/opt/servers/").forEach { path ->
                FilterChip(selected = state.path == path, onClick = { onUpdate { it.copy(path = path) } }, label = { Text(path) })
            }
        }
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CoreStep(
    state: CreateInstanceUiState,
    onUpdate: ((CreateInstanceUiState) -> CreateInstanceUiState) -> Unit,
    onOpenCoreSelector: () -> Unit,
    onClearCore: () -> Unit,
    onRemoveUpload: () -> Unit,
    onPickJar: () -> Unit,
) {
    SectionCard("服务端核心") {
        if (state.mode != 3) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("online" to "在线下载", "manual" to "本地上传", "custom" to "自定义文件名").forEach { (value, label) ->
                    FilterChip(selected = state.downloadType == value, onClick = { onUpdate { it.copy(downloadType = value) } }, label = { Text(label) })
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (state.mode == 3) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("online" to "在线下载", "url" to "远程下载", "manual" to "本地上传").forEach { (value, label) ->
                    FilterChip(selected = state.downloadType == value, onClick = { onUpdate { it.copy(downloadType = value) } }, label = { Text(label) })
                }
            }
            Spacer(Modifier.height(8.dp))
            when (state.downloadType) {
                "online" -> if (state.core.isNotBlank()) SelectedCoreCard(state.core, onClearCore) else OutlinedButton(onClick = onOpenCoreSelector, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.Build, null); Text("选择基岩版在线核心") }
                "url" -> OutlinedTextField(state.coreUrl, { v -> onUpdate { it.copy(coreUrl = v, coreFileKey = "") } }, label = { Text("基岩版核心远程下载地址") }, placeholder = { Text("https://...") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                else -> UploadRow(state.uploading, state.uploadProgress, state.uploadedFileName, state.coreFileKey.isNotBlank(), onPickJar, onRemoveUpload)
            }
        } else when (state.downloadType) {
            "online" -> {
                if (state.core.isNotBlank()) {
                    SelectedCoreCard(state.core, onClearCore)
                } else {
                    OutlinedButton(onClick = onOpenCoreSelector, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Build, null)
                        Text("点击打开服务端核心选择库")
                    }
                }
            }
            "manual" -> UploadRow(
                uploading = state.uploading,
                progress = state.uploadProgress,
                fileName = state.uploadedFileName,
                hasKey = state.coreFileKey.isNotBlank(),
                onPick = onPickJar,
                onRemove = onRemoveUpload,
            )
            else -> OutlinedTextField(
                value = state.core,
                onValueChange = { v -> onUpdate { it.copy(core = v) } },
                label = { Text("核心文件名") },
                placeholder = { Text("例如: server.jar") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PackageStep(state: CreateInstanceUiState, onUpdate: ((CreateInstanceUiState) -> CreateInstanceUiState) -> Unit, onOpenCoreSelector: () -> Unit, onClearCore: () -> Unit, onPickPackage: () -> Unit) {
    SectionCard("整合包") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("upload" to "本地上传", "url" to "远程下载", "local" to "Daemon 路径").forEach { (value, label) ->
                FilterChip(selected = state.packageType == value, onClick = { onUpdate { it.copy(packageType = value) } }, label = { Text(label) })
            }
        }
        Spacer(Modifier.height(8.dp))
        when (state.packageType) {
            "upload" -> UploadRow(state.uploading, state.uploadProgress, state.uploadedFileName, state.packageFileKey.isNotBlank(), onPickPackage) { onUpdate { it.copy(packageFileKey = "") } }
            "url" -> OutlinedTextField(state.packageUrl, { v -> onUpdate { it.copy(packageUrl = v) } }, label = { Text("整合包下载地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(state.packageLocalPath, { v -> onUpdate { it.copy(packageLocalPath = v) } }, label = { Text("Daemon 上的绝对路径") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("/home/user/下载/", "/home/user/", "/opt/servers/").forEach { path ->
                        FilterChip(selected = state.packageLocalPath == path, onClick = { onUpdate { it.copy(packageLocalPath = path) } }, label = { Text(path) })
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("可选：同时下载服务端核心", style = MaterialTheme.typography.labelLarge)
        if (state.core.isNotBlank()) SelectedCoreCard(state.core, onClearCore) else OutlinedButton(onClick = onOpenCoreSelector, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.Build, null); Text("选择服务端核心") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JavaStep(state: CreateInstanceUiState, onUpdate: ((CreateInstanceUiState) -> CreateInstanceUiState) -> Unit) {
    var pending by remember { mutableStateOf<PendingJava?>(null) }
    val recommended = recommendedJavaFor(state.onlineGameVersion)
    SectionCard("Java 环境") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("online" to "在线下载", "local" to "电脑上的 Java", "env" to "环境变量", "custom" to "自定义路径", "docker" to "Docker").forEach { (value, label) -> FilterChip(state.javaType == value, { onUpdate { it.copy(javaType = value) } }, label = { Text(label) }) }
        }
        Spacer(Modifier.height(8.dp))
        recommended?.let { Text("当前核心建议使用 Java $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        when (state.javaType) {
            "online" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { state.onlineJavaVersions.forEach { version -> FilterChip(state.selectedJavaVersion == version, { if (recommended != null && version.toIntOrNull() != recommended) pending = PendingJava("online", version) else onUpdate { it.copy(selectedJavaVersion = version) } }, label = { Text("Java $version") }) } }
            "local" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { state.localJavas.forEach { java -> val major = parseJavaMajorVersion(java.version); FilterChip(state.customJavaPath == java.path, { if (recommended != null && major != recommended) pending = PendingJava("local", java.version, java.path) else onUpdate { it.copy(customJavaPath = java.path) } }, label = { Text("Java ${java.version} (${java.vendor ?: "未知"})") }) } }
            "env" -> Text("将使用系统环境变量中的 java 命令", style = MaterialTheme.typography.bodySmall)
            "custom" -> OutlinedTextField(state.customJavaPath, { v -> onUpdate { it.copy(customJavaPath = v) } }, label = { Text("Java 可执行文件路径") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            "docker" -> if (state.dockerImageType == "preset") FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("25", "21", "17", "11", "8").forEach { version -> FilterChip(state.dockerImagePresetVersion == version, { if (recommended != null && version.toIntOrNull() != recommended) pending = PendingJava("docker", version) else onUpdate { it.copy(dockerImagePresetVersion = version) } }, label = { Text("Java $version") }) } } else OutlinedTextField(state.dockerCustomImage, { v -> onUpdate { it.copy(dockerCustomImage = v) } }, label = { Text("自定义镜像") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }
    }
    pending?.let { p -> AlertDialog(
        onDismissRequest = { pending = null },
        title = { Text("Java 版本建议") },
        text = { Text("当前服务端版本建议使用 Java ${recommended ?: "对应版本"}，你选择的是 Java ${p.version}。仍要继续吗？") },
        confirmButton = { TextButton(onClick = {
            onUpdate { s ->
                when (p.type) {
                    "local" -> s.copy(customJavaPath = p.localPath.orEmpty())
                    "docker" -> s.copy(dockerImagePresetVersion = p.version)
                    else -> s.copy(selectedJavaVersion = p.version)
                }
            }
            pending = null
        }) { Text("仍然使用") } },
        dismissButton = { TextButton(onClick = { pending = null }) { Text("返回") } },
    ) }
}

/** 暂存的"仍要使用非推荐版本"的选择;本地 Java 额外记录 path,确认时写回 customJavaPath。 */
private data class PendingJava(val type: String, val version: String, val localPath: String? = null)

/** 解析 Java 主版本号:`1.8.0_401` -> 8,`17.0.10` -> 17,`21` -> 21。 */
private fun parseJavaMajorVersion(version: String): Int? {
    val trimmed = version.trim()
    trimmed.toIntOrNull()?.let { return it }
    val match = Regex("^(\\d+)(?:\\.(\\d+))?").find(trimmed) ?: return null
    val first = match.groupValues[1].toIntOrNull() ?: return null
    val second = match.groupValues[2].toIntOrNull()
    return if (first == 1 && second != null) second else first
}


@Composable
private fun McdrStep(state: CreateInstanceUiState, onUpdate: ((CreateInstanceUiState) -> CreateInstanceUiState) -> Unit) {
    SectionCard("MCDR 配置") {
        OutlinedTextField(
            value = state.mcdrPython,
            onValueChange = { v -> onUpdate { it.copy(mcdrPython = v) } },
            label = { Text("Python 可执行文件") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.mcdrHandler,
            onValueChange = { v -> onUpdate { it.copy(mcdrHandler = v) } },
            label = { Text("Handler（选填，自动推断）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = state.mcdrInstall, onCheckedChange = { v -> onUpdate { it.copy(mcdrInstall = v) } })
            Text("自动安装 MCDReforged", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun ResourceStep(state: CreateInstanceUiState, onUpdate: ((CreateInstanceUiState) -> CreateInstanceUiState) -> Unit) {
    var minUnit by remember { mutableStateOf("GB") }
    var maxUnit by remember { mutableStateOf("GB") }
    SectionCard("资源配置") {
        MemoryField("最小内存", state.minM, minUnit, { minUnit = it }, { mb -> onUpdate { it.copy(minM = mb) } })
        Spacer(Modifier.height(8.dp))
        MemoryField("最大内存", state.maxM, maxUnit, { maxUnit = it }, { mb -> onUpdate { it.copy(maxM = mb) } })
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.args,
            onValueChange = { v -> onUpdate { it.copy(args = v) } },
            label = { Text("JVM 启动参数（选填）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = state.ignoreEula, onCheckedChange = { v -> onUpdate { it.copy(ignoreEula = v) } })
            Text("自动同意 EULA", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun ConfirmStep(state: CreateInstanceUiState) {
    SectionCard("确认信息") {
        SummaryRow("实例名称", state.name)
        SummaryRow("实例路径", state.path.ifBlank { "默认路径（Daemon 数据目录/Server）" })
        if (state.mode == 3) {
            SummaryRow("服务端核心", state.core.ifBlank { "基岩版核心" })
        } else {
            SummaryRow("服务端核心", state.core)
        }
        if (state.mode == 2) {
            val pkg = when {
                state.packageFileKey.isNotBlank() -> "已上传整合包"
                state.packageUrl.isNotBlank() -> "远程下载：${state.packageUrl}"
                state.packageLocalPath.isNotBlank() -> "本机路径：${state.packageLocalPath}"
                else -> "未配置"
            }
            SummaryRow("整合包", pkg)
        }
        if (state.mode != 3) {
            SummaryRow("Java 环境", javaDisplay(state))
        }
        if (state.mode == 4) {
            SummaryRow("MCDR Python", state.mcdrPython)
        }
        SummaryRow("最小内存", "${state.minM} MB")
        SummaryRow("最大内存", "${state.maxM} MB")
        SummaryRow("EULA", if (state.ignoreEula) "自动同意" else "手动同意")
        if (state.args.isNotBlank()) {
            SummaryRow("启动参数", state.args)
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, modifier = Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun javaDisplay(state: CreateInstanceUiState): String = when (state.javaType) {
    "online" -> "在线下载 Java ${state.selectedJavaVersion}"
    "local", "custom" -> state.customJavaPath.ifBlank { "未指定路径" }
    "env" -> "环境变量 (java)"
    "docker" -> if (state.dockerImageType == "preset") "Docker 镜像 Java ${state.dockerImagePresetVersion}" else state.dockerCustomImage.ifBlank { "Docker 自定义镜像" }
    else -> "未配置"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UploadRow(
    uploading: Boolean,
    progress: Int,
    fileName: String,
    hasKey: Boolean,
    onPick: () -> Unit,
    onRemove: () -> Unit,
) {
    if (uploading) {
        Column(Modifier.fillMaxWidth()) {
            Text("正在上传: $fileName", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
    } else if (hasKey) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fileName, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, "移除") }
        }
    } else {
        OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, null)
            Text("选择文件并上传")
        }
    }
}

@Composable
private fun SelectedCoreCard(core: String, onClear: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(core, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onClear) { Icon(Icons.Filled.Delete, "移除") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryField(
    label: String,
    valueMb: Int,
    unit: String,
    onUnitChange: (String) -> Unit,
    onValueChange: (Int) -> Unit,
) {
    // 本地文本态：允许清空/输入中间态（如 "1."），只有合法正数才写回父级，
    // 修复"数字仅剩一位时无法删除"的问题
    var text by remember { mutableStateOf(formatMemory(valueMb, unit)) }
    // 单位切换或外部重置（如 viewModel.reset）时同步显示
    LaunchedEffect(unit, valueMb) {
        text = formatMemory(valueMb, unit)
    }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { input ->
                    val filtered = sanitizeMemoryInput(input)
                    text = filtered
                    val num = filtered.toFloatOrNull()
                    if (num != null && num > 0f) {
                        onValueChange(if (unit == "GB") (num * 1024).toInt().coerceAtLeast(1) else num.toInt().coerceAtLeast(1))
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            FlowRow(modifier = Modifier.padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("GB", "MB").forEach { u ->
                    FilterChip(selected = unit == u, onClick = { onUnitChange(u) }, label = { Text(u) })
                }
            }
        }
    }
}

/** 只保留数字与一个小数点，小数部分最多 2 位（与 [formatMemory] 的精度一致，避免输入回弹）。 */
private fun sanitizeMemoryInput(input: String): String {
    val filtered = input.filter { it.isDigit() || it == '.' }
    val dot = filtered.indexOf('.')
    if (dot < 0) return filtered
    val head = filtered.substring(0, dot + 1)
    val tail = filtered.substring(dot + 1).filter { it.isDigit() }.take(2)
    return head + tail
}

/** MB → 显示文本：GB 模式最多 2 位小数并去掉尾零（1024 → "1"，512 → "0.5"，256 → "0.25"）。 */
private fun formatMemory(valueMb: Int, unit: String): String {
    if (unit != "GB") return valueMb.toString()
    val gb = valueMb / 1024.0
    return String.format(Locale.US, "%.2f", gb).trimEnd('0').trimEnd('.')
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun CreatingContent(
    serverId: String,
    progress: Double,
    logs: List<CreationLog>,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 进度条平滑前进动画
    val animatedProgress by animateFloatAsState(
        targetValue = (progress / 100.0).toFloat().coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 400),
        label = "creationProgress",
    )
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("正在创建实例 ($serverId)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(progress = { animatedProgress }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text("${progress.toInt()}%", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            logs.takeLast(20).forEach { log ->
                Text(log.message, style = MaterialTheme.typography.bodySmall, color = if (log.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onCancel) { Text("取消创建") }
    }
}

@Composable
private fun SuccessContent(
    serverId: String,
    onOpenConsole: () -> Unit,
    onReset: () -> Unit,
    onBackToList: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("🎉 创建成功", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("服务器 ($serverId) 已创建成功", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onOpenConsole, modifier = Modifier.fillMaxWidth()) { Text("进入控制台") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onBackToList, modifier = Modifier.fillMaxWidth()) { Text("返回实例列表") }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onReset) { Text("继续创建新实例") }
    }
}

/** 查询 Content URI 的文件显示名（不做读取，避免大文件占用内存）。 */
private suspend fun queryDisplayName(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    var name = "file"
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx) ?: name
            }
        }
    }
    name
}
