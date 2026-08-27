package com.mslx.console.ui.connect

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.MSLXApplication
import com.mslx.console.data.DaemonConfig
import com.mslx.console.data.remote.ApiClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ConnectUiState(
    val editingId: String? = null,
    val name: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val allowHttp: Boolean = false,
    val loading: Boolean = false,
    val autoChecking: Boolean = false,
    val error: String? = null,
)

class ConnectViewModel(
    application: Application,
    private val autoConnect: Boolean = true,
    private val editingDaemonId: String? = null,
) : AndroidViewModel(application) {

    private val container = getApplication<MSLXApplication>().container
    private val repository = container.instanceRepository
    private val store = container.settingsStore

    private val _state = MutableStateFlow(ConnectUiState())
    val state = _state.asStateFlow()

    private val _connected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val connected = _connected.asSharedFlow()

    /** 启动自动连接失败事件（携带错误信息），由界面回退主页并弹窗提示。 */
    private val _autoConnectFailed = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val autoConnectFailed = _autoConnectFailed.asSharedFlow()

    init {
        if (autoConnect || editingDaemonId != null) {
            viewModelScope.launch {
                val settings = runCatching { store.settingsFlow.first() }
                    .onFailure { com.mslx.console.data.AppLogger.w("Connect", "读取设置失败", it) }
                    .getOrNull()
                val target = settings?.daemons?.firstOrNull { it.id == editingDaemonId }
                    ?: settings?.activeDaemon
                if (target != null) {
                    _state.update {
                        it.copy(
                            editingId = target.id,
                            name = target.name,
                            baseUrl = target.baseUrl,
                            apiKey = target.apiKey,
                            allowHttp = target.allowHttp,
                        )
                    }
                    // 已有激活的 Daemon → 自动连接
                    if (autoConnect) doConnect(target, auto = true)
                }
            }
        }
    }

    fun onNameChange(value: String) = _state.update { it.copy(name = value, error = null) }
    fun onBaseUrlChange(value: String) = _state.update { it.copy(baseUrl = value, error = null) }
    fun onApiKeyChange(value: String) = _state.update { it.copy(apiKey = value, error = null) }
    fun onAllowHttpChange(value: Boolean) = _state.update { it.copy(allowHttp = value, error = null) }

    fun connect() {
        val s = _state.value
        val baseUrl = normalizeBaseUrl(s.baseUrl, s.allowHttp)
        val apiKey = s.apiKey.trim()
        if (baseUrl.isBlank() || apiKey.isBlank()) {
            _state.update { it.copy(error = "请填写完整的 Daemon 地址和 API Key。") }
            return
        }
        val name = s.name.trim().ifBlank { baseUrl }
        val config = DaemonConfig(
            id = s.editingId ?: UUID.randomUUID().toString(),
            name = name,
            baseUrl = baseUrl,
            apiKey = apiKey,
            allowHttp = s.allowHttp,
        )
        doConnect(config)
    }

    /**
     * 规范化 Daemon 地址：默认强制 HTTPS（http 自动升级）；用户勾选"允许 HTTP"后保留明文地址。
     */
    private fun normalizeBaseUrl(input: String, allowHttp: Boolean): String =
        ApiClient.normalizeDaemonUrl(input, allowHttp)

    private fun doConnect(config: DaemonConfig, auto: Boolean = false) {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, autoChecking = auto, error = null) }
        viewModelScope.launch {
            val settings = runCatching { store.settingsFlow.first() }
                .onFailure { com.mslx.console.data.AppLogger.w("Connect", "读取设置失败", it) }
                .getOrNull()
            val duplicate = settings?.daemons?.any {
                it.id != config.id &&
                    ApiClient.normalizeDaemonUrl(it.baseUrl, it.allowHttp).equals(
                        ApiClient.normalizeDaemonUrl(config.baseUrl, config.allowHttp),
                        ignoreCase = true,
                    ) &&
                    it.apiKey == config.apiKey
            } == true
            if (duplicate) {
                _state.update { it.copy(loading = false, autoChecking = false, error = "同一 API Key 已连接此 Daemon，不能重复添加。") }
                return@launch
            }
            // 地址已由 normalize 规范化（默认升级 https，勾选允许 HTTP 后保留明文）
            val result = runCatching {
                repository.configure(config.baseUrl, config.apiKey, config.allowHttp)
                repository.verify()
            }
            if (result.isSuccess) {
                // 持久化失败不应阻断本次连接（内存中已生效），仅记录日志
                runCatching { store.upsertDaemon(config) }
                    .onFailure { com.mslx.console.data.AppLogger.w("Connect", "保存 Daemon 配置失败", it) }
                _state.update { it.copy(loading = false, autoChecking = false) }
                _connected.tryEmit(Unit)
            } else {
                val message = "连接失败：${result.exceptionOrNull()?.message ?: "未知错误"}"
                _state.update {
                    it.copy(loading = false, autoChecking = false, error = message)
                }
                // 启动自动连接失败：通知界面回退主页并弹窗，而不是停留在连接页卡住
                if (auto) {
                    _autoConnectFailed.tryEmit(message)
                }
            }
        }
    }
}
