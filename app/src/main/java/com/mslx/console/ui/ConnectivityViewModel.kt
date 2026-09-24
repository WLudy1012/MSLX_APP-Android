package com.mslx.console.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.MSLXApplication
import com.mslx.console.data.AppLogger
import kotlinx.coroutines.CancellationException
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
    /** 默认 Daemon 的状态：null=尚未检测；true=在线；false=离线（主页首屏与全局弹窗用）。 */
    val online: Boolean? = null,
    /** 各 Daemon 最近一次探测结果（daemonId -> 在线），供多页主页逐台展示。 */
    val perDaemon: Map<String, Boolean> = emptyMap(),
)

/** 连接连通性一次性事件。 */
sealed interface ConnectivityEvent {
    /** 使用过程中从在线变为离线（触发全局弹窗提醒）。 */
    data object WentOffline : ConnectivityEvent
}

/**
 * 后台连接连通性监视器（activity 作用域，全局单例）：
 * 每 5 秒并行对**所有**已配置 Daemon 做一次轻量 verify()（去主连接：不存在只盯一台）；
 * [ConnectivityUiState.online] 取「默认 Daemon」结果供主页首屏展示，
 * 若使用过程中在线→离线，发出 WentOffline 事件由全局弹窗提醒。
 */
class ConnectivityViewModel(application: Application) : AndroidViewModel(application) {

    private val container = getApplication<MSLXApplication>().container
    private val store = container.settingsStore

    private val _state = MutableStateFlow(ConnectivityUiState())
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<ConnectivityEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    private var wasOnline: Boolean? = null

    init {
        viewModelScope.launch {
            while (isActive) {
                try {
                    checkOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 单次检查失败绝不能让心跳循环终止
                    AppLogger.w("Connectivity", "连通性检查异常", e)
                }
                delay(5_000)
            }
        }
    }

    /** 立即执行一次连通性检查（也用于启动后的首查）。 */
    suspend fun checkOnce() {
        val settings = runCatching { store.settingsFlow.first() }
            .onFailure { AppLogger.w("Connectivity", "读取设置失败", it) }
            .getOrNull()
        if (settings == null || settings.daemons.isEmpty()) {
            _state.value = ConnectivityUiState()
            wasOnline = null
            return
        }
        // 注册表跟设置保持同步（configure 内部对「配置未变化」早退，不会每 5 秒重建 OkHttpClient）
        container.daemonRegistry.sync(settings)
        val results = settings.daemons.associate { daemon ->
            val repository = container.daemonRegistry.repositoryFor(daemon.id)
            val online = repository != null &&
                runCatching { repository.verify().getOrThrow(); true }.getOrDefault(false)
            AppLogger.d("Connectivity", "连通性检查 ${if (online) "在线" else "离线"} ${daemon.name.ifBlank { daemon.baseUrl }}")
            daemon.id to online
        }
        val online = results[settings.activeDaemonId]
        val previous = wasOnline
        if (previous == true && online == false) {
            AppLogger.w("Connectivity", "默认 Daemon 连接中断 ${settings.activeDaemon?.baseUrl.orEmpty()}")
            _events.tryEmit(ConnectivityEvent.WentOffline)
        }
        wasOnline = online
        _state.value = ConnectivityUiState(online = online, perDaemon = results)
    }
}
