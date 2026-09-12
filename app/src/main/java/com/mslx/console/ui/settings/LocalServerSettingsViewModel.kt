package com.mslx.console.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.MSLXApplication
import com.mslx.console.data.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
        val loaded: Boolean = false,
        val message: String? = null,
    )

    private val store = getApplication<MSLXApplication>().container.settingsStore

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init {
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
