package com.mslx.console.data.model

import com.google.gson.annotations.SerializedName

/**
 * MSLX Daemon 统一响应结构。
 * 后端约定：{ "code": 200, "message": "...", "data": ... }
 */
data class ApiResponse<T>(
    @SerializedName("code") val code: Int = 0,
    @SerializedName("message") val message: String? = null,
    @SerializedName("data") val data: T? = null,
)

data class UpdateSettingsData(
    @SerializedName("needListen") val needListen: Boolean = false,
)

/** GET /api/status 的 data 字段(部分字段)。 */
data class StatusData(
    @SerializedName("clientName") val clientName: String? = null,
    @SerializedName("version") val version: String? = null,
    @SerializedName("user") val user: String? = null,
    @SerializedName("username") val username: String? = null,
    @SerializedName("systemInfo") val systemInfo: SystemInfo? = null,
)

/** 守护程序所在主机系统信息。 */
data class SystemInfo(
    @SerializedName("osType") val osType: String? = null,
    @SerializedName("osArchitecture") val osArchitecture: String? = null,
    @SerializedName("cpuUsage") val cpuUsage: Double? = null,
    @SerializedName("memoryUsage") val memoryUsage: Double? = null,
    @SerializedName("memoryUsed") val memoryUsed: Double? = null,
    @SerializedName("memoryTotal") val memoryTotal: Double? = null,
)

/**
 * SignalR /api/hubs/system 的 ReceiveSystemStats 节点负载(本地节点)。
 * 单位约定：cpu/memUsage 为百分比(0-100)，memTotal/memUsed 为 MB。
 */
data class NodeStatsPayload(
    @SerializedName("cpu") val cpu: Double? = null,
    @SerializedName("memTotal") val memTotal: Double? = null,
    @SerializedName("memUsed") val memUsed: Double? = null,
    @SerializedName("memUsage") val memUsage: Double? = null,
    @SerializedName("timestamp") val timestamp: String? = null,
)

/** SignalR ReceiveSystemStats 的完整载荷：{ local: NodeStatsPayload, slaves: {...} } */
data class SystemStatsEnvelope(
    @SerializedName("local") val local: NodeStatsPayload? = null,
)

/** GET /api/java/list 的 data 数组元素(本地 Java 环境)。 */
data class LocalJava(
    @SerializedName("path") val path: String = "",
    @SerializedName("home") val home: String? = null,
    @SerializedName("version") val version: String = "",
    @SerializedName("vendor") val vendor: String? = null,
    @SerializedName("is64Bit") val is64Bit: Boolean = false,
)

/** GET /api/user/me 的 data 字段(当前登录用户信息)。 */
data class UserInfo(
    @SerializedName("id") val id: String? = null,
    @SerializedName("username") val username: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("avatar") val avatar: String? = null,
    @SerializedName("role") val role: String? = null,
    @SerializedName("apiKey") val apiKey: String? = null,
    @SerializedName("lastLoginTime") val lastLoginTime: String? = null,
    @SerializedName("resources") val resources: List<String> = emptyList(),
    @SerializedName("openMSLID") val openMSLID: String? = null,
)

data class FrpSummary(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("name") val name: String? = null,
    @SerializedName("service") val service: String? = null,
)

data class UpdateSelfRequest(
    @SerializedName("username") val username: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("avatar") val avatar: String? = null,
    @SerializedName("password") val password: String? = null,
    @SerializedName("resetApiKey") val resetApiKey: Boolean = false,
    @SerializedName("resources") val resources: List<String>? = null,
)

data class AdminCreateUserRequest(
    @SerializedName("username") val username: String,
    @SerializedName("password") val password: String,
    @SerializedName("name") val name: String? = null,
    @SerializedName("role") val role: String = "user",
    @SerializedName("resources") val resources: List<String> = emptyList(),
)

data class AdminUpdateUserRequest(
    @SerializedName("name") val name: String? = null,
    @SerializedName("avatar") val avatar: String? = null,
    @SerializedName("password") val password: String? = null,
    @SerializedName("role") val role: String? = null,
    @SerializedName("resetApiKey") val resetApiKey: Boolean = false,
    @SerializedName("resources") val resources: List<String>? = null,
)

/** 实例列表项中的额外信息。 */
data class InstanceExtra(
    @SerializedName("onlinePlayers") val onlinePlayers: Int = 0,
)

/** GET /api/instance/list 的 data 数组元素。 */
data class InstanceSummary(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String? = null,
    @SerializedName("basePath") val basePath: String? = null,
    @SerializedName("java") val java: String? = null,
    @SerializedName("core") val core: String? = null,
    @SerializedName("icon") val icon: String? = null,
    @SerializedName("status") val status: Int = 0,
    @SerializedName("statusText") val statusText: String? = null,
    @SerializedName("expireTime") val expireTime: String? = null,
    @SerializedName("extra") val extra: InstanceExtra? = null,
)

/** GET /api/instance/info 的 data 字段(部分字段)。 */
data class InstanceInfo(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String? = null,
    @SerializedName("status") val status: Int = 0,
    @SerializedName("statusText") val statusText: String? = null,
    @SerializedName("uptime") val uptime: String? = null,
    @SerializedName("onlinePlayers") val onlinePlayers: Int = 0,
    @SerializedName("java") val java: String? = null,
    @SerializedName("core") val core: String? = null,
)

/** POST /api/instance/action 的请求体。 */
data class ActionRequest(
    @SerializedName("id") val id: Long,
    @SerializedName("action") val action: String,
)

/** SignalR CommandResult 事件负载：{ "success": bool, "message": "..." } */
data class CommandResultPayload(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("message") val message: String? = null,
)

/** GET /api/files/instance/{id}/lists 的 data 数组元素(文件/目录项)。 */
data class FileItem(
    @SerializedName("name") val name: String = "",
    @SerializedName("type") val type: String = "file",
    @SerializedName("size") val size: Long = 0,
    @SerializedName("lastModified") val lastModified: String? = null,
    @SerializedName("permission") val permission: String = "",
) {
    val isFolder: Boolean get() = type == "folder"
}
