package com.mslx.console.ui.console

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.MSLXApplication
import com.mslx.console.data.AppLogger
import com.mslx.console.data.InstanceRepository
import com.mslx.console.data.ServerRef
import com.mslx.console.data.ensureRepository
import com.mslx.console.data.remote.ConsoleHubClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/** 已同意 EULA 的 eula.txt 内容（与守护进程 AgreeEULA 写入格式一致）。 */
private const val EULA_AGREED_CONTENT =
    "#By changing the setting below to TRUE you are indicating your agreement to our EULA (https://aka.ms/MinecraftEULA).\n#MSLX-Android auto agreed\neula=true\n"

/** 日志保留上限（超出后从头部裁剪）。 */
private const val MAX_LOG_LINES = 3000

/** 微批窗口：把窗口内到达的日志合并为一次 StateFlow 更新（降低重组与列表拷贝频率）。 */
private const val LOG_BATCH_WINDOW_MS = 40L

/** 单批最大行数：防止超长突发把单次合并放大到不可控。 */
private const val LOG_BATCH_MAX_LINES = 512

/** 日志行；[id] 供 UI 层 LazyColumn 用作稳定 item key（自动递增，不参与业务语义）。 */
data class LogLine(
    val text: String,
    val system: Boolean = false,
    val id: Long = idGenerator.incrementAndGet(),
) {
    companion object {
        private val idGenerator = AtomicLong()
    }
}

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
    /** 目标实例：[ServerRef.daemonId] 决定操作哪台服务端（去主连接）。 */
    private val ref: ServerRef,
) : AndroidViewModel(application), ConsoleController {

    private val container = getApplication<MSLXApplication>().container

    /**
     * 目标 Daemon 的连接。启动时 registry 通常已同步，构造即命中；
     * 未命中（进程重建后直接进入本页）时先退化为未配置仓储，由 init 里的
     * ensureRepository 补齐后替换（服务端真被删除则保持未配置，调用返回错误而不是崩溃）。
     */
    private var repository: InstanceRepository =
        container.repositoryFor(ref.daemonId) ?: InstanceRepository()

    private val instanceId: Long get() = ref.remoteIdOrNull ?: -1L

    private val _state = MutableStateFlow(ConsoleUiState(instanceName = "实例 #${ref.instanceId}"))
    override val state = _state.asStateFlow()

    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    override val logs = _logs.asStateFlow()

    private val _events = MutableSharedFlow<ConsoleEvent>(extraBufferCapacity = 16)
    override val events = _events.asSharedFlow()

    private var client: ConsoleHubClient? = null

    /** 日志入队通道（无界、非阻断）：SignalR 回调线程 trySend，不阻塞网络回调。 */
    private val incomingLogs = Channel<LogLine>(Channel.UNLIMITED)

    /** 已渲染日志的底层缓冲（仅日志协程访问），发布时拷贝快照给 StateFlow。 */
    private val renderBuffer = ArrayDeque<LogLine>()

    init {
        // 日志微批消费：攒批后一次性发布，取代逐行全量拷贝与重复重组
        viewModelScope.launch { drainLogs() }
        // 单一串行协程：先确保连接已解析，再加载、建连、轮询（避免轮询比首连更早解析仓储）
        viewModelScope.launch {
            container.ensureRepository(ref.daemonId)?.let { repository = it }
            loadInfo()
            connectHub()
            // 周期性刷新状态(运行时长、在线人数、启停状态)；顺带同步控制台连接实况
            // （断线自动重连期间 UI 显示“正在重连”，连上后错误提示自动消失）
            while (true) {
                try {
                    loadInfo()
                    syncConnectionState()
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
            onLog = { line -> enqueueLogs(listOf(LogLine(line))) },
            onCommandResult = { result ->
                if (!result.success) {
                    enqueueLogs(listOf(LogLine(">>> ${result.message ?: "命令发送失败"}", system = true)))
                }
            },
            onEulaRequired = { _events.tryEmit(ConsoleEvent.EulaRequired) },
        )
        client = hubClient
        try {
            withContext(Dispatchers.IO) { hubClient.connect() }
            _state.update { it.copy(connecting = false, connected = true, connectionError = null) }
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

    override fun retryConnect() {
        if (_state.value.connecting || _state.value.connected) return
        _state.update { it.copy(connecting = true, connectionError = null) }
        viewModelScope.launch { connectHub() }
    }

    override fun sendCommand(command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        enqueueLogs(listOf(LogLine("> $cmd")))
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { client?.sendCommand(cmd) }
            }
        }
    }

    override fun sendAction(action: String) {
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

    override fun agreeEulaAndStart() {
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

    override fun clearLogs() {
        renderBuffer.clear()
        _logs.value = emptyList()
    }

    /** 入队日志（非阻断；SignalR 回调线程可安全调用），由 [drainLogs] 微批后统一发布。 */
    private fun enqueueLogs(lines: List<LogLine>) {
        lines.forEach { incomingLogs.trySend(it) }
    }

    /**
     * 日志微批消费者：
     * 等到第一条日志后，在最多 [LOG_BATCH_WINDOW_MS]（或 [LOG_BATCH_MAX_LINES] 行）窗口内
     * 把陆续到达的日志攒成一批，一次性合并进 StateFlow，消除逐行追加时的
     * O(n) 全量拷贝与 3000 行封顶时的重复重组。
     */
    private suspend fun drainLogs() {
        while (currentCoroutineContext().isActive) {
            val first = incomingLogs.receiveCatching().getOrNull() ?: return
            val batch = ArrayList<LogLine>(64).apply { add(first) }
            val deadline = SystemClock.uptimeMillis() + LOG_BATCH_WINDOW_MS
            while (batch.size < LOG_BATCH_MAX_LINES) {
                val remain = deadline - SystemClock.uptimeMillis()
                if (remain <= 0) break
                val next = withTimeoutOrNull(remain) { incomingLogs.receiveCatching().getOrNull() } ?: break
                batch.add(next)
            }
            flushBatch(batch)
        }
    }

    /** 把一批日志合并进渲染缓冲并发布快照（缓冲只在日志协程内访问，无需加锁）。 */
    private fun flushBatch(batch: List<LogLine>) {
        renderBuffer.addAll(batch)
        while (renderBuffer.size > MAX_LOG_LINES) renderBuffer.removeFirst()
        _logs.value = renderBuffer.toList()
    }

    /** 用 Hub 的真实连接状态校正 UI：自动重连成功后错误提示自动消失。 */
    private fun syncConnectionState() {
        val live = client?.isConnected ?: return
        val current = _state.value
        if (current.connecting) return
        if (current.connected != live || (live && current.connectionError != null)) {
            _state.update {
                it.copy(
                    connected = live,
                    connectionError = if (live) null else it.connectionError,
                )
            }
        }
    }

    override fun onCleared() {
        incomingLogs.close()
        val hub = client
        client = null
        if (hub != null) {
            viewModelScope.launch(Dispatchers.IO) { hub.disconnect() }
        }
    }
}
