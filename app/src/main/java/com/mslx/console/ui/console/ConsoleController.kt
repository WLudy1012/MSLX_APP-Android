package com.mslx.console.ui.console

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 控制台控制器抽象：屏蔽「Daemon 实例（SignalR 控制台）」与「本机实例（进程内 / Shizuku 引擎）」
 * 的差异，让 [ConsoleScreen] 只面向统一接口渲染日志、发送命令与启停操作。
 *
 * 两个实现：
 *  - [ConsoleViewModel]：既有的 Daemon SignalR 控制台。
 *  - [LocalConsoleViewModel]：绑定 [com.mslx.console.data.localengine.LocalServerManager] 的本机控制台。
 */
interface ConsoleController {

    /** 控制台状态（名称 / 运行状态码 / 在线人数 / 连接态 / 忙）。 */
    val state: StateFlow<ConsoleUiState>

    /** 日志行（终端渲染）。 */
    val logs: StateFlow<List<LogLine>>

    /** 一次性事件（Toast / EULA 弹窗）。 */
    val events: SharedFlow<ConsoleEvent>

    /** 发送一条控制台命令。 */
    fun sendCommand(command: String)

    /** 发送一个操作动作：start / stop / restart / forceExit / backup。 */
    fun sendAction(action: String)

    /** 重连（Daemon 控制台有效；本机为无网络语义的空实现）。 */
    fun retryConnect()

    /** 同意 EULA 并启动。 */
    fun agreeEulaAndStart()

    /** 清空本地渲染的日志。 */
    fun clearLogs()
}
