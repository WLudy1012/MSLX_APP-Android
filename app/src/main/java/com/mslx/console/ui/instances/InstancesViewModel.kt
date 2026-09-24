package com.mslx.console.ui.instances

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.MSLXApplication
import com.mslx.console.data.AppLogger
import com.mslx.console.data.AppSettings
import com.mslx.console.data.ManagedServer
import com.mslx.console.data.ensureRepository
import com.mslx.console.data.localengine.LocalInstanceStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class InstancesUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val deleting: Boolean = false,
    val deleteError: String? = null,
    val error: String? = null,
    val servers: List<ManagedServer> = emptyList(),
)

/**
 * 统一实例列表：本机开服实例 + **所有** Daemon 的实例（经 [com.mslx.console.data.ServerCatalog] 聚合）。
 * 条目一律用 [com.mslx.console.data.ServerRef] 定位：删哪个服务端的实例就操作哪台的仓储。
 */
class InstancesViewModel(application: Application) : AndroidViewModel(application) {

    private val container = getApplication<MSLXApplication>().container
    private val catalog = container.serverCatalog
    private val store = container.settingsStore

    private val _state = MutableStateFlow(InstancesUiState())
    val state = _state.asStateFlow()

    init {
        refresh(initial = true)
    }

    fun delete(server: ManagedServer, deleteFiles: Boolean, onDone: () -> Unit) {
        if (_state.value.deleting) return
        _state.update { it.copy(deleting = true, deleteError = null) }
        viewModelScope.launch {
            if (server.isLocal) {
                val dir = server.localDirName
                if (dir.isNullOrBlank()) {
                    _state.update { it.copy(deleting = false, deleteError = "无法确定实例目录") }
                    return@launch
                }
                LocalInstanceStore.delete(getApplication(), dir).fold(
                    onSuccess = {
                        _state.update { it.copy(deleting = false, deleteError = null) }
                        onDone()
                        refresh()
                    },
                    onFailure = { e ->
                        _state.update { it.copy(deleting = false, deleteError = e.message ?: "删除失败") }
                    },
                )
            } else {
                val ref = server.ref
                val id = server.remoteId
                if (ref == null || id == null) {
                    _state.update { it.copy(deleting = false, deleteError = "无法确定实例归属的服务端") }
                    return@launch
                }
                val repository = container.ensureRepository(ref.daemonId)
                if (repository == null) {
                    _state.update { it.copy(deleting = false, deleteError = "该实例所属服务端已不存在，请先检查连接配置") }
                    return@launch
                }
                repository.deleteInstance(id, deleteFiles).fold(
                    onSuccess = {
                        _state.update { it.copy(deleting = false, deleteError = null) }
                        onDone()
                        refresh()
                    },
                    onFailure = { e ->
                        _state.update { it.copy(deleting = false, deleteError = e.message ?: "删除失败") }
                    },
                )
            }
        }
    }

    fun refresh(initial: Boolean = false) {
        if (initial) {
            _state.update { it.copy(loading = true, error = null) }
        } else {
            _state.update { it.copy(refreshing = true, error = null) }
        }
        viewModelScope.launch {
            val settings = runCatching { store.settingsFlow.first() }.getOrDefault(AppSettings())
            runCatching { catalog.load(settings) }
                .onSuccess { servers ->
                    _state.update {
                        it.copy(loading = false, refreshing = false, error = null, servers = servers)
                    }
                }
                .onFailure { e ->
                    AppLogger.w("Instances", "加载统一实例列表失败", e)
                    _state.update {
                        it.copy(loading = false, refreshing = false, error = e.message ?: "加载失败")
                    }
                }
        }
    }
}
