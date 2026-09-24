package com.mslx.console.ui.create

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.MSLXApplication
import com.mslx.console.data.AppLogger
import com.mslx.console.data.AppSettings
import com.mslx.console.data.DaemonOption
import com.mslx.console.data.InstanceRepository
import com.mslx.console.data.ensureRepository
import com.mslx.console.data.localengine.InstanceStorage
import com.mslx.console.data.localengine.LocalCoreInstaller
import com.mslx.console.data.localengine.LocalInstanceMeta
import com.mslx.console.data.localengine.LocalInstanceStore
import com.mslx.console.data.localengine.LocalJreManager
import com.mslx.console.data.localengine.LocalStorage
import com.mslx.console.data.model.CreateServerRequest
import com.mslx.console.data.model.LocalJava
import com.mslx.console.data.model.ServerCoreDownloadInfo
import com.mslx.console.data.remote.CreationProgressClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 已同意 EULA 的 eula.txt 内容（与守护进程 AgreeEULA 写入格式一致）。 */
private const val EULA_AGREED_CONTENT =
    "#By changing the setting below to TRUE you are indicating your agreement to our EULA (https://aka.ms/MinecraftEULA).\n#MSLX-Android auto agreed\neula=true\n"

/**
 * 「重按新建 tab 重置表单」的跨层事件通道：底部 Dock 在 NavHost 外层（AppNavHost），
 * 而表单状态在页面内的 CreateInstanceViewModel，两者通过此单例桥接。
 */
object CreateResetBus {
    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    /** 请求重置新建实例表单（由 Dock 重按新建 tab 触发）。 */
    fun request() {
        _events.tryEmit(Unit)
    }
}

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

