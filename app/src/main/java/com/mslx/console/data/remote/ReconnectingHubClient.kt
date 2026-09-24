package com.mslx.console.data.remote

import com.microsoft.signalr.HubConnection
import com.mslx.console.data.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * SignalR Hub 客户端基类：连接生命周期 + 断线自动重连。
 *
 * 为什么自实现重连：SignalR 的自动重连（withAutomaticReconnect / onReconnecting /
 * onReconnected）只有 .NET 与 JS 客户端有。Java 客户端 8.0.8 已核对源码：
 * HubConnection 仅提供 onClosed 回调，HttpHubConnectionBuilder 也没有重连开关，
 * 因此断线后连接不会自愈，必须在这里补上等价能力。
 *
 * 重连语义：连接因网络/服务端原因关闭（非用户主动 [disconnect]）后，按退避序列
 * 1s → 2s → 5s → 10s → 30s（其后固定 30s 封顶）自动重建；重建成功后重新执行入组
 * [onOpened]——服务端分组是连接级状态，重连得到新 connectionId 后原分组已丢失。
 *
 * 线程约定：connect() / disconnect() 为阻塞网络操作（内部串行化，可安全并发调用），
 * 须在 IO 线程调用；[onOpened] / [onClosed] / [onDisconnect] 在连接所在线程执行。
 */
abstract class ReconnectingHubClient(private val tag: String) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectLock = Any()

    @Volatile
    protected var hub: HubConnection? = null

    /** 真实连接状态：由连接生命周期维护（不再是「connection != null」的近似语义）。 */
    @Volatile
    var isConnected: Boolean = false
        private set

    /** 用户主动 disconnect() 后置位，阻止自动重连。 */
    @Volatile
    private var stopped = false

    @Volatile
    private var reconnectJob: Job? = null

    /** 构建 HubConnection 并注册业务回调（不得调用 start）。 */
    protected abstract fun buildConnection(): HubConnection

    /** start 成功后调用：首次连接与每次重连后都会执行（用于 JoinGroup/TrackServer 等入组）。 */
    protected open fun onOpened(connection: HubConnection) {}

    /** 主动断开前调用：与 [onOpened] 对称的退组操作（LeaveGroup 等），异常由基类兜底。 */
    protected open fun onDisconnect(connection: HubConnection) {}

    /** 连接关闭（含用户主动断开）时通知业务侧；自动重连由基类负责，子类无需处理。 */
    protected open fun onClosed(cause: Exception?) {}

    /** 阻塞建连；失败抛出异常，由调用方决定提示方式；成功后连接由基类维持自愈。 */
    fun connect() {
        stopped = false
        synchronized(connectLock) {
            ensureConnectedLocked()
        }
    }

    /** 主动断开并停止自动重连。 */
    fun disconnect() {
        stopped = true
        reconnectJob?.cancel()
        reconnectJob = null
        val connection: HubConnection?
        synchronized(connectLock) {
            connection = hub
            hub = null
            isConnected = false
        }
        if (connection == null) return
        try {
            runCatching { onDisconnect(connection) }
            connection.stop().blockingAwait()
        } catch (_: Exception) {
            // 断开阶段忽略异常，避免影响界面退出
        }
    }

    /** 在持有 [connectLock] 的前提下建连（含旧连接清理）。 */
    private fun ensureConnectedLocked() {
        if (isConnected) return
        val previous = hub
        hub = null
        if (previous != null) runCatching { previous.stop().blockingAwait() }
        val connection = buildConnection()
        // onClosed 必须在 start 之前注册（Java 客户端只允许 Disconnected 状态下注册处理器）
        connection.onClosed { cause: Exception? ->
            isConnected = false
            onClosed(cause)
            scheduleReconnect()
        }
        connection.start().blockingAwait()
        hub = connection
        isConnected = true
        AppLogger.i(tag, "SignalR 连接建立")
        onOpened(connection)
    }

    /** 连接意外关闭后按退避序列重连；用户主动 disconnect 则不重连。 */
    private fun scheduleReconnect() {
        if (stopped || isConnected) return
        synchronized(connectLock) {
            if (stopped || isConnected) return
            if (reconnectJob?.isActive == true) return
            reconnectJob = scope.launch {
                var attempt = 0
                while (isActive && !stopped && !isConnected) {
                    attempt++
                    delay(RECONNECT_DELAYS_MS[(attempt - 1).coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)])
                    if (!isActive || stopped || isConnected) return@launch
                    val result = runCatching {
                        synchronized(connectLock) { ensureConnectedLocked() }
                    }
                    if (result.isSuccess) {
                        AppLogger.i(tag, "自动重连成功（第 $attempt 次尝试）")
                        return@launch
                    }
                    AppLogger.w(tag, "自动重连第 $attempt 次失败", result.exceptionOrNull())
                }
            }
        }
    }

    private companion object {
        /** 退避序列：前几次快速试探，之后 30s 封顶（长期离线时不再高频重试）。 */
        val RECONNECT_DELAYS_MS = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)
    }
}
