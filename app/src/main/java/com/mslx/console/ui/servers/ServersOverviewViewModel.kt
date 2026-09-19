package com.mslx.console.ui.servers

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.MSLXApplication
import com.mslx.console.data.AppLogger
import com.mslx.console.data.AppSettings
import com.mslx.console.data.DaemonStatus
import com.mslx.console.data.ManagedServer
import com.mslx.console.data.localengine.LocalInstanceStore
import com.mslx.console.data.localengine.LocalServerRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServersOverviewUiState(
    val loading: Boolean = true,
    val daemons: List<DaemonStatus> = emptyList(),
    val servers: List<ManagedServer> = emptyList(),
    val localBackendName: String = "",
    val message: String? = null,
)

/**
 * 服务端总览：**多 Daemon 连接状态 + 统一服务端列表（本机开服实例与各 Daemon 实例）**。
 * 本机开服由此纳入通用服务端管理体系（同一列表、同一操作入口）。
 */
class ServersOverviewViewModel(application: Application) : AndroidViewModel(application) {

    private val container = getApplication<MSLXApplication>().container
    private val registry = container.daemonRegistry
    private val catalog = container.serverCatalog
    private val store = container.settingsStore

    private val _state = MutableStateFlow(ServersOverviewUiState())
    val state = _state.asStateFlow()

    init {
        refresh()
    }

    /** 并行刷新各 Daemon 状态 + 重新聚合服务端列表。 */
    fun refresh() {
        _state.update { it.copy(loading = true, message = null) }
        viewModelScope.launch {
            val settings = runCatching { store.settingsFlow.first() }.getOrDefault(AppSettings())
            registry.sync(settings)
            val servers = runCatching { catalog.load(settings) }
                .onFailure { AppLogger.w("Servers", "聚合服务端列表失败", it) }
                .getOrDefault(emptyList())
            _state.update {
                it.copy(
                    loading = false,
                    daemons = settings.daemons.map { d -> registry.statusOf(d.id).copy(name = d.name) },
                    servers = servers,
                    localBackendName = LocalServerRuntime.currentServerName,
                )
            }
        }
    }

    /** 把某台 Daemon 设为主连接（既有页面随后都指向它）。 */
    fun setPrimary(daemonId: String) {
        viewModelScope.launch {
            runCatching { store.setActiveDaemon(daemonId) }
                .onSuccess {
                    val settings = runCatching { store.settingsFlow.first() }.getOrDefault(AppSettings())
                    registry.sync(settings)
                    val name = settings.daemons.firstOrNull { it.id == daemonId }?.name.orEmpty()
                    AppLogger.i("Servers", "已切换主 Daemon：$name")
                    _state.update { it.copy(message = "已把「${name.ifBlank { daemonId }}」设为主连接") }
                    refresh()
                }
                .onFailure { e ->
                    AppLogger.w("Servers", "切换主 Daemon 失败", e)
                    _state.update { it.copy(message = "切换失败：${e.message}") }
                }
        }
    }

    /** 停止本机服务端（本机实例的运行控制在总览页也可用）。 */
    fun stopLocal() {
        if (!LocalServerRuntime.running.value) {
            _state.update { it.copy(message = "本机服务端未在运行") }
            return
        }
        LocalServerRuntime.stop()
        _state.update { it.copy(message = "已向本机服务端发送 stop") }
    }

    /** 删除本机实例（整目录，含世界存档）。 */
    fun deleteLocal(dirName: String) {
        viewModelScope.launch {
            LocalInstanceStore.delete(getApplication(), dirName)
                .onSuccess {
                    _state.update { it.copy(message = "已删除本机实例：$dirName") }
                    refresh()
                }
                .onFailure { e ->
                    _state.update { it.copy(message = "删除失败：${e.message}") }
                }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }
}