/** 本机可选 Java 运行时（创建向导 Java 步的「本机」变体）。 */
data class LocalRuntimeChoice(
    val id: String,
    val label: String,
    val javaMajor: Int,
    val installed: Boolean,
    val embedded: Boolean,
)

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
    // 创建目标: "daemon" 远程守护进程 / "local" 本机（进程内 JVM / Shizuku）
    val target: String = "daemon",
    // —— 远程目标专用：实例落到哪台 Daemon（去主连接，不再隐含「当前主连接」）——
    /** 已配置的 Daemon 列表。 */
    val availableDaemons: List<DaemonOption> = emptyList(),
    /** 当前选中的目标 Daemon（初值为「默认 Daemon」）。 */
    val selectedDaemonId: String = "",
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
    // —— 本机目标专用 ——
    /** 本机选中的运行时 id（jre8/jre17/jre21/jre25）。 */
    val selectedRuntimeId: String = LocalJreManager.RUNTIME_ID,
    /** 当前 ABI 下可安装的本机运行时列表。 */
    val localRuntimes: List<LocalRuntimeChoice> = emptyList(),
    val localKeepAlive: Boolean = true,
    val localUseSerialGc: Boolean = true,
    /** 实例存放位置（默认公共目录，便于用户在文件管理器里直接拿核心/备份存档）。 */
    val localInstanceStorage: InstanceStorage = InstanceStorage.PUBLIC,
    /** 本机系统能否用公共目录（仅 Android 11+）。 */
    val publicStorageSupported: Boolean = true,
    /** 已授予「所有文件访问」。 */
    val publicStorageGranted: Boolean = false,
    /** 选公共目录但尚未授权 → 需先弹一个说人话的用途说明，再由 UI 跳系统授权页。 */
    val showPublicStorageRationale: Boolean = false,
    /** 本机创建成功后落地的实例目录名（跳统一本机控制台用）。 */
    val createdDirName: String = "",
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

    private val container = getApplication<MSLXApplication>().container
    private val settingsStore = container.settingsStore

    /**
     * 目标 Daemon 的仓储：随 [CreateInstanceUiState.selectedDaemonId] 切换（未选中时为
     * 未配置仓储，调用只会得到「尚未配置连接信息」而不会崩溃）。
     */
    private var repository: InstanceRepository = InstanceRepository()

    private val _state = MutableStateFlow(CreateInstanceUiState())
    val state = _state.asStateFlow()

    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val message = _message.asSharedFlow()

    private var creationClient: CreationProgressClient? = null

    init {
        loadDaemons()
        loadLocalRuntimes()
        loadLocalDefaults()
    }

    /** 读取已配置 Daemon 列表，默认选中「默认 Daemon」并以它加载 Java / 核心分类。 */
    private fun loadDaemons() {
        viewModelScope.launch {
            val settings = runCatching { settingsStore.settingsFlow.first() }.getOrNull()
            val daemons = settings?.daemons.orEmpty()
            val initial = settings?.activeDaemonId?.takeIf { id -> daemons.any { it.id == id } }
                ?: daemons.firstOrNull()?.id
                ?: ""
            _state.update { it.copy(availableDaemons = daemons.map { d -> DaemonOption(d.id, d.name) }) }
            bindDaemon(initial)
        }
    }

    /** 切换目标 Daemon：重新解析仓储并重取该 Daemon 的 Java 选项与核心分类。 */
    private fun bindDaemon(daemonId: String) {
        viewModelScope.launch {
            repository = container.ensureRepository(daemonId) ?: InstanceRepository()
            _state.update { it.copy(selectedDaemonId = daemonId) }
            loadJavaOptions()
            loadCoreCategories()
        }
    }

    /** 界面上选择目标 Daemon。 */
    fun setDaemon(daemonId: String) {
        if (_state.value.selectedDaemonId == daemonId) return
        // 上传得到的文件 key / 核心下载信息都属于旧 Daemon，切 Daemon 后一律清空
        _state.update {
            it.copy(
                core = "",
                coreUrl = "",
                coreSha256 = "",
                coreFileKey = "",
                packageFileKey = "",
                uploadedFileName = "",
                onlineGameVersion = "",
            )
        }
        bindDaemon(daemonId)
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

    /**
     * 切换创建目标（Daemon / 本机）。本机仅支持 Java 核心：
     * 强制回到快速模式(1) 与第一步，并清理与本机无关的整合包/基岩版/MCDR 选项。
     */
    fun setTarget(target: String) {
        if (_state.value.target == target) return
        _state.update {
            it.copy(
                target = target,
                step = 0,
                mode = if (target == "local") 1 else it.mode,
                downloadType = if (target == "local") "online" else it.downloadType,
                javaType = if (target == "local") "local" else it.javaType,
                core = "",
                coreUrl = "",
                coreSha256 = "",
                coreFileKey = "",
                onlineGameVersion = "",
                packageFileKey = "",
                packageUrl = "",
                packageLocalPath = "",
                success = false,
                creating = false,
                submitting = false,
                createdServerId = "",
                createdDirName = "",
                error = null,
            )
        }
        if (target == "local") {
            loadLocalRuntimes()
            refreshStorageAccess()
        }
    }

    /**
     * 刷新公共目录可用性（系统版本 + 「所有文件访问」授权）。
     * 从系统授权页回到前台时也要调，否则开关状态会停在旧值。
     */
    fun refreshStorageAccess() {
        val supported = LocalStorage.publicStorageSupported()
        val granted = LocalStorage.publicStorageGranted()
        _state.update { s ->
            val storage = if (!supported) InstanceStorage.PRIVATE else s.localInstanceStorage
            s.copy(
                publicStorageSupported = supported,
                publicStorageGranted = granted,
                localInstanceStorage = storage,
                showPublicStorageRationale = s.showPublicStorageRationale && !granted && supported,
            )
        }
    }

    /** 切换实例存放位置：选公共且未授权时弹前置说明（由 UI 负责跳系统授权页）。 */
    fun selectInstanceStorage(storage: InstanceStorage) {
        if (_state.value.localInstanceStorage == storage && storage == InstanceStorage.PRIVATE) return
        if (storage == InstanceStorage.PUBLIC && !_state.value.publicStorageGranted) {
            _state.update { it.copy(localInstanceStorage = InstanceStorage.PUBLIC, showPublicStorageRationale = true) }
            return
        }
        _state.update { it.copy(localInstanceStorage = storage, showPublicStorageRationale = false) }
    }

    /** 关闭授权说明弹窗（用户看完/拒绝）：未授权就继续选公共时自动退回私有。 */
    fun dismissPublicStorageRationale(keepPublicChoice: Boolean) {
        _state.update { s ->
            s.copy(
                showPublicStorageRationale = false,
                localInstanceStorage = if (keepPublicChoice && s.publicStorageGranted) InstanceStorage.PUBLIC else InstanceStorage.PRIVATE,
            )
        }
        if (!keepPublicChoice) _message.tryEmit("已改为应用私有目录，无需任何权限")
    }

    /** 弹窗上点「去系统授权」：收起弹窗但保留公共目录选择，回来授权成功后无需重选。 */
    fun openPublicStorageSettings() {
        _state.update { it.copy(showPublicStorageRationale = false) }
    }

    /** 弹窗上点「改用私有目录」（或直接关掉弹窗）。 */
    fun dismissWithPrivateStorage() = dismissPublicStorageRationale(keepPublicChoice = false)

    /** 刷新本机可安装运行时（当前 ABI）。 */
    fun loadLocalRuntimes() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val abi = LocalJreManager.currentAbi()
            val options = LocalJreManager.installableRuntimes(abi).map { rt ->
                LocalRuntimeChoice(
                    id = rt.id,
                    label = rt.label,
                    javaMajor = rt.javaMajor,
                    installed = LocalJreManager.isInstalled(context, rt),
                    embedded = LocalJreManager.hasEmbeddedAsset(context, rt, abi),
                )
            }
            _state.update { s ->
                // 选中的运行时若在当前 ABI 不可用，回退到已安装项或默认项
                val valid = options.any { it.id == s.selectedRuntimeId }
                val fallback = options.firstOrNull { it.installed }?.id ?: LocalJreManager.RUNTIME_ID
                s.copy(localRuntimes = options, selectedRuntimeId = if (valid) s.selectedRuntimeId else fallback)
            }
        }
    }

    /** 本机默认保活 / SerialGC 从「本机运行时与开服设置」读取。 */
    private fun loadLocalDefaults() {
        viewModelScope.launch {
            runCatching { settingsStore.settingsFlow.first() }
                .onSuccess { s ->
                    _state.update {
                        it.copy(
                            localKeepAlive = s.localKeepAlive,
                            localUseSerialGc = s.localUseSerialGc,
                            minM = s.localMinMemMb,
                            maxM = s.localMaxMemMb,
                        )
                    }
                }
                .onFailure { AppLogger.w("Create", "读取本机开服全局默认失败", it) }
        }
    }

    fun selectLocalRuntime(id: String) {
        _state.update { it.copy(selectedRuntimeId = id) }
    }

    /** 按 MC 版本自动选中本机可用运行时（复用创建向导推荐规则）。 */
    private fun autoRecommendLocalRuntime(gameVersion: String) {
        val recommended = recommendedJavaFor(gameVersion) ?: return
        val target = _state.value.localRuntimes.firstOrNull { it.javaMajor == recommended } ?: return
        if (_state.value.selectedRuntimeId != target.id) {
            _state.update { it.copy(selectedRuntimeId = target.id) }
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
                if (s.target == "local") {
                    // 本机：必须经 MSLAPI 在线选定核心（拿到下载地址与游戏版本）
                    if (s.coreUrl.isBlank() || s.onlineGameVersion.isBlank()) {
                        _message.tryEmit("请在线选择服务端核心（本机仅支持 MSLAPI 在线核心）"); return
                    }
                } else if (s.mode == 3) {
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
            "java" -> if (s.target != "local" && computedJava().isBlank()) {
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
                    val all = listOf(
                        CoreCategory("plugins", "插件服务端", "Bukkit/Spigot/Paper", classify.pluginsCore),
                        CoreCategory("forge_hybrid", "Forge 混合", "Forge 模组 + 插件", classify.pluginsAndModsCoreForge),
                        CoreCategory("fabric_hybrid", "Fabric 混合", "Fabric 模组 + 插件", classify.pluginsAndModsCoreFabric),
                        CoreCategory("mod_forge", "Forge 模组", "纯 Forge/NeoForge", classify.modsCoreForge),
                        CoreCategory("mod_fabric", "Fabric 模组", "纯 Fabric", classify.modsCoreFabric),
                        CoreCategory("vanilla", "原版服务端", "官方原版", classify.vanillaCore),
                        CoreCategory("bedrock", "基岩版第三方", "基岩版服务端", classify.bedrockCore),
                        CoreCategory("proxy", "代理服务端", "BungeeCord/Velocity", classify.proxyCore),
                    )
                    // 全量分类保留在 state；基岩版模式（mode==3）由界面显示时过滤为仅 bedrock
                    val categories = all
                    _state.update {
                        it.copy(
                            coreCategories = categories,
                            coreSelectorLoading = false,
                            selectedCategoryKey = if (_state.value.mode == 3) "bedrock" else _state.value.selectedCategoryKey,
                        )
                    }
                },
                onFailure = { e ->
                    _state.update { it.copy(coreSelectorLoading = false, error = "获取核心分类失败：${e.message}") }
                },
            )
        }
    }

    fun openCoreSelector() {
        _state.update {
            // 基岩版模式强制从基岩版分类开始
            it.copy(coreSelectorVisible = true, selectedCategoryKey = if (it.mode == 3) "bedrock" else it.selectedCategoryKey)
        }
    }

    fun closeCoreSelector() {
        _state.update { it.copy(coreSelectorVisible = false, buildsVisible = false, selectedCoreName = "", coreVersions = emptyList(), coreBuilds = emptyList(), coreVersionDescription = "") }
    }

    fun selectCategory(key: String) {
        // 基岩版模式只允许基岩版分类（双保险，界面已过滤）
        if (_state.value.mode == 3 && key != "bedrock") return
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
        _state.update { s ->
            // 选择核心版本后，按版本自动选中推荐 Java（在线下载与 Docker 预设同步），
            // 修复"建议 Java 8 却默认下载 Java 21/25"的问题
            val recommended = recommendedJavaFor(version)
            val recommendedOnline = recommended?.let { r ->
                s.onlineJavaVersions.firstOrNull { v -> v.toIntOrNull() == r }
            }
            s.copy(
                core = "$core-$version.jar",
                coreUrl = info.url,
                coreSha256 = info.sha256.orEmpty(),
                coreFileKey = "",
                onlineGameVersion = version,
                selectedJavaVersion = recommendedOnline ?: s.selectedJavaVersion,
                dockerImagePresetVersion = recommended?.toString() ?: s.dockerImagePresetVersion,
                coreSelectorLoading = false,
                coreSelectorVisible = false,
                buildsVisible = false,
                selectedCoreName = "",
                coreVersions = emptyList(),
                coreBuilds = emptyList(),
                coreVersionDescription = "",
            )
        }
        // 本机目标：按核心版本自动选中推荐且可用的本机 Java 运行时
        if (_state.value.target == "local") autoRecommendLocalRuntime(version)
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
        if (_state.value.target == "local") {
            submitLocal()
            return
        }
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

    /**
     * 本机创建：把已选定的 MSLAPI 核心（coreUrl/coreSha256/onlineGameVersion）下载到
     * 实例目录下的 `server.jar`（位置由用户选：公共 `/storage/emulated/0/MSLX/servers/<名>`
     * 或私有 `filesDir/mslx/servers/<名>`），并补全实例文件（eula/server.properties/instance.json），
     * 不经 Daemon / SignalR。成功后置 success + createdDirName，由界面跳统一本机控制台。
     */
    private fun submitLocal() {
        val s = _state.value
        if (s.name.isBlank()) {
            _message.tryEmit("请填写实例名称")
            return
        }
        if (s.coreUrl.isBlank() || s.onlineGameVersion.isBlank()) {
            _message.tryEmit("请在线选择服务端核心")
            return
        }
        val context = getApplication<Application>()
        val runtime = LocalJreManager.runtimeById(s.selectedRuntimeId)
        // 核心名：选择器写入的 core 形如 "$core-$version.jar"，去掉后缀还原；无则用运行时 id 占位
        val coreName = s.core.removeSuffix("-${s.onlineGameVersion}.jar").ifBlank { "server" }
        _state.update {
            it.copy(
                submitting = true,
                creating = true,
                error = null,
                success = false,
                creationProgress = 0.0,
                creationLogs = listOf(CreationLog("开始下载服务端核心 ${s.core}", null)),
            )
        }
        viewModelScope.launch {
            LocalStorage.migrateLegacy(context)
            val global = runCatching { settingsStore.settingsFlow.first() }.getOrDefault(AppSettings())
            val placement = withContext(Dispatchers.IO) {
                LocalInstanceStore.createDir(context, s.name, s.localInstanceStorage)
            }
            if (s.localInstanceStorage == InstanceStorage.PUBLIC && placement.storage == InstanceStorage.PRIVATE) {
                // 未拿到「所有文件访问」：不阻断开服，静默降到私有并告知一句
                _message.tryEmit("未获得公共目录权限，本次实例已建在应用私有目录")
            }
            val meta = LocalInstanceMeta(
                name = s.name,
                directory = placement.dirName,
                runtimeId = runtime.id,
                javaMajor = runtime.javaMajor,
                abi = LocalJreManager.currentAbi(),
                minMemMb = s.minM,
                maxMemMb = s.maxM,
                jvmArgs = s.args.trim(),
                useSerialGc = s.localUseSerialGc,
                keepAlive = s.localKeepAlive,
                storage = placement.storage.key,
                // 创建时没改过全局默认（内存/参数）→ 标记为跟随，以后改全局不用逐实例改
                inheritGlobal = s.minM == global.localMinMemMb &&
                    s.maxM == global.localMaxMemMb &&
                    s.args.trim() == global.localJvmArgs.trim(),
                motd = s.name,
            )
            _state.update {
                it.copy(
                    createdDirName = placement.dirName,
                    localInstanceStorage = placement.storage,
                    creationLogs = it.creationLogs +
                        CreationLog("实例目录：${LocalStorage.displayPath(context, placement.dir)}", null),
                )
            }
            LocalCoreInstaller(repository).installFromUrl(
                url = s.coreUrl,
                sha256 = s.coreSha256,
                core = coreName,
                version = s.onlineGameVersion,
                serverDir = placement.dir,
                meta = meta,
            ) { p ->
                _state.update { it.copy(creationProgress = (p * 100.0).coerceIn(0.0, 100.0)) }
            }.onSuccess { (_, finalMeta) ->
                AppLogger.i("Create", "本机实例创建完成：${finalMeta.directory}")
                _state.update {
                    it.copy(
                        submitting = false,
                        creating = false,
                        creationProgress = 100.0,
                        success = true,
                        createdDirName = finalMeta.directory,
                    )
                }
            }.onFailure { e ->
                AppLogger.w("Create", "本机实例创建失败", e)
                _state.update { it.copy(submitting = false, creating = false, error = "创建失败：${e.message}") }
                _message.tryEmit("创建失败：${e.message}")
            }
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
            localRuntimes = _state.value.localRuntimes,
            // 目标 Daemon 与已加载列表跨表单复用，避免重置后回到未选中状态
            availableDaemons = _state.value.availableDaemons,
            selectedDaemonId = _state.value.selectedDaemonId,
        )
    }

    override fun onCleared() {
        creationClient?.disconnect()
        super.onCleared()
    }
}

/**
 * 根据 Minecraft 版本推荐 Java 主版本：规则已下沉到 [LocalJreManager.recommendedMajorForGame]
 * （本机运行时与远程创建共用同一张表，避免两边推荐不一致）。
 */
fun recommendedJavaFor(gameVersion: String): Int? =
    LocalJreManager.recommendedMajorForGame(gameVersion)
