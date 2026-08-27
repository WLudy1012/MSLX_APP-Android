package com.mslx.console.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.MSLXApplication
import com.mslx.console.data.AppLogger
import com.mslx.console.data.remote.ApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 连接连通性状态。 */
data class ConnectivityUiState(
    /** null=尚未检测；true=在线；false=离线。 */
    val online: Boolean? = null,
)

/** 连接连通性一次性事件。 */
sealed interface ConnectivityEvent {
    /** 使用过程中从在线变为离线（触发全局弹窗提醒）。 */
    data object WentOffline : ConnectivityEvent
}

/**
 * 后台连接连通性监视器（activity 作用域，全局单例）：
 * 每 5 秒对当前激活 Daemon 做一次轻量 verify()，状态供主页显示；
 * 若使用过程中在线→离线，发出 WentOffline 事件由全局弹窗提醒。
 */
class ConnectivityViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = getApplication<MSLXApplication>().container.instanceRepository
    private val store = getApplication<MSLXApplication>().container.settingsStore

    private val _state = MutableStateFlow(ConnectivityUiState())
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<ConnectivityEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    private var wasOnline: Boolean? = null

    init {
        viewModelScope.launch {
            while (isActive) {
                checkOnce()
                delay(5_000)
            }
        }
    }

    /** 立即执行一次连通性检查（也用于启动后的首查）。 */
    suspend fun checkOnce() {
        val settings = store.settingsFlow.first()
        val daemon = settings.activeDaemon
        if (daemon == null) {
            _state.value = ConnectivityUiState(online = null)
            wasOnline = null
            return
        }
        // 使用保存的激活连接（与 HomeViewModel 自动连接保持同一配置来源）
        val normalized = ApiClient.normalizeDaemonUrl(daemon.baseUrl, daemon.allowHttp)
        runCatching {
            repository.configure(normalized, daemon.apiKey, daemon.allowHttp)
        }
        val online = runCatching { repository.verify().getOrThrow(); true }.getOrDefault(false)
        AppLogger.d("Connectivity", "连通性检查 ${if (online) "在线" else "离线"} $normalized")
        val previous = wasOnline
        if (previous == true && !online) {
            AppLogger.w("Connectivity", "守护进程连接中断 $normalized")
            _events.tryEmit(ConnectivityEvent.WentOffline)
        }
        wasOnline = online
        _state.value = ConnectivityUiState(online = online)
    }
}
