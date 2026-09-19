package com.mslx.console.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.MSLXApplication
import com.mslx.console.data.AppLogger
import com.mslx.console.data.localengine.LocalJreManager
import com.mslx.console.data.localengine.ShizukuController
import com.mslx.console.data.localengine.ShizukuStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 本机 Java 运行时选项（供设置页安装/切换）。 */
data class JreOption(
    val id: String,
    val label: String,
    val javaMajor: Int,
    val installed: Boolean,
    val embedded: Boolean,
    /** 是否存在可用的 Android 构建（false 时只展示、不可安装）。 */
    val supported: Boolean = true,
    val unavailableReason: String = "",
)

/**
 * 本机开服的默认性能设置（内存 / JVM 参数 / 保活 / GC），存 DataStore，
 * 本机开服页启动服务端时读取这些默认值（页面内可临时覆盖）。
 */
class LocalServerSettingsViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val minMem: String = "1024",
        val maxMem: String = "2048",
        val jvmArgs: String = "",
        val keepAlive: Boolean = true,
        val useSerialGc: Boolean = true,
        val useShizuku: Boolean = false,
        val shizukuStatus: ShizukuStatus = ShizukuStatus.DEAD,
        val shizukuStatusText: String = "",
        // 本机 Java 运行时（JRE 安装/版本）
        val jreAbi: String = "",
        val runtimeOptions: List<JreOption> = emptyList(),
        val selectedRuntimeId: String = LocalJreManager.RUNTIME_ID,
        val jreInstalling: Boolean = false,
        val jreProgress: Float = 0f,
        val jreError: String? = null,
        val loaded: Boolean = false,
        val message: String? = null,
    )

    private val store = getApplication<MSLXApplication>().container.settingsStore

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init {
        ShizukuController.init()
        refreshJre()
        viewModelScope.launch {
            ShizukuController.status.collect { st ->
                _state.update { it.copy(shizukuStatus = st, shizukuStatusText = statusText(st)) }
            }
        }
        viewModelScope.launch {
            runCatching { store.settingsFlow.first() }
                .onSuccess { s ->
                    _state.update {
                        it.copy(
                            minMem = s.localMinMemMb.toString(),
                            maxMem = s.localMaxMemMb.toString(),
                            jvmArgs = s.localJvmArgs,
                            keepAlive = s.localKeepAlive,
                            useSerialGc = s.localUseSerialGc,
                            useShizuku = s.localUseShizuku,
                            loaded = true,
                        )
                    }
                }
                .onFailure {
                    AppLogger.w("LocalSettings", "读取本机开服设置失败", it)
                    _state.update { it.copy(loaded = true) }
                }
        }
    }

    fun update(transform: (UiState) -> UiState) = _state.update(transform)

    /** 刷新当前 ABI 下可安装运行时及安装状态。 */
    fun refreshJre() {
        val context = getApplication<Application>()
        val abi = LocalJreManager.currentAbi()
        val options = LocalJreManager.availableRuntimes(abi).map { rt ->
            JreOption(
                id = rt.id,
                label = rt.label,
                javaMajor = rt.javaMajor,
                installed = LocalJreManager.isInstalled(context, rt),
                embedded = LocalJreManager.hasEmbeddedAsset(context, rt, abi),
                supported = rt.supported,
                unavailableReason = LocalJreManager.installBlockReason(rt, abi).orEmpty(),
            )
        }
        _state.update { s ->
            val valid = options.any { it.id == s.selectedRuntimeId && it.supported }
            s.copy(
                jreAbi = abi,
                runtimeOptions = options,
                selectedRuntimeId = if (valid) {
                    s.selectedRuntimeId
                } else {
                    options.firstOrNull { it.supported && it.installed }?.id ?: LocalJreManager.RUNTIME_ID
                },
            )
        }
    }

    /** 选中某个运行时（安装中禁改）。 */
    fun selectRuntime(id: String) {
        if (_state.value.jreInstalling) return
        if (LocalJreManager.runtimeById(id).let { !it.supported && !LocalJreManager.isInstalled(getApplication(), it) }) return
        _state.update { it.copy(selectedRuntimeId = id, jreError = null) }
    }

    /** 安装/修复选中的运行时：优先 APK 内嵌归档，缺失时按预设地址下载（含 SHA-256 校验）。 */
    fun installJre(id: String = _state.value.selectedRuntimeId) {
        if (_state.value.jreInstalling) return
        val runtime = LocalJreManager.runtimeById(id)
        _state.update { it.copy(jreInstalling = true, jreProgress = 0f, jreError = null, selectedRuntimeId = runtime.id, message = null) }
        viewModelScope.launch {
            LocalJreManager.install(getApplication(), runtime) { p ->
                _state.update { it.copy(jreProgress = p) }
            }.onSuccess {
                AppLogger.i("LocalSettings", "运行时安装完成：${runtime.label}")
                _state.update { it.copy(jreInstalling = false, jreError = null, message = "${runtime.label} 安装完成") }
            }.onFailure { e ->
                AppLogger.e("LocalSettings", "运行时安装失败", e)
                _state.update {
                    it.copy(
                        jreInstalling = false,
                        jreError = "${runtime.label} 安装失败：${e.message ?: e::class.java.simpleName}",
                    )
                }
            }
            refreshJre()
        }
    }

    /** 开关增强模式：开启时若未授权会先申请 Shizuku 授权；不可用时保持关闭（启动时自动回退进程内）。 */
    fun toggleEnhanced(enabled: Boolean) {
        if (!enabled) {
            persistEnhanced(false)
            return
        }
        ShizukuController.refresh()
        when (ShizukuController.status.value) {
            ShizukuStatus.READY -> persistEnhanced(true)
            ShizukuStatus.DEAD -> _state.update {
                it.copy(useShizuku = false, message = "未检测到 Shizuku：请先安装并激活（无线调试 / 电脑 ADB / root）后再开启")
            }
            ShizukuStatus.NO_PERMISSION -> ShizukuController.requestPermission { granted ->
                if (granted) persistEnhanced(true)
                else _state.update { it.copy(useShizuku = false, message = "未获得 Shizuku 授权，增强模式保持关闭") }
            }
        }
    }

    /** 仅申请/刷新 Shizuku 授权（不改动开关）。 */
    fun authorizeShizuku() {
        ShizukuController.refresh()
        if (ShizukuController.isReady()) {
            _state.update { it.copy(message = "Shizuku 已授权") }
            return
        }
        if (ShizukuController.status.value == ShizukuStatus.DEAD) {
            _state.update { it.copy(message = "未检测到 Shizuku，请先安装并激活") }
            return
        }
        ShizukuController.requestPermission { granted ->
            _state.update { it.copy(message = if (granted) "Shizuku 授权成功" else "Shizuku 授权被拒绝") }
        }
    }

    private fun persistEnhanced(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { store.setLocalUseShizuku(enabled) }
                .onSuccess {
                    _state.update {
                        it.copy(
                            useShizuku = enabled,
                            message = if (enabled) "已开启增强模式（Shizuku exec 子进程）" else "已关闭增强模式（回到进程内 JVM）",
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(message = "保存失败：${e.message}") } }
        }
    }

    private fun statusText(st: ShizukuStatus): String = when (st) {
        ShizukuStatus.DEAD -> "未检测到 Shizuku：请安装 Shizuku 并用「无线调试 / 电脑 ADB / root」任一方式激活。"
        ShizukuStatus.NO_PERMISSION -> "Shizuku 运行中，但尚未授权本应用（点按「授权 Shizuku」）。"
        ShizukuStatus.READY -> "已授权：增强模式可用（exec 真 java 子进程，支持多实例 / 可重启 / 跨 Java 版本）。"
    }

    fun resetDefaults() {
        _state.update {
            it.copy(
                minMem = "1024",
                maxMem = "2048",
                jvmArgs = "",
                keepAlive = true,
                useSerialGc = true,
                message = "已填入推荐默认值，记得点保存",
            )
        }
    }

    fun save() {
        val s = _state.value
        val min = s.minMem.toIntOrNull()?.coerceIn(256, 16384) ?: 1024
        val max = s.maxMem.toIntOrNull()?.coerceIn(256, 16384) ?: 2048
        val lo = minOf(min, max)
        val hi = maxOf(min, max)
        viewModelScope.launch {
            runCatching { store.setLocalServer(lo, hi, s.jvmArgs.trim(), s.keepAlive, s.useSerialGc) }
                .onSuccess {
                    AppLogger.i("LocalSettings", "已保存本机开服设置: ${lo}-${hi}MB keepAlive=${s.keepAlive} serialGc=${s.useSerialGc}")
                    _state.update {
                        it.copy(
                            minMem = lo.toString(),
                            maxMem = hi.toString(),
                            message = "已保存：最小 ${lo}MB / 最大 ${hi}MB",
                        )
                    }
                }
                .onFailure { e ->
                    AppLogger.w("LocalSettings", "保存本机开服设置失败", e)
                    _state.update { it.copy(message = "保存失败：${e.message}") }
                }
        }
    }
}
