package com.mslx.console.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.MSLXApplication
import com.mslx.console.data.AppLogger
import com.mslx.console.data.model.InstanceSummary
import com.mslx.console.data.model.NodeStatsPayload
import com.mslx.console.data.model.SystemInfo
import com.mslx.console.data.remote.ApiClient
import com.mslx.console.data.remote.SystemMonitorClient
import com.mslx.console.ui.ServerNotificationHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 开服/关服通知条目。 */
data class ServerNotification(
    val id: Long,
    val instanceName: String,
    val isOpened: Boolean, // true=开服(变为运行中), false=关服(离开运行中)
    val time: Long,
)

data class HomeUiState(
    // 连接
    val connecting: Boolean = true,
    val connected: Boolean = false,
    val daemonName: String = "",
    val baseUrl: String = "",
    val protocol: String = "",
    val systemInfo: SystemInfo? = null,
    val daemonVersion: String = "",
    // 实例
    val instances: List<InstanceSummary> = emptyList(),
    val error: String? = null,
    // 通知
    val notifications: List<ServerNotification> = emptyList(),
    // 一言金句
    val quote: String = "",
    val quoteSource: String = "",
    val quoteFailed: Boolean = false,
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val container = getApplication<MSLXApplication>().container
    private val repository = container.instanceRepository
    private val store = container.settingsStore

    private val _state = MutableStateFlow(HomeUiState())
    val state = _state.asStateFlow()

    /** 上次轮询到的实例状态快照（id -> status），用于检测开服/关服变化。 */
    private val lastStatus = mutableMapOf<Long, Int>()

    private var monitorClient: SystemMonitorClient? = null

    init {
        autoConnect()
        loadQuote()
        // 周期性刷新实例状态（负载走 SignalR 实时推送）
        viewModelScope.launch {
            while (isActive) {
                try {
                    if (_state.value.connected) {
                        refreshInstances()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 轮询体异常不能杀死循环：记录后继续下一轮
                    AppLogger.w("Home", "轮询实例状态异常", e)
                }
                delay(15_000)
            }
        }
    }

    /** 加载一言（Hitokoto）金句；失败时置 quoteFailed=true 由 UI 展示兜底文案，绝不崩溃。 */
    fun loadQuote() {
        viewModelScope.launch {
            runCatching {
                val q = ApiClient.buildHitokotoApi().quote()
                val text = q.hitokoto?.trim().orEmpty()
                if (text.isBlank()) throw IllegalStateException("一言返回为空")
                text to (q.fromWho?.takeIf { it.isNotBlank() } ?: q.from.orEmpty())
            }.onSuccess { (text, source) ->
                _state.update { it.copy(quote = text, quoteSource = source, quoteFailed = false) }
            }.onFailure { e ->
                AppLogger.w("Home", "一言加载失败", e)
                _state.update { it.copy(quote = "", quoteSource = "", quoteFailed = true) }
            }
        }
    }

    /** 自动连接已保存的激活 Daemon；没有配置则直接进入未连接状态。 */
    fun autoConnect() {
        viewModelScope.launch {
            val settings = runCatching { store.settingsFlow.first() }
                .onFailure { AppLogger.w("Home", "读取设置失败", it) }
                .getOrNull()
            val daemon = settings?.activeDaemon
            if (daemon == null) {
                _state.update { it.copy(connecting = false, connected = false) }
                return@launch
            }
            _state.update {
                it.copy(
                    connecting = true,
                    daemonName = daemon.name.ifBlank { daemon.baseUrl },
                    baseUrl = daemon.baseUrl,
                    protocol = protocolLabel(daemon.baseUrl),
                )
            }
            // 地址归一化：默认强制 HTTPS；该 Daemon 勾选"允许 HTTP"时保留明文地址
            val normalizedUrl = ApiClient.normalizeDaemonUrl(daemon.baseUrl, daemon.allowHttp)
            val result = runCatching {
                repository.configure(normalizedUrl, daemon.apiKey, daemon.allowHttp)
                repository.verify()
            }
            val connected = result.isSuccess
            if (connected) {
                // 若地址被规范化（如 http 升级 https），同步回写存储，避免下次仍用旧地址
                if (normalizedUrl != daemon.baseUrl) {
                    runCatching { store.upsertDaemon(daemon.copy(baseUrl = normalizedUrl)) }
                        .onFailure { AppLogger.w("Home", "回写 Daemon 地址失败", it) }
                }
                _state.update { it.copy(connecting = false, connected = true, error = null) }
                AppLogger.i("Home", "连接成功 $normalizedUrl")
                refreshMetrics()
                refreshInstances()
                startMonitor()
            } else {
                result.exceptionOrNull()?.let { AppLogger.w("Home", "连接失败 $normalizedUrl", it) }
                _state.update {
                    it.copy(
                        connecting = false,
                        connected = false,
                        error = "连接失败：无法连接守护进程，请检查地址、协议与 API Key",
                    )
                }
            }
        }
    }

    /** 重新连接（主页"重试"按钮）。 */
    fun retryConnect() = autoConnect()

    /** 启动 SignalR 系统负载监视（/api/hubs/system，2s 推送）。 */
    private fun startMonitor() {
        if (monitorClient != null) return
        val client = repository.createSystemMonitorClient(::onSystemStats)
        monitorClient = client
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching { client.connect() }.isSuccess
            if (!ok) {
                // 连接失败：释放引用，允许下次重连（如 daemon 重启后用户回到主页）
                monitorClient = null
            }
        }
    }

    /** 收到负载推送：memTotal/memUsed 单位为 MB，换算为 GB 展示。 */
    private fun onSystemStats(stats: NodeStatsPayload) {
        val base = _state.value.systemInfo ?: SystemInfo()
        _state.update {
            it.copy(
                systemInfo = base.copy(
                    cpuUsage = stats.cpu,
                    memoryUsage = stats.memUsage,
                    memoryUsed = stats.memUsed?.let { mb -> mb / 1024.0 },
                    memoryTotal = stats.memTotal?.let { mb -> mb / 1024.0 },
                ),
            )
        }
    }

    /** 刷新 Daemon 基础状态(版本/系统信息，不含负载)。 */
    fun refreshMetrics() {
        viewModelScope.launch {
            repository.getStatus().onSuccess { status ->
                _state.update {
                    it.copy(
                        systemInfo = (it.systemInfo ?: SystemInfo()).copy(
                            osType = status.systemInfo?.osType ?: it.systemInfo?.osType,
                            osArchitecture = status.systemInfo?.osArchitecture ?: it.systemInfo?.osArchitecture,
                        ),
                        daemonVersion = status.version.orEmpty(),
                    )
                }
            }
        }
    }

    /** 刷新实例列表并检测开服/关服变化。 */
    fun refreshInstances() {
        viewModelScope.launch {
            repository.listInstances().fold(
                onSuccess = { list ->
                    detectStatusChanges(list)
                    _state.update {
                        it.copy(
                            instances = list,
                            error = null,
                        )
                    }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(error = e.message ?: "加载失败")
                    }
                },
            )
        }
    }

    /** 对比上次状态快照，生成开服/关服通知（仅保留最近 50 条）并发送原生系统通知。 */
    private fun detectStatusChanges(current: List<InstanceSummary>) {
        val now = System.currentTimeMillis()
        val notifications = mutableListOf<ServerNotification>()
        for (instance in current) {
            val previous = lastStatus[instance.id]
            if (previous != null && previous != instance.status) {
                val becameRunning = instance.status == 2 && previous != 2
                val leftRunning = previous == 2 && instance.status != 2
                if (becameRunning || leftRunning) {
                    val item = ServerNotification(
                        id = instance.id,
                        instanceName = instance.name ?: "实例 #${instance.id}",
                        isOpened = becameRunning,
                        time = now,
                    )
                    notifications.add(item)
                    // 发送 Android 原生通知（点击跳转实例控制台）
                    runCatching {
                        ServerNotificationHelper.notifyServerStatus(
                            context = getApplication(),
                            instanceId = instance.id,
                            instanceName = item.instanceName,
                            isOpened = becameRunning,
                        )
                    }
                }
            }
            lastStatus[instance.id] = instance.status
        }
        if (notifications.isNotEmpty()) {
            _state.update {
                it.copy(notifications = (notifications + it.notifications).take(50))
            }
        }
    }

    fun clearNotifications() {
        _state.update { it.copy(notifications = emptyList()) }
    }

    private fun protocolLabel(baseUrl: String): String = when {
        baseUrl.startsWith("https://", ignoreCase = true) -> "HTTPS / WSS"
        baseUrl.startsWith("http://", ignoreCase = true) -> "HTTP / WS"
        else -> "未知"
    }

    override fun onCleared() {
        val client = monitorClient
        monitorClient = null
        if (client != null) {
            viewModelScope.launch(Dispatchers.IO) { client.disconnect() }
        }
        super.onCleared()
    }
}
