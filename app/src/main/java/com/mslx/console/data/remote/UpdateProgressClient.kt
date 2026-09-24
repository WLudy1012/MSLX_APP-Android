package com.mslx.console.data.remote

import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder

/**
 * 监听实例设置更新中的 Java/Core 下载进度 (/api/hubs/updateProgressHub)。
 *
 * 断线自动重连由 [ReconnectingHubClient] 提供（重连成功自动重新 JoinGroup）。
 */
class UpdateProgressClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val instanceId: Long,
    private val onStatus: (message: String, progress: Double, isError: Boolean) -> Unit,
) : ReconnectingHubClient("UpdateHub") {

    override fun buildConnection(): HubConnection {
        val connection = HubConnectionBuilder
            .create("${baseUrl.trimEnd('/')}/api/hubs/updateProgressHub")
            .withHeader("x-api-key", apiKey)
            .setHttpClientBuilderCallback { builder -> ApiClient.configureDaemonHttpClient(builder) }
            .build()
        connection.on(
            "UpdateStatus",
            { message: String, progress: Double, isError: Boolean ->
                onStatus(message, progress, isError)
            },
            String::class.java,
            Double::class.java,
            Boolean::class.java,
        )
        return connection
    }

    override fun onOpened(connection: HubConnection) {
        connection.send("JoinGroup", instanceId.toString())
    }

    override fun onDisconnect(connection: HubConnection) {
        connection.send("LeaveGroup", instanceId.toString())
    }
}
