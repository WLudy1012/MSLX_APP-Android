package com.mslx.console.ui.local

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.MSLXApplication
import com.mslx.console.data.AppLogger
import com.mslx.console.data.InstanceRepository
import com.mslx.console.data.localengine.LocalCoreInstaller
import com.mslx.console.data.localengine.LocalJreManager
import com.mslx.console.data.localengine.LocalServerRuntime
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
    // 服务端配置（默认值来自「本地开服设置」，页面内可临时覆盖）
    val serverName: String = "本地服务器",
    val minMem: Int = 1024,
    val maxMem: Int = 2048,
    val jvmArgs: String = "",
    val keepAlive: Boolean = true,
    val useSerialGc: Boolean = true,
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

    private val worldsDir = File(application.filesDir, "worlds")

    private val container = getApplication<MSLXApplication>().container
    private val repository: InstanceRepository = container.instanceRepository
    private val store = container.settingsStore
    private val coreInstaller = LocalCoreInstaller(repository)

    private val _state = MutableStateFlow(LocalHostUiState())
    val state = _state.asStateFlow()

    /** 用于识别「运行中 → 已停止」跳变，触发重启提示弹窗。 */
    private var wasRunning = false

    init {
        refreshJre()
        refreshCores()
        loadDefaults()
        observeRuntime()
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
        _state.update { it.copy(jreInstalling = true, jreProgress = 0f, message = null) }
        viewModelScope.launch {
            LocalJreManager.install(getApplication()) { p ->
                _state.update { it.copy(jreProgress = p) }
            }
                .onSuccess {
                    AppLogger.i("LocalHost", "JRE 安装成功")
                    _state.update { it.copy(jreInstalling = false, message = "JRE 安装完成") }
                }
                .onFailure { e ->
                    AppLogger.e("LocalHost", "JRE 安装失败", e)
                    _state.update { it.copy(jreInstalling = false, message = "JRE 安装失败：${e.message}") }
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
        viewModelScope.launch {
            coreInstaller.installCore(
                core = s.coreName,
                version = s.coreVersion,
                build = "latest",
                worldsDir = worldsDir,
            ) { p -> _state.update { it.copy(coreProgress = p) } }
                .onSuccess { jar ->
                    _state.update {
                        it.copy(
                            coreDownloading = false,
                            jarInstalled = true,
                            jarPath = jar.absolutePath,
                            message = "核心下载完成",
                        )
                    }
                }
                .onFailure { e ->
                    AppLogger.e("LocalHost", "核心下载失败", e)
                    _state.update { it.copy(coreDownloading = false, message = "核心下载失败：${e.message}") }
                }
        }
    }

    fun start() {
        val s = _state.value
        if (s.running) return
        if (!s.jarInstalled || s.jarPath.isBlank()) {
            _state.update { it.copy(message = "请先下载服务端核心") }
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
        val context = getApplication<Application>()
        val extraArgs = s.jvmArgs.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val workDir = File(worldsDir, s.serverName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        LocalServerRuntime.clearLogs()
        _state.update { it.copy(message = null, logs = emptyList()) }
        viewModelScope.launch {
            LocalServerRuntime.start(
                context = context,
                jreHome = LocalJreManager.jreHome(context),
                serverJar = File(s.jarPath),
                workDir = workDir,
                serverName = s.serverName,
                minMemM = s.minMem,
                maxMemM = s.maxMem,
                extraArgs = extraArgs,
                useSerialGc = s.useSerialGc,
                keepAlive = s.keepAlive,
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
