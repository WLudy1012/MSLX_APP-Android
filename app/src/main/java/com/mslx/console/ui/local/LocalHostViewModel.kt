package com.mslx.console.ui.local

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.MSLXApplication
import com.mslx.console.data.AppLogger
import com.mslx.console.data.InstanceRepository
import com.mslx.console.data.localengine.LocalCoreInstaller
import com.mslx.console.data.localengine.LocalInstanceMeta
import com.mslx.console.data.localengine.LocalInstanceStore
import com.mslx.console.data.localengine.LocalInstanceSummary
import com.mslx.console.data.localengine.LocalJreManager
import com.mslx.console.data.localengine.LocalServerRuntime
import com.mslx.console.data.localengine.LocalStorage
import com.mslx.console.data.localengine.ServerFiles
import com.mslx.console.localengine.NativeVm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class LocalHostUiState(
    // 运行时（JRE）
    val jreInstalled: Boolean = false,
    val jreInfo: String? = null,
    val jreEmbedded: Boolean = false,
    val jreAbi: String = "",
    val jreInstalling: Boolean = false,
    val jreProgress: Float = 0f,
    /** JRE 安装失败原因（在 JRE 卡片内直接显示，避免用户看不到页面底部的提示）。 */
    val jreError: String? = null,
    val javaMajor: Int = LocalJreManager.JAVA_MAJOR,
    // 核心（MSLAPI）
    val coreNames: List<String> = emptyList(),
    val coreName: String = "",
    val coreVersions: List<String> = emptyList(),
    val coreVersion: String = "",
    val coresLoading: Boolean = false,
    val coreDownloading: Boolean = false,
    val coreProgress: Float = 0f,
    val jarInstalled: Boolean = false,
    val jarPath: String = "",
    /** 实例目录（数据目录下的相对路径，如 servers/本地服务器）与文件补全情况。 */
    val instancePath: String = "",
    val instanceFiles: List<Pair<String, Boolean>> = emptyList(),
    // 多实例管理（mslx/servers 下一目录一实例）
    val instances: List<LocalInstanceSummary> = emptyList(),
    val activeDirName: String = "",
    val deleteTarget: LocalInstanceSummary? = null,
    // 服务端配置（默认值来自「本地开服设置」，页面内可临时覆盖）
    val serverName: String = "本地服务器",
    val minMem: Int = 1024,
    val maxMem: Int = 2048,
    val jvmArgs: String = "",
    val keepAlive: Boolean = true,
    val useSerialGc: Boolean = true,
    // 服务端 server.properties 配置（下载核心后写入实例文件）
    val serverPort: Int = 25565,
    val motd: String = "",
    val maxPlayers: Int = 20,
    val onlineMode: Boolean = true,
    val difficulty: String = "easy",
    val gamemode: String = "survival",
    // 运行
    val running: Boolean = false,
    val jvmCreated: Boolean = false,
    val restartPrompt: Boolean = false,
    val logs: List<String> = emptyList(),
    val message: String? = null,
)

/**
 * 本机开服（进程内 JVM）：JRE 内嵌/下载 → 核心下载 → 前台服务保活下启动服务端。
 * 运行实例与日志挂在进程级的 [LocalServerRuntime]，所以离开页面再回来、或 App 退到后台都不丢。
 */
class LocalHostViewModel(application: Application) : AndroidViewModel(application) {

    private val container = getApplication<MSLXApplication>().container
    private val repository: InstanceRepository = container.instanceRepository
    private val store = container.settingsStore
    private val coreInstaller = LocalCoreInstaller(repository)

    private val _state = MutableStateFlow(LocalHostUiState())
    val state = _state.asStateFlow()

    /** 用于识别「运行中 → 已停止」跳变，触发重启提示弹窗。 */
    private var wasRunning = false

    init {
        // 统一数据目录：旧布局（filesDir/jre、filesDir/worlds）迁移到 filesDir/mslx 下
        LocalStorage.migrateLegacy(getApplication())
        LocalStorage.ensureBase(getApplication())
        refreshJre()
        refreshCores()
        loadDefaults()
        observeRuntime()
        refreshInstance()
        refreshInstances()
    }

    /** 刷新当前实例目录与其文件补全情况（UI 展示「服务器目录」与文件清单）。 */
    fun refreshInstance() {
        val context = getApplication<Application>()
        val current = _state.value
        // 已选中某个实例时以该目录为准；否则按输入框里的名称推导（新建场景）
        val dir = if (current.activeDirName.isNotBlank()) {
            LocalInstanceStore.dir(context, current.activeDirName)
        } else {
            LocalStorage.serverDir(context, current.serverName)
        }
        val jar = File(dir, ServerFiles.SERVER_JAR_NAME)
        _state.update {
            it.copy(
                instancePath = LocalStorage.displayPath(context, dir),
                instanceFiles = if (dir.isDirectory) ServerFiles.listInstanceFiles(dir) else emptyList(),
                jarInstalled = jar.isFile,
                jarPath = jar.absolutePath,
            )
        }
    }

