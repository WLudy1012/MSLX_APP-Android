package com.mslx.console.ui.create

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.MSLXApplication
import com.mslx.console.data.model.CreateServerRequest
import com.mslx.console.data.model.LocalJava
import com.mslx.console.data.model.ServerCoreDownloadInfo
import com.mslx.console.data.remote.CreationProgressClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 已同意 EULA 的 eula.txt 内容（与守护进程 AgreeEULA 写入格式一致）。 */
private const val EULA_AGREED_CONTENT =
    "#By changing the setting below to TRUE you are indicating your agreement to our EULA (https://aka.ms/MinecraftEULA).\n#MSLX-Android auto agreed\neula=true\n"

data class CoreCategory(
    val key: String,
    val name: String,
    val desc: String,
    val cores: List<String>,
)

data class CreationLog(
    val message: String,
    val progress: Double?,
    val isError: Boolean = false,
)

data class WizardStep(val key: String, val title: String)

fun wizardSteps(mode: Int): List<WizardStep> = when (mode) {
    2 -> listOf(
        WizardStep("basic", "基本信息"),
        WizardStep("package", "整合包"),
        WizardStep("java", "Java 环境"),
        WizardStep("resource", "资源配置"),
        WizardStep("confirm", "确认"),
    )
    3 -> listOf(
        WizardStep("basic", "基本信息"),
        WizardStep("core", "核心文件"),
        WizardStep("resource", "资源配置"),
        WizardStep("confirm", "确认"),
    )
    4 -> listOf(
        WizardStep("basic", "基本信息"),
        WizardStep("core", "核心文件"),
        WizardStep("java", "Java 环境"),
        WizardStep("mcdr", "MCDR"),
        WizardStep("resource", "资源配置"),
        WizardStep("confirm", "确认"),
    )
    else -> listOf(
        WizardStep("basic", "基本信息"),
        WizardStep("core", "核心文件"),
        WizardStep("java", "Java 环境"),
        WizardStep("resource", "资源配置"),
        WizardStep("confirm", "确认"),
    )
}

data class CreateInstanceUiState(
    // 模式: 1 快速 / 2 整合包 / 3 基岩版 / 4 MCDR / 10 自定义
    val mode: Int = 1,
    val step: Int = 0,
    // 基础
    val name: String = "新建服务器",
    val path: String = "",
    // 核心
    val downloadType: String = "online", // online / manual / custom
    val core: String = "",
    val coreUrl: String = "",
    val coreSha256: String = "",
    val coreFileKey: String = "",
    val onlineGameVersion: String = "",
    // 整合包
    val packageType: String = "upload", // upload / url / local
    val packageFileKey: String = "",
    val packageUrl: String = "",
    val packageLocalPath: String = "",
    // Java
    val javaType: String = "online", // env / custom / local / online / docker
    val selectedJavaVersion: String = "21",
    val customJavaPath: String = "",
    val dockerImageType: String = "preset",
    val dockerImagePresetVersion: String = "21",
    val dockerCustomImage: String = "",
    // 内存与参数
    val minM: Int = 1024,
    val maxM: Int = 4096,
    val args: String = "",
    val ignoreEula: Boolean = true,
    // MCDR
    val mcdrPython: String = "python",
    val mcdrHandler: String = "",
    val mcdrInstall: Boolean = true,
    val mcdrPipMirror: String = "",
    // Java 选项
    val onlineJavaVersions: List<String> = emptyList(),
    val localJavas: List<LocalJava> = emptyList(),
    // 核心选择器
    val coreCategories: List<CoreCategory> = emptyList(),
    val coreSelectorVisible: Boolean = false,
    val coreSelectorLoading: Boolean = false,
    val selectedCategoryKey: String = "plugins",
    val selectedCoreName: String = "",
    val coreVersions: List<String> = emptyList(),
    val coreVersionDescription: String = "",
    val loadingVersions: Boolean = false,
    val coreBuilds: List<String> = emptyList(),
    val buildsVisible: Boolean = false,
    val selectedBuildVersion: String = "",
    // 上传
    val uploading: Boolean = false,
    val uploadProgress: Int = 0,
    val uploadedFileName: String = "",
    // 创建
    val submitting: Boolean = false,
    val creating: Boolean = false,
    val creationProgress: Double = 0.0,
    val creationLogs: List<CreationLog> = emptyList(),
    val createdServerId: String = "",
    val success: Boolean = false,
    val error: String? = null,
)

class CreateInstanceViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = getApplication<MSLXApplication>().container.instanceRepository

    private val _state = MutableStateFlow(CreateInstanceUiState())
    val state = _state.asStateFlow()

    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val message = _message.asSharedFlow()

    private var creationClient: CreationProgressClient? = null

    init {
        loadJavaOptions()
        loadCoreCategories()
    }

    fun update(transform: (CreateInstanceUiState) -> CreateInstanceUiState) {
        _state.update(transform)
    }

    fun setMode(mode: Int) {
        if (_state.value.mode == mode) return
        // 切换新建方式：重置向导进度及相关选择状态，避免残留上一方式的旧数据
        _state.update {
            it.copy(
                mode = mode,
                step = 0,
                selectedCategoryKey = if (mode == 3) "bedrock" else "plugins",
                core = "",
                coreUrl = "",
                coreSha256 = "",
                coreFileKey = "",
                onlineGameVersion = "",
                packageFileKey = "",
                packageUrl = "",
                packageLocalPath = "",
                mcdrPython = "python",
                mcdrHandler = "",
                success = false,
                error = null,
            )
        }
    }

    fun nextStep() {
        val s = _state.value
        val steps = wizardSteps(s.mode)
        val key = steps.getOrNull(s.step)?.key
        when (key) {
            "basic" -> if (s.name.isBlank()) {
                _message.tryEmit("请填写实例名称"); return
            }
            "core" -> {
                if (s.mode == 3) {
                    if (s.coreFileKey.isBlank() && s.coreUrl.isBlank()) {
                        _message.tryEmit("请在线选择、填写远程地址或上传基岩版核心"); return
                    }
                } else if (s.core.isBlank()) {
                    _message.tryEmit("请配置服务端核心"); return
                }
            }
            "package" -> if (s.packageFileKey.isBlank() && s.packageUrl.isBlank() && s.packageLocalPath.isBlank()) {
                _message.tryEmit("请提供整合包（上传 / 地址 / 本机路径）"); return
            }
            "java" -> if (computedJava().isBlank()) {
                _message.tryEmit("请配置 Java 环境"); return
            }
        }
        if (s.step < steps.size - 1) {
            _state.update { it.copy(step = it.step + 1) }
        }
    }

    fun prevStep() {
        if (_state.value.step > 0) {
            _state.update { it.copy(step = it.step - 1) }
        }
    }

    fun loadJavaOptions() {
        viewModelScope.launch {
            val locals = repository.javaList(refresh = false).getOrDefault(emptyList())
            val status = repository.getStatus().getOrNull()
            val os = status?.systemInfo?.osType?.lowercase()?.replaceFirst("os", "") ?: ""
            val arch = when (status?.systemInfo?.osArchitecture?.lowercase()) {
                "amd64", "x86_64" -> "x64"
                "aarch64" -> "arm64"
                else -> status?.systemInfo?.osArchitecture?.lowercase().orEmpty()
            }
            val online = if (os.isNotBlank() && arch.isNotBlank()) {
                repository.onlineJavaVersions(os, arch).getOrDefault(emptyList())
            } else emptyList()
            _state.update {
                it.copy(
                    localJavas = locals,
                    onlineJavaVersions = online,
                    selectedJavaVersion = online.firstOrNull() ?: "21",
                )
            }
        }
    }

    fun loadCoreCategories() {
        _state.update { it.copy(coreSelectorLoading = true) }
        viewModelScope.launch {
            repository.serverCoreClassify().fold(
                onSuccess = { classify ->
                    val categories = listOf(
                        CoreCategory("plugins", "插件服务端", "Bukkit/Spigot/Paper", classify.pluginsCore),
                        CoreCategory("forge_hybrid", "Forge 混合", "Forge 模组 + 插件", classify.pluginsAndModsCoreForge),
                        CoreCategory("fabric_hybrid", "Fabric 混合", "Fabric 模组 + 插件", classify.pluginsAndModsCoreFabric),
                        CoreCategory("mod_forge", "Forge 模组", "纯 Forge/NeoForge", classify.modsCoreForge),
                        CoreCategory("mod_fabric", "Fabric 模组", "纯 Fabric", classify.modsCoreFabric),
                        CoreCategory("vanilla", "原版服务端", "官方原版", classify.vanillaCore),
                        CoreCategory("bedrock", "基岩版第三方", "基岩版服务端", classify.bedrockCore),
                        CoreCategory("proxy", "代理服务端", "BungeeCord/Velocity", classify.proxyCore),
                    )
                    _state.update { it.copy(coreCategories = categories, coreSelectorLoading = false) }
                },
                onFailure = { e ->
                    _state.update { it.copy(coreSelectorLoading = false, error = "获取核心分类失败：${e.message}") }
                },
            )
        }
    }

    fun openCoreSelector() {
        _state.update { it.copy(coreSelectorVisible = true) }
    }

    fun closeCoreSelector() {
        _state.update { it.copy(coreSelectorVisible = false, buildsVisible = false, selectedCoreName = "", coreVersions = emptyList(), coreBuilds = emptyList(), coreVersionDescription = "") }
    }

    fun selectCategory(key: String) {
        _state.update {
            it.copy(
                selectedCategoryKey = key,
                selectedCoreName = "",
                coreVersions = emptyList(),
                coreVersionDescription = "",
                coreBuilds = emptyList(),
                buildsVisible = false,
            )
        }
    }

    fun selectCoreName(name: String) {
        if (_state.value.selectedCoreName == name) return
        _state.update { it.copy(selectedCoreName = name, coreVersions = emptyList(), coreVersionDescription = "", coreBuilds = emptyList(), buildsVisible = false, loadingVersions = true) }
        viewModelScope.launch {
            repository.serverCoreGameVersion(name).fold(
                onSuccess = { info ->
                    _state.update { it.copy(coreVersions = info.versions, coreVersionDescription = info.description.orEmpty(), loadingVersions = false) }
                },
                onFailure = { e ->
                    _state.update { it.copy(loadingVersions = false, error = "获取 $name 版本失败：${e.message}") }
                },
            )
        }
    }

    fun selectVersion(version: String) {
        val core = _state.value.selectedCoreName
        if (core == "forge" || core == "neoforge") {
            _state.update { it.copy(selectedBuildVersion = version, buildsVisible = true) }
            viewModelScope.launch {
                repository.serverCoreBuilds(core, version).fold(
                    onSuccess = { builds -> _state.update { it.copy(coreBuilds = builds.ifEmpty { listOf("latest") }) } },
                    onFailure = { _state.update { it.copy(coreBuilds = listOf("latest")) } },
                )
            }
        } else {
            fetchDownloadInfo(core, version, "latest")
        }
    }

    fun selectBuild(build: String) {
        fetchDownloadInfo(_state.value.selectedCoreName, _state.value.selectedBuildVersion, build)
    }

    private fun fetchDownloadInfo(core: String, version: String, build: String) {
        _state.update { it.copy(coreSelectorLoading = true) }
        viewModelScope.launch {
            repository.serverCoreDownloadInfo(core, version, build).fold(
                onSuccess = { info ->
                    applyCoreSelection(core, version, info)
                },
                onFailure = { e ->
                    _state.update { it.copy(coreSelectorLoading = false, error = "获取下载信息失败：${e.message}") }
                },
            )
        }
    }

    private fun applyCoreSelection(core: String, version: String, info: ServerCoreDownloadInfo) {
        _state.update {
            it.copy(
                core = "$core-$version.jar",
                coreUrl = info.url,
                coreSha256 = info.sha256.orEmpty(),
                coreFileKey = "",
                onlineGameVersion = version,
                coreSelectorLoading = false,
                coreSelectorVisible = false,
                buildsVisible = false,
                selectedCoreName = "",
                coreVersions = emptyList(),
                coreBuilds = emptyList(),
                coreVersionDescription = "",
            )
        }
    }

    fun clearCoreSelection() {
        _state.update { it.copy(core = "", coreUrl = "", coreSha256 = "", coreFileKey = "", onlineGameVersion = "") }
    }

    fun uploadCore(uri: Uri, fileName: String) {
        if (_state.value.uploading) return
        _state.update { it.copy(uploading = true, uploadProgress = 0, uploadedFileName = fileName) }
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val size = queryContentLength(resolver, uri)
            repository.uploadFileStream(
                input = { resolver.openInputStream(uri) ?: error("无法打开文件") },
                totalBytes = size,
                onProgress = { progress ->
                    _state.update { it.copy(uploadProgress = progress) }
                },
            ).fold(
                onSuccess = { key ->
                    _state.update { it.copy(uploading = false, uploadProgress = 100, coreFileKey = key, core = fileName, coreUrl = "", coreSha256 = "") }
                    _message.tryEmit("核心文件上传成功")
                },
                onFailure = { e ->
                    _state.update { it.copy(uploading = false, coreFileKey = "", core = "", error = "上传失败：${e.message}") }
                },
            )
        }
    }

    fun removeUploadedCore() {
        val key = _state.value.coreFileKey
        if (key.isNotBlank()) {
            viewModelScope.launch {
                // 清理失败仅记录日志（临时文件由 Daemon 侧过期回收兜底）
                repository.deleteUpload(key).onFailure { e ->
                    com.mslx.console.data.AppLogger.w("Create", "清理上传临时文件失败", e)
                }
            }
        }
        _state.update { it.copy(coreFileKey = "", core = "", uploadProgress = 0, uploadedFileName = "") }
    }

    fun uploadPackage(uri: Uri, fileName: String) {
        if (_state.value.uploading) return
        _state.update { it.copy(uploading = true, uploadProgress = 0, uploadedFileName = fileName) }
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val size = queryContentLength(resolver, uri)
            repository.uploadFileStream(
                input = { resolver.openInputStream(uri) ?: error("无法打开文件") },
                totalBytes = size,
                onProgress = { progress ->
                    _state.update { it.copy(uploadProgress = progress) }
                },
            ).fold(
                onSuccess = { key ->
                    _state.update { it.copy(uploading = false, uploadProgress = 100, packageFileKey = key) }
                    _message.tryEmit("整合包上传成功")
                },
                onFailure = { e ->
                    _state.update { it.copy(uploading = false, packageFileKey = "", error = "上传失败：${e.message}") }
                },
            )
        }
    }

    /** 查询 Content URI 指向的文件大小；查询不到时返回 0（进度按已上传字节计算）。 */
    private fun queryContentLength(resolver: android.content.ContentResolver, uri: Uri): Long =
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else 0L
                } else 0L
            } ?: 0L
        }.getOrDefault(0L)

    private fun computedJava(): String = with(_state.value) {
        when (javaType) {
            "env" -> "java"
            "custom", "local" -> customJavaPath
            "online" -> if (selectedJavaVersion.isNotBlank()) "MSLX://Java/$selectedJavaVersion" else ""
            "docker" -> if (dockerImage.startsWith("MSLX://")) "docker-java" else "docker-custom"
            else -> ""
        }
    }

    private val CreateInstanceUiState.dockerImage: String
        get() = if (dockerImageType == "preset") "MSLX://DockerImage/Java/$dockerImagePresetVersion" else dockerCustomImage

    fun submit() {
        if (_state.value.submitting || _state.value.creating) return
        val s = _state.value
        if (s.name.isBlank()) {
            _message.tryEmit("请填写实例名称")
            return
        }
        if (s.mode != 3 && computedJava().isBlank()) {
            _message.tryEmit("请配置 Java 环境")
            return
        }
        if (s.mode == 3) {
            if (s.coreFileKey.isBlank() && s.coreUrl.isBlank()) {
                _message.tryEmit("请上传或选择基岩版核心")
                return
            }
        } else if (s.core.isBlank()) {
            _message.tryEmit("请配置服务端核心")
            return
        }

        val request = when (s.mode) {
            2 -> CreateServerRequest(
                name = s.name,
                core = s.core.ifBlank { "server.jar" },
                minM = s.minM,
                maxM = s.maxM,
                java = computedJava(),
                args = s.args.ifBlank { null },
                ignoreEula = s.ignoreEula,
                path = s.path.ifBlank { null },
                dockerImage = s.dockerImage,
                packageFileKey = s.packageFileKey.ifBlank { null },
                packageUrl = s.packageUrl.ifBlank { null },
                packageLocalPath = s.packageLocalPath.ifBlank { null },
                coreUrl = s.coreUrl.ifBlank { null },
                coreSha256 = s.coreSha256.ifBlank { null },
                coreFileKey = s.coreFileKey.ifBlank { null },
            )
            3 -> CreateServerRequest(
                name = s.name,
                core = s.core.ifBlank { "bedrock_server.jar" },
                minM = s.minM,
                maxM = s.maxM,
                java = null,
                args = s.args.ifBlank { null },
                ignoreEula = s.ignoreEula,
                path = s.path.ifBlank { null },
                coreUrl = s.coreUrl.ifBlank { null },
                coreSha256 = s.coreSha256.ifBlank { null },
                coreFileKey = s.coreFileKey.ifBlank { null },
            )
            else -> CreateServerRequest(
                name = s.name,
                core = s.core,
                minM = s.minM,
                maxM = s.maxM,
                java = computedJava().ifBlank { null },
                args = s.args.ifBlank { null },
                ignoreEula = s.ignoreEula,
                path = s.path.ifBlank { null },
                dockerImage = s.dockerImage,
                dockerPorts = "25565:25565",
                mcdr = s.mode == 4,
                mcdrPython = if (s.mode == 4) s.mcdrPython.ifBlank { "python" } else null,
                mcdrHandler = if (s.mode == 4) s.mcdrHandler.ifBlank { null } else null,
                mcdrInstall = s.mode == 4 && s.mcdrInstall,
                mcdrPipMirror = if (s.mode == 4) s.mcdrPipMirror.ifBlank { null } else null,
                coreUrl = s.coreUrl.ifBlank { null },
                coreSha256 = s.coreSha256.ifBlank { null },
                coreFileKey = s.coreFileKey.ifBlank { null },
            )
        }

        _state.update { it.copy(submitting = true, error = null, creationLogs = emptyList(), success = false) }
        viewModelScope.launch {
            repository.createInstance(request).fold(
                onSuccess = { serverId ->
                    _state.update { it.copy(createdServerId = serverId, creating = true, creationProgress = 0.0) }
                    startCreationProgress(serverId)
                },
                onFailure = { e ->
                    _state.update { it.copy(submitting = false, error = "创建请求失败：${e.message}") }
                },
            )
        }
    }

    private fun startCreationProgress(serverId: String) {
        creationClient?.disconnect()
        val client = CreationProgressClient(
            baseUrl = repository.baseUrl,
            apiKey = repository.apiKey,
            serverId = serverId,
        ) { id, message, progress ->
            if (id != serverId) return@CreationProgressClient
            _state.update {
                val logs = it.creationLogs + CreationLog(message, progress, progress == -1.0)
                it.copy(creationLogs = logs, creationProgress = progress.coerceIn(0.0, 100.0))
            }
            when {
                progress >= 100.0 -> {
                    _state.update { it.copy(creating = false, submitting = false, success = true) }
                    creationClient?.disconnect()
                    // 勾选了"自动同意 EULA"时，创建成功后直接写 eula.txt，
                    // 否则 vanilla 等核心首次启动仍会因 eula.txt 未同意而退出
                    if (_state.value.ignoreEula) {
                        viewModelScope.launch {
                            // 自动同意 EULA 写入失败仅记录日志（启动流程还有 ensureEulaAgreed 兜底）
                            repository.saveFileContent(
                                id = serverId.toLongOrNull() ?: return@launch,
                                path = "eula.txt",
                                content = EULA_AGREED_CONTENT,
                            ).onFailure { e ->
                                com.mslx.console.data.AppLogger.w("Create", "写入 eula.txt 失败", e)
                            }
                        }
                    }
                }
                progress == -1.0 -> {
                    _state.update { it.copy(creating = false, submitting = false, error = message) }
                    creationClient?.disconnect()
                }
            }
        }
        creationClient = client
        try {
            client.connect()
            _state.update { it.copy(creationLogs = _state.value.creationLogs + CreationLog("已连接到实时进度服务", null)) }
        } catch (e: Exception) {
            _state.update { it.copy(creating = false, submitting = false, error = "连接进度服务失败：${e.message}") }
        }
    }

    fun cancelCreation() {
        val serverId = _state.value.createdServerId
        if (serverId.isBlank()) return
        viewModelScope.launch {
            repository.cancelCreation(serverId).fold(
                onSuccess = { _message.tryEmit(it) },
                onFailure = { e -> _message.tryEmit("取消失败：${e.message}") },
            )
        }
        creationClient?.disconnect()
        _state.update { it.copy(creating = false, submitting = false) }
    }

    fun reset() {
        creationClient?.disconnect()
        _state.value = CreateInstanceUiState(
            onlineJavaVersions = _state.value.onlineJavaVersions,
            localJavas = _state.value.localJavas,
            coreCategories = _state.value.coreCategories,
        )
    }

    override fun onCleared() {
        creationClient?.disconnect()
        super.onCleared()
    }
}
