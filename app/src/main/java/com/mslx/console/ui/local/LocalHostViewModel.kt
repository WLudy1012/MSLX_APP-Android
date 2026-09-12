package com.mslx.console.ui.local

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.MSLXApplication
import com.mslx.console.data.AppLogger
import com.mslx.console.data.InstanceRepository
import com.mslx.console.data.localengine.LocalCoreInstaller
import com.mslx.console.data.localengine.LocalJreManager
import com.mslx.console.data.localengine.LocalJvmLauncher
import com.mslx.console.localengine.NativeVm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    // 服务端配置（参照 daemon 实例）
    val serverName: String = "本地服务器",
    val minMem: Int = 1024,
    val maxMem: Int = 2048,
    val jvmArgs: String = "",
    // 运行
    val running: Boolean = false,
    val jvmCreated: Boolean = false,
    val logs: List<String> = emptyList(),
    val message: String? = null,
)

/**
 * 本机开服（进程内 JVM 架构）：内嵌/下载 Android JRE → 解压到私有目录 →
 * native dlopen(libjvm.so) + JNI_CreateJavaVM 在 App 进程内起服 → 控制台经 pipe 收发。
 */
class LocalHostViewModel(application: Application) : AndroidViewModel(application) {

    private val worldsDir = File(application.filesDir, "worlds")

    private val repository: InstanceRepository = getApplication<MSLXApplication>().container.instanceRepository
    private val coreInstaller = LocalCoreInstaller(repository)

    private val _state = MutableStateFlow(LocalHostUiState())
    val state = _state.asStateFlow()

    private var launcher: LocalJvmLauncher? = null

    init {
        refreshJre()
        refreshCores()
    }

    fun update(transform: (LocalHostUiState) -> LocalHostUiState) = _state.update(transform)

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

    /** 安装 JRE：优先用 APK 内嵌归档，缺失时按预设地址下载（都做 SHA-256 校验）。 */
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
            // 版本列表通常由旧到新，取最后一个作为默认
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
            _state.update { it.copy(message = "本进程已创建过 JVM，Android 上无法重启：请完全退出 App 后重试") }
            return
        }
        val jreHome = LocalJreManager.jreHome(getApplication())
        val extraArgs = s.jvmArgs.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val workDir = File(worldsDir, s.serverName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        val engine = LocalJvmLauncher(
            jreHome = jreHome,
            serverJar = File(s.jarPath),
            workDir = workDir,
            minMemM = s.minMem,
            maxMemM = s.maxMem,
            extraArgs = extraArgs,
        )
        launcher = engine
        viewModelScope.launch {
            engine.logs.collect { line ->
                _state.update { it.copy(logs = (it.logs + line).takeLast(800)) }
            }
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { engine.start() }
            _state.update {
                it.copy(
                    running = ok,
                    jvmCreated = NativeVm.isJvmCreated(),
                    message = if (ok) null else "启动失败：详见下方日志",
                )
            }
        }
    }

    fun stop() {
        val engine = launcher
        if (engine == null || !engine.running) {
            _state.update { it.copy(message = "服务端未在运行") }
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.stop() }
            _state.update { it.copy(running = engine.running, message = "已发送 stop，等待服务端保存并退出") }
        }
    }

    fun clearLogs() = _state.update { it.copy(logs = emptyList(), message = null) }
}