    /** 扫描本地实例列表（mslx/servers 下一目录一实例）。 */
    fun refreshInstances() {
        val list = LocalInstanceStore.list(getApplication())
        _state.update { it.copy(instances = list) }
    }

    /** 切换到某个已存在实例：把它的元数据回填到表单（运行中禁止切换）。 */
    fun selectInstance(dirName: String) {
        if (_state.value.running) {
            _state.update { it.copy(message = "服务端运行中，暂时不能切换实例") }
            return
        }
        val meta = LocalInstanceStore.load(getApplication(), dirName)
        if (meta == null) {
            _state.update { it.copy(message = "实例不存在：$dirName") }
            refreshInstances()
            return
        }
        _state.update {
            it.copy(
                activeDirName = dirName,
                serverName = meta.name.ifBlank { dirName },
                coreName = meta.core.ifBlank { it.coreName },
                coreVersion = meta.coreVersion,
                minMem = meta.minMemMb,
                maxMem = meta.maxMemMb,
                jvmArgs = meta.jvmArgs,
                keepAlive = meta.keepAlive,
                useSerialGc = meta.useSerialGc,
                serverPort = meta.serverPort,
                motd = meta.motd,
                maxPlayers = meta.maxPlayers,
                onlineMode = meta.onlineMode,
                difficulty = meta.difficulty,
                gamemode = meta.gamemode,
                message = "已切换到实例「${meta.name.ifBlank { dirName }}」",
            )
        }
        refreshInstance()
    }

    /** 新建实例：清空选中状态并重置表单（下载核心时按新名称建目录）。 */
    fun newInstance() {
        if (_state.value.running) {
            _state.update { it.copy(message = "服务端运行中，暂时不能新建实例") }
            return
        }
        _state.update {
            it.copy(
                activeDirName = "",
                serverName = ServerFiles.DEFAULT_SERVER_NAME,
                motd = "",
                serverPort = 25565,
                maxPlayers = 20,
                onlineMode = true,
                difficulty = "easy",
                gamemode = "survival",
                message = "已切到新建实例：下载核心后会创建 servers/${LocalStorage.sanitizeName(ServerFiles.DEFAULT_SERVER_NAME)}",
            )
        }
        refreshInstance()
    }

    /** 请求删除实例（弹确认框；运行中的当前实例不允许删除）。 */
    fun requestDelete(summary: LocalInstanceSummary) {
        if (_state.value.running && _state.value.activeDirName == summary.dirName) {
            _state.update { it.copy(message = "该实例正在运行，请先停止服务端") }
            return
        }
        _state.update { it.copy(deleteTarget = summary) }
    }

    fun dismissDelete() = _state.update { it.copy(deleteTarget = null) }

    /** 确认删除实例目录（世界存档一并删除，不可恢复）。 */
    fun confirmDelete() {
        val target = _state.value.deleteTarget ?: return
        val context = getApplication<Application>()
        viewModelScope.launch {
            LocalInstanceStore.delete(context, target.dirName)
                .onSuccess {
                    AppLogger.i("LocalInstance", "实例已删除：${target.dirName}")
                    _state.update {
                        it.copy(
                            deleteTarget = null,
                            message = "已删除实例「${target.name}」及其目录",
                            activeDirName = if (it.activeDirName == target.dirName) "" else it.activeDirName,
                        )
                    }
                    refreshInstances()
                    refreshInstance()
                }
                .onFailure { e ->
                    AppLogger.w("LocalInstance", "删除实例失败：${target.dirName}", e)
                    _state.update { it.copy(deleteTarget = null, message = "删除失败：${e.message}") }
                }
        }
    }

    fun update(transform: (LocalHostUiState) -> LocalHostUiState) = _state.update(transform)

    private fun observeRuntime() {
        viewModelScope.launch {
            LocalServerRuntime.logs.collect { list -> _state.update { it.copy(logs = list) } }
        }
        viewModelScope.launch {
            LocalServerRuntime.running.collect { running ->
                val stopped = wasRunning && !running
                wasRunning = running
                _state.update {
                    it.copy(
                        running = running,
                        jvmCreated = NativeVm.isJvmCreated(),
                        restartPrompt = it.restartPrompt || stopped,
                    )
                }
            }
        }
    }

