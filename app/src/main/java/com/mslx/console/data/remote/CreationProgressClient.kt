package com.mslx.console.data.remote

import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder

/**
 * 监听实例创建进度 (/api/hubs/creationProgressHub)。
 * 服务端约定：
 *  - 客户端调用：TrackServer(serverId)、UnTrackServer(serverId)
 *  - 服务端推送：StatusUpdate(serverId, message, progress)
 *
 * 断线自动重连由 [ReconnectingHubClient] 提供（重连成功自动重新 TrackServer）。
 */
class CreationProgressClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val serverId: String,
    private val onStatus: (id: String, message: String, progress: Double) -> Unit,
) : ReconnectingHubClient("CreationHub") {

    override fun buildConnection(): HubConnection {
        val connection = HubConnectionBuilder
            .create("${baseUrl.trimEnd('/')}/api/hubs/creationProgressHub")
            .withHeader("x-api-key", apiKey)
            .setHttpClientBuilderCallback { builder -> ApiClient.configureDaemonHttpClient(builder) }
            .build()
        connection.on(
            "StatusUpdate",
            { id: String, message: String, progress: Double ->
                onStatus(id, message, progress)
            },
            String::class.java,
            String::class.java,
            Double::class.java,
        )
        return connection
    }

    override fun onOpened(connection: HubConnection) {
        connection.send("TrackServer", serverId)
    }

    override fun onDisconnect(connection: HubConnection) {
        connection.send("UnTrackServer", serverId)
    }
}
