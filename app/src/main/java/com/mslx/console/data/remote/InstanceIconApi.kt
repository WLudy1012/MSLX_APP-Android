package com.mslx.console.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * 第三方服务器状态 API（公开接口，无需认证）。
 *
 * 图标三级来源的最后一级：当 Daemon 未安装图标插件时，App 直接查询这两个服务，
 * 仅使用 `online` 与 `icon`（`data:image/png;base64,...`）两个字段，
 * 且只对实例在 server.properties 中显式配置的公网地址发起。
 */
data class ServerStatusIcon(
    @SerializedName("online") val online: Boolean = false,
    @SerializedName("icon") val icon: String? = null,
)

/** mcsrvstat.us API（`https://api.mcsrvstat.us/3/{address}`，首选）。 */
interface McsrvstatApi {

    @GET("3/{address}")
    suspend fun status(@Path("address") address: String): ServerStatusIcon
}

/** mcstatus.io API（`https://api.mcstatus.io/v2/status/java/{address}`，mcsrvstat.us 无结果时回退）。 */
interface McstatusApi {

    @GET("v2/status/java/{address}")
    suspend fun status(@Path("address") address: String): ServerStatusIcon
}