    /** 读取「本地开服设置」中的默认内存 / JVM 参数 / 保活开关。 */
    private fun loadDefaults() {
        viewModelScope.launch {
            runCatching { store.settingsFlow.first() }
                .onSuccess { s ->
                    _state.update {
                        it.copy(
                            minMem = s.localMinMemMb,
                            maxMem = s.localMaxMemMb,
                            jvmArgs = s.localJvmArgs,
                            keepAlive = s.localKeepAlive,
                            useSerialGc = s.localUseSerialGc,
                        )
                    }
                }
                .onFailure { AppLogger.w("LocalHost", "读取本地开服默认设置失败", it) }
        }
    }

    fun refreshJre() {
        val context = getApplication<Application>()
        val abi = LocalJreManager.currentAbi()
        _state.update {
            it.copy(
                jreInstalled = LocalJreManager.isInstalled(context),
                jreInfo = LocalJreManager.installedInfo(context),
                jreEmbedded = LocalJreManager.hasEmbeddedAsset(context, abi),
                jreAbi = abi,
                javaMajor = LocalJreManager.JAVA_MAJOR,
                jvmCreated = NativeVm.isJvmCreated(),
            )
        }
    }

    /** 安装 JRE：优先 APK 内嵌归档，缺失时按预设地址下载（都做 SHA-256 校验）。 */
    fun installJre() {
        if (_state.value.jreInstalling) return
        _state.update { it.copy(jreInstalling = true, jreProgress = 0f, jreError = null, message = null) }
        AppLogger.i(
            "LocalHost",
            "开始安装 JRE：abi=${LocalJreManager.currentAbi()}，内嵌=${_state.value.jreEmbedded}",
        )
        viewModelScope.launch {
            LocalJreManager.install(getApplication()) { p ->
                _state.update { it.copy(jreProgress = p) }
            }
                .onSuccess {
                    AppLogger.i("LocalHost", "JRE 安装成功")
                    _state.update { it.copy(jreInstalling = false, jreError = null, message = "JRE 安装完成") }
                }
                .onFailure { e ->
                    AppLogger.e("LocalHost", "JRE 安装失败", e)
                    _state.update {
                        it.copy(
                            jreInstalling = false,
                            jreError = "JRE 安装失败：${e.message ?: e::class.java.simpleName}",
                            message = null,
                        )
                    }
                }
            refreshJre()
        }
    }

    /** 从 MSLAPI 拉取核心列表与默认版本。 */
    fun refreshCores() {
        _state.update { it.copy(coresLoading = true) }
        viewModelScope.launch {
            val names = coreInstaller.fetchCoreNames()
            val name = names.firstOrNull().orEmpty()
            _state.update { it.copy(coreNames = names, coreName = name, coresLoading = false) }
            if (name.isNotBlank()) selectCore(name)
        }
    }

    fun selectCore(name: String) {
        _state.update { it.copy(coreName = name, coreVersion = "", coresLoading = true) }
        viewModelScope.launch {
            val versions = coreInstaller.fetchVersions(name)
            val version = versions.lastOrNull().orEmpty()
            _state.update { it.copy(coreVersions = versions, coreVersion = version, coresLoading = false) }
        }
    }

    fun selectVersion(version: String) = _state.update { it.copy(coreVersion = version) }

    /** 下载服务端核心 jar（复用 daemon 创建实例的核心契约，含 SHA-256 校验）。 */
    fun downloadCore() {
        val s = _state.value
        if (s.coreName.isBlank() || s.coreVersion.isBlank()) {
            _state.update { it.copy(message = "请先选择核心与版本") }
            return
        }
        if (s.coreDownloading) return
        _state.update { it.copy(coreDownloading = true, coreProgress = 0f, message = null) }
        val context = getApplication<Application>()
        val serverDir = LocalStorage.serverDir(context, s.serverName)
        viewModelScope.launch {
            coreInstaller.installCore(
                core = s.coreName,
                version = s.coreVersion,
                build = "latest",
                serverDir = serverDir,
                meta = buildMeta(s),
            ) { p -> _state.update { it.copy(coreProgress = p) } }
                .onSuccess { (jar, meta) ->
                    AppLogger.i("LocalHost", "核心就绪：${meta.core} ${meta.coreVersion}，实例目录 ${meta.directory}")
                    _state.update {
                        it.copy(
                            coreDownloading = false,
                            jarInstalled = true,
                            jarPath = jar.absolutePath,
                            activeDirName = meta.directory,
                            instancePath = LocalStorage.displayPath(context, serverDir),
                            instanceFiles = ServerFiles.listInstanceFiles(serverDir),
                            message = "核心下载完成，实例文件已补全（${meta.directory}）",
                        )
                    }
                    refreshInstances()
                }
                .onFailure { e ->
                    AppLogger.e("LocalHost", "核心下载失败", e)
                    _state.update { it.copy(coreDownloading = false, message = "核心下载失败：${e.message}") }
                }
        }
    }

