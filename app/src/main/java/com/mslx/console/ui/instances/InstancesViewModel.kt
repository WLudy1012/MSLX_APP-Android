package com.mslx.console.ui.instances

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.MSLXApplication
import com.mslx.console.data.AppLogger
import com.mslx.console.data.AppSettings
import com.mslx.console.data.ManagedServer
import com.mslx.console.data.ensureRepository
import com.mslx.console.data.isStoppableStatus
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
    /** 正在下发启停操作的实例 key（对应行按钮显示进度，避免重复下发）。 */
    val busyKeys: Set<String> = emptySet(),
    /** 一键停止全部进行中。 */
    val stoppingAll: Boolean = false,
    /** 操作结果提示（由 UI 弹 Snackbar 后调 [InstancesViewModel.clearActionMessage] 清空）。 */
    val actionMessage: String? = null,
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

    fun clearActionMessage() {
        _state.update { it.copy(actionMessage = null) }
    }

    /**
     * 启停单个实例（按 [ManagedServer.ref] 下发：本机实例走 LocalServerManager，
     * 远程实例走它所属那台 Daemon 的仓储，去主连接后不再存在“当前连接”）。
     */
    fun toggle(server: ManagedServer, start: Boolean) {
        if (server.key in _state.value.busyKeys) return
        _state.update { it.copy(busyKeys = it.busyKeys + server.key, actionMessage = null) }
        viewModelScope.launch {
            val result = runCatchingWithBusy(server, start)
            _state.update { it.copy(busyKeys = it.busyKeys - server.key) }
            result.fold(
                onSuccess = { message ->
                    _state.update { it.copy(actionMessage = message) }
                    refresh()
                },
                onFailure = { e ->
                    AppLogger.w("Instances", "实例操作失败：${server.name}", e)
                    _state.update { it.copy(actionMessage = e.message ?: "操作失败") }
                },
            )
        }
    }

    /**
     * 一键停止全部：逐个按实例归属下发 stop（过渡态也算需停），失败项汇总提示。
     * 串行而非并行：避开部分 Daemon 对同一实例并发操作的拒绝，也让失败原因能逐项归属。
     */
    fun stopAll() {
        if (_state.value.stoppingAll) return
        val targets = _state.value.servers.filter { isStoppableStatus(it.status) }
        if (targets.isEmpty()) {
            _state.update { it.copy(actionMessage = "没有运行中的实例") }
            return
        }
        _state.update { it.copy(stoppingAll = true, actionMessage = null) }
        viewModelScope.launch {
            val failed = mutableListOf<String>()
            targets.forEach { server ->
                _state.update { it.copy(busyKeys = it.busyKeys + server.key) }
                runCatchingWithBusy(server, start = false)
                    .onFailure { failed += server.name }
                _state.update { it.copy(busyKeys = it.busyKeys - server.key) }
            }
            _state.update {
                it.copy(
                    stoppingAll = false,
                    actionMessage = when {
                        failed.isEmpty() -> "已停止 ${targets.size} 个实例"
                        failed.size == targets.size -> "全部停止失败：${failed.joinToString("、")}"
                        else -> "已停止 ${targets.size - failed.size} 个，失败：${failed.joinToString("、")}"
                    },
                )
            }
            refresh()
        }
    }

    /** 下发一次启停，返回可直接展示的结果文案；失败以异常抛出。 */
    private suspend fun runCatchingWithBusy(server: ManagedServer, start: Boolean): Result<String> {
        val message = if (server.isLocal) {
            val dir = server.localDirName
            if (dir.isNullOrBlank()) return Result.failure(IllegalStateException("无法确定实例目录"))
            if (!start) {
                // 本机停止是「向服务端发 stop 后等它自己退出」，不等价于完成
                container.localServerManager.stop()
                "已发送停止命令：${server.name}"
            } else {
                container.localServerManager.start(dir)
                    .map { "已启动本机实例：${server.name}" }
                    .getOrElse { e -> return Result.failure(e) }
            }
        } else {
            val ref = server.ref
            val id = server.remoteId
            if (ref == null || id == null) return Result.failure(IllegalStateException("无法确定实例归属的服务端"))
            val repository = container.ensureRepository(ref.daemonId)
                ?: return Result.failure(IllegalStateException("该实例所属服务端已不存在，请先检查连接配置"))
            repository.sendAction(id, if (start) "start" else "stop")
                .map { "已${if (start) "启动" else "停止"}：${server.name}" }
                .getOrElse { e -> return Result.failure(e) }
        }
        AppLogger.i("Instances", message)
        return Result.success(message)
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
