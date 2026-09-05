package com.mslx.console.ui.local

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.MSLXApplication
import com.mslx.console.data.AppLogger
import com.mslx.console.data.InstanceRepository
import com.mslx.console.data.localengine.LocalCoreInstaller
import com.mslx.console.data.localengine.LocalJreManager
import com.mslx.console.data.localengine.LocalServerProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class LocalHostUiState(
    // JRE
    val jreInstalled: Boolean = false,
    val jreInstalling: Boolean = false,
    val jreProgress: Float = 0f,
    val jreZipUrl: String = "",
    val javaPath: String = "",
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
    // 服务端配置（参照 daemon 实例配置）
    val serverName: String = "本地服务器",
    val minMem: Int = 1024,
    val maxMem: Int = 2048,
    val jvmArgs: String = "",
    // 运行
    val running: Boolean = false,
    val logs: List<String> = emptyList(),
    val message: String? = null,
)

/** 本机开服引擎（P1）：JRE 自动下载 + MSLAPI 核心下载 + daemon 式配置启停。 */
class LocalHostViewModel(application: Application) : AndroidViewModel(application) {

    private val appDir: File = application.getExternalFilesDir(null) ?: application.filesDir
    private val worldsDir = File(appDir, "worlds")

    private val repository: InstanceRepository = getApplication<MSLXApplication>().container.instanceRepository
    private val coreInstaller = LocalCoreInstaller(repository)

    private val _state = MutableStateFlow(
        LocalHostUiState(
            jreInstalled = LocalJreManager.isInstalled(application),
            javaPath = LocalJreManager.installedJava(application).absolutePath,
        ),
    )
    val state = _state.asStateFlow()

    private var server: LocalServerProcess? = null

    init {
        refreshJre()
        refreshCores()
    }

    fun update(transform: (LocalHostUiState) -> LocalHostUiState) = _state.update(transform)

    fun refreshJre() {
        _state.update {
            it.copy(
                jreInstalled = LocalJreManager.isInstalled(getApplication()),
                javaPath = LocalJreManager.installedJava(getApplication()).absolutePath,
            )
        }
    }

    /** 下载 Android JRE（zip 包，需用户提供可用的 bionic JRE 下载地址）。 */
    fun downloadJre() {
        val url = _state.value.jreZipUrl.trim()
        if (url.isBlank()) {
            _state.update { it.copy(message = "请先填写 Android JRE 的 .zip 下载地址（可留空跳过，使用已安装路径）") }
            return
        }
        if (_state.value.jreInstalling) return
        _state.update { it.copy(jreInstalling = true, jreProgress = 0f, message = null) }
        viewModelScope.launch {
            LocalJreManager.installFromZip(
                context = getApplication(),
                url = url,
                expectedSha256 = null,
            ) { p -> _state.update { it.copy(jreProgress = p) } }
                .onSuccess { java ->
                    _state.update { it.copy(jreInstalling = false, jreInstalled = true, javaPath = java.absolutePath, message = "JRE 安装完成") }
                }
                .onFailure { e ->
                    AppLogger.e("LocalHost", "JRE 下载失败", e)
                    _state.update { it.copy(jreInstalling = false, message = "JRE 下载失败：${e.message}") }
                }
        }
    }

    /** 从 MSLAPI v4 mirrors 拉取核心列表与默认版本。 */
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

    /** 下载服务端核心 jar 到世界目录（复用 daemon 创建实例的核心契约，含 SHA-256 校验）。 */
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
                    _state.update { it.copy(coreDownloading = false, jarInstalled = true, jarPath = jar.absolutePath, message = "核心下载完成") }
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
            _state.update { it.copy(message = "未找到 JRE，请先下载或配置 java 路径") }
            return
        }
        val extraArgs = s.jvmArgs.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val workDir = File(worldsDir, s.serverName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        val engine = LocalServerProcess(
            javaBin = File(s.javaPath),
            serverJar = File(s.jarPath),
            workDir = workDir,
            minMemM = s.minMem,
            maxMemM = s.maxMem,
            extraArgs = extraArgs,
        )
        server = engine
        viewModelScope.launch {
            engine.logs.collect { line ->
                _state.update { it.copy(logs = (it.logs + line).takeLast(500)) }
            }
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { engine.start() }
            _state.update { it.copy(running = ok, message = if (ok) null else "启动失败：请检查 JRE 与核心路径") }
            AppLogger.i("LocalHost", "本地引擎启动 ok=$ok")
        }
    }

    fun stop() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { server?.stop() }
            _state.update { it.copy(running = false) }
        }
    }
}