    /** 用当前页面/设置里的参数构造实例元数据。 */
    private fun buildMeta(s: LocalHostUiState): LocalInstanceMeta = LocalInstanceMeta(
        name = s.serverName.ifBlank { ServerFiles.DEFAULT_SERVER_NAME },
        directory = LocalStorage.sanitizeName(s.serverName),
        core = s.coreName,
        coreVersion = s.coreVersion,
        coreBuild = "latest",
        javaMajor = LocalJreManager.JAVA_MAJOR,
        abi = LocalJreManager.currentAbi(),
        minMemMb = s.minMem,
        maxMemMb = s.maxMem,
        jvmArgs = s.jvmArgs.trim(),
        useSerialGc = s.useSerialGc,
        keepAlive = s.keepAlive,
        serverPort = s.serverPort,
        motd = s.motd.ifBlank { s.serverName },
        maxPlayers = s.maxPlayers,
        onlineMode = s.onlineMode,
        difficulty = s.difficulty,
        gamemode = s.gamemode,
    )

    /** 更新实例文件（server.properties / instance.json 等）：已存在的不覆盖用户改动，仅补齐缺失项。 */
    fun applyInstanceFiles() {
        val s = _state.value
        val context = getApplication<Application>()
        val dir = LocalStorage.serverDir(context, s.serverName)
        if (!dir.isDirectory || !File(dir, ServerFiles.SERVER_JAR_NAME).isFile) {
            _state.update { it.copy(message = "请先下载服务端核心") }
            return
        }
        viewModelScope.launch {
            ServerFiles.complete(dir, buildMeta(s))
                .onSuccess { meta ->
                    AppLogger.i("LocalHost", "实例文件已更新：${meta.directory}")
                    _state.update {
                        it.copy(
                            instancePath = LocalStorage.displayPath(context, dir),
                            instanceFiles = ServerFiles.listInstanceFiles(dir),
                            message = "实例文件已补全/更新（${meta.directory}）",
                        )
                    }
                }
                .onFailure { e ->
                    AppLogger.w("LocalHost", "补全实例文件失败", e)
                    _state.update { it.copy(message = "补全实例文件失败：${e.message}") }
                }
        }
    }

    fun start() {
        val s = _state.value
        if (s.running) return
        val context = getApplication<Application>()
        val serverDir = LocalStorage.serverDir(context, s.serverName)
        val serverJar = File(serverDir, ServerFiles.SERVER_JAR_NAME)
        if (!serverJar.isFile) {
            _state.update { it.copy(message = "请先下载服务端核心（实例目录：${LocalStorage.displayPath(context, serverDir)}）") }
            return
        }
        if (!LocalJreManager.isInstalled(getApplication())) {
            _state.update { it.copy(message = "请先安装 JRE 运行时") }
            return
        }
        if (NativeVm.isJvmCreated()) {
            _state.update {
                it.copy(
                    message = "本进程已创建过 JVM，Android 上无法重启：请完全退出 App 后重试",
                    restartPrompt = true,
                )
            }
            return
        }
        val extraArgs = s.jvmArgs.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        LocalServerRuntime.clearLogs()
        _state.update { it.copy(message = null, logs = emptyList()) }
        viewModelScope.launch {
            // 启动前再补一次实例文件：用户可能改了名称/端口，或目录被外部工具动过（幂等、不覆盖已有改动）
            val meta = ServerFiles.complete(serverDir, buildMeta(s)).getOrElse { e ->
                AppLogger.w("LocalHost", "启动前补全实例文件失败", e)
                buildMeta(s)
            }
            _state.update {
                it.copy(
                    instancePath = LocalStorage.displayPath(context, serverDir),
                    instanceFiles = ServerFiles.listInstanceFiles(serverDir),
                )
            }
            LocalServerRuntime.start(
                context = context,
                jreHome = LocalJreManager.jreHome(context),
                serverJar = serverJar,
                workDir = serverDir,
                serverName = meta.name,
                minMemM = meta.minMemMb,
                maxMemM = meta.maxMemMb,
                extraArgs = extraArgs,
                useSerialGc = meta.useSerialGc,
                keepAlive = meta.keepAlive,
            ).onFailure { e ->
                AppLogger.e("LocalHost", "启动本机服务端失败", e)
                _state.update { it.copy(message = "启动失败：${e.message ?: "详见日志"}", running = false) }
            }
            refreshJre()
        }
    }

    fun stop() {
        if (!_state.value.running) {
            _state.update { it.copy(message = "服务端未在运行") }
            return
        }
        LocalServerRuntime.stop()
        _state.update { it.copy(message = "已发送 stop，等待服务端保存并退出") }
    }

    fun dismissRestartPrompt() = _state.update { it.copy(restartPrompt = false) }

    fun clearLogs() {
        LocalServerRuntime.clearLogs()
        _state.update { it.copy(logs = emptyList(), message = null) }
    }
}
