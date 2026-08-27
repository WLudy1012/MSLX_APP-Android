package com.mslx.console.ui.console

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.MSLXApplication
import com.mslx.console.data.AppLogger
import com.mslx.console.data.remote.ConsoleHubClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 已同意 EULA 的 eula.txt 内容（与守护进程 AgreeEULA 写入格式一致）。 */
private const val EULA_AGREED_CONTENT =
    "#By changing the setting below to TRUE you are indicating your agreement to our EULA (https://aka.ms/MinecraftEULA).\n#MSLX-Android auto agreed\neula=true\n"

data class LogLine(val text: String, val system: Boolean = false)

sealed interface ConsoleEvent {
    data class Toast(val message: String) : ConsoleEvent
    data object EulaRequired : ConsoleEvent
}

data class ConsoleUiState(
    val instanceName: String = "",
    val status: Int = 0,
    val statusText: String? = null,
    val onlinePlayers: Int = 0,
    val uptime: String? = null,
    val connecting: Boolean = true,
    val connected: Boolean = false,
    val connectionError: String? = null,
    val busy: Boolean = false,
)

class ConsoleViewModel(
    application: Application,
    private val instanceId: Long,
) : AndroidViewModel(application) {

    private val repository = getApplication<MSLXApplication>().container.instanceRepository

    private val _state = MutableStateFlow(ConsoleUiState(instanceName = "实例 #$instanceId"))
    val state = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _events = MutableSharedFlow<ConsoleEvent>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    private var client: ConsoleHubClient? = null

    init {
        viewModelScope.launch {
            loadInfo()
            connectHub()
        }
        // 周期性刷新状态(运行时长、在线人数、启停状态)
        viewModelScope.launch {
            while (true) {
                try {
                    loadInfo()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 网络超时/解析异常等绝不能让轮询协程死亡：记录后继续下一轮
                    AppLogger.w("Console", "轮询实例信息异常", e)
                }
                delay(15_000)
            }
        }
    }

    private suspend fun loadInfo() {
        repository.instanceInfo(instanceId).onSuccess { info ->
            _state.update {
                it.copy(
                    instanceName = info.name ?: it.instanceName,
                    status = info.status,
                    statusText = info.statusText,
                    onlinePlayers = info.onlinePlayers,
                    uptime = info.uptime?.let(::formatUptime),
                )
            }
        }
    }

    /**
     * 把守护进程返回的 TimeSpan 字符串（如 `00:12:34.5678901` / `1.02:03:04.5`）
     * 截断到秒（保留天）：`00:12:34` / `1.02:03:04`。无小数部分时原样返回。
     */
    private fun formatUptime(raw: String): String {
        val t = raw.trim()
        return if (t.contains('.')) t.substringBefore('.') else t
    }

    private suspend fun connectHub() {
        val hubClient = repository.createConsoleClient(
            instanceId = instanceId,
            onLog = { line -> appendLogs(listOf(LogLine(line))) },
            onCommandResult = { result ->
                if (!result.success) {
                    appendLogs(listOf(LogLine(">>> ${result.message ?: "命令发送失败"}", system = true)))
                }
            },
            onEulaRequired = { _events.tryEmit(ConsoleEvent.EulaRequired) },
        )
        client = hubClient
        try {
            withContext(Dispatchers.IO) { hubClient.connect() }
            _state.update { it.copy(connecting = false, connected = true) }
        } catch (e: Exception) {
            _state.update {
                it.copy(connecting = false, connected = false, connectionError = formatConnectionError(e))
            }
        }
    }

    /**
     * 将控制台 SignalR 连接异常格式化为面向用户的提示：
     * 异常链包含 websocket / negotiate / transport（大小写不敏感）时，
     * 判定为 WebSocket 协商失败并给出额外排查指引；否则按普通连接失败处理。
     */
    private fun formatConnectionError(e: Exception): String {
        val chainMessages = generateSequence<Throwable>(e) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
        val isWebSocketIssue = listOf("websocket", "negotiate", "transport")
            .any { chainMessages.contains(it, ignoreCase = true) }
        val base = e.message ?: "未知错误"
        return if (isWebSocketIssue) {
            "WebSocket 协商失败：$base 请确认 Daemon 已启用 WebSocket，并检查 HTTPS/反向代理配置。"
        } else {
            "控制台连接失败：$base"
        }
    }

    fun retryConnect() {
        if (_state.value.connecting || _state.value.connected) return
        _state.update { it.copy(connecting = true, connectionError = null) }
        viewModelScope.launch { connectHub() }
    }

    fun sendCommand(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        appendLogs(listOf(LogLine("> $cmd")))
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { client?.sendCommand(cmd) }
            }
        }
    }

    fun sendAction(action: String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            // 启动前：若实例设置了"忽略/自动同意 EULA"，先写入 eula.txt，
            // 否则 vanilla 等服务端仍会因 eula.txt 未同意而拒绝启动
            if (action == "start") ensureEulaAgreed()
            val result = repository.sendAction(instanceId, action)
            if (result.isSuccess) {
                _events.tryEmit(ConsoleEvent.Toast(result.getOrNull() ?: "操作成功"))
                loadInfo()
            } else {
                _events.tryEmit(ConsoleEvent.Toast(result.exceptionOrNull()?.message ?: "操作失败"))
            }
            _state.update { it.copy(busy = false) }
        }
    }

    /** 若实例 ignoreEula=true，则把 eula.txt 写入已同意内容（失败静默，由后续启动流程兜底）。 */
    private suspend fun ensureEulaAgreed() {
        val settings = repository.getSettings(instanceId).getOrNull() ?: return
        if (settings.ignoreEula != true) return
        val propsPath = settings.serverPropertiesPath?.trim().orEmpty()
            .ifBlank { "server.properties" }
        val dir = propsPath.substringBeforeLast('/', "")
        val eulaPath = if (dir.isBlank()) "eula.txt" else "$dir/eula.txt"
        runCatching { repository.saveFileContent(instanceId, eulaPath, EULA_AGREED_CONTENT) }
    }

    fun agreeEulaAndStart() {
        viewModelScope.launch {
            // 先尝试让守护进程记录 EULA 同意（失败不阻断，随后 start 本身会给出结果）
            repository.sendAction(instanceId, "agreeEula?true")
                .onFailure { AppLogger.w("Console", "同意 EULA 失败", it) }
            val startResult = repository.sendAction(instanceId, "start")
            if (startResult.isSuccess) {
                _events.tryEmit(ConsoleEvent.Toast(startResult.getOrNull() ?: "启动成功"))
            } else {
                _events.tryEmit(ConsoleEvent.Toast(startResult.exceptionOrNull()?.message ?: "启动失败"))
            }
            loadInfo()
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    private fun appendLogs(new: List<LogLine>) {
        // 用原子 update 保证 SignalR 回调线程并发追加时不丢日志
        _logs.update { current ->
            val merged = current + new
            if (merged.size > 3000) merged.takeLast(3000) else merged
        }
    }

    override fun onCleared() {
        val hub = client
        client = null
        if (hub != null) {
            viewModelScope.launch(Dispatchers.IO) { hub.disconnect() }
        }
    }
}
