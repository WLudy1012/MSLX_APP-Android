package com.mslx.console.data.remote

import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.mslx.console.data.model.CommandResultPayload

/**
 * 封装 /api/hubs/instanceControlHub 的 SignalR 连接。
 *
 * 服务端约定：
 *  - 客户端调用：JoinGroup(instanceId)、LeaveGroup(instanceId)、SendCommand(instanceId, command)
 *  - 服务端推送：ReceiveLog(string)、CommandResult({success,message})、RequireEULA()
 *
 * 断线自动重连由 [ReconnectingHubClient] 提供（重连成功自动重新 JoinGroup）。
 * 注意：连接、断开均为阻塞网络操作，务必在 IO 线程调用。
 */
class ConsoleHubClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val instanceId: Long,
    private val onLog: (String) -> Unit,
    private val onCommandResult: (CommandResultPayload) -> Unit,
    private val onEulaRequired: () -> Unit,
) : ReconnectingHubClient("ConsoleHub") {

    override fun buildConnection(): HubConnection {
        val url = "${baseUrl.trimEnd('/')}/api/hubs/instanceControlHub"
        val connection = HubConnectionBuilder.create(url)
            .withHeader("x-api-key", apiKey)
            .setHttpClientBuilderCallback { builder -> ApiClient.configureDaemonHttpClient(builder) }
            .build()

        connection.on("ReceiveLog", { log: String -> onLog(log) }, String::class.java)
        connection.on(
            "CommandResult",
            { result: CommandResultPayload -> onCommandResult(result) },
            CommandResultPayload::class.java,
        )
        connection.on("RequireEULA", { onEulaRequired() })
        return connection
    }

    override fun onOpened(connection: HubConnection) {
        connection.send("JoinGroup", instanceId)
    }

    override fun onDisconnect(connection: HubConnection) {
        connection.send("LeaveGroup", instanceId)
    }

    fun sendCommand(command: String) {
        hub?.send("SendCommand", instanceId, command)
    }
}
