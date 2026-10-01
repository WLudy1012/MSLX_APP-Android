package com.wludy.rolithax.launcher.data.model

import com.google.gson.annotations.SerializedName

/**
 * 实例设置(对应后端 McServerInfo.ServerInfo / UpdateServerRequest)。
 * JSON 使用 camelCase 命名(ASP.NET Core 默认)。
 * 字段名与网页版 UpdateInstanceModel 保持一致。
 */
data class ServerSettings(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("name") val name: String = "",
    @SerializedName("base") val base: String = "",
    @SerializedName("java") val java: String = "",
    @SerializedName("core") val core: String = "",

    // 内存与启动参数
    @SerializedName("minM") val minM: Int? = 1024,
    @SerializedName("maxM") val maxM: Int? = 4096,
    @SerializedName("args") val args: String? = "",

    // 运行行为
    @SerializedName("forceExitDelay") val forceExitDelay: Int? = 10,
    @SerializedName("stopCommand") val stopCommand: String? = "stop",
    @SerializedName("yggdrasilApiAddr") val yggdrasilApiAddr: String? = "",
    @SerializedName("monitorPlayers") val monitorPlayers: Boolean? = true,
    @SerializedName("autoRestart") val autoRestart: Boolean? = false,
    @SerializedName("forceAutoRestart") val forceAutoRestart: Boolean? = true,
    @SerializedName("runOnStartup") val runOnStartup: Boolean? = false,
    @SerializedName("ignoreEula") val ignoreEula: Boolean? = false,
    @SerializedName("forceJvmUTF8") val forceJvmUTF8: Boolean? = false,
    @SerializedName("allowOriginASCIIColors") val allowOriginASCIIColors: Boolean? = true,

    // 编码
    @SerializedName("inputEncoding") val inputEncoding: String? = "utf-8",
    @SerializedName("outputEncoding") val outputEncoding: String? = "utf-8",
    @SerializedName("fileEncoding") val fileEncoding: String? = "utf-8",

    // 路径
    @SerializedName("serverPropertiesPath") val serverPropertiesPath: String? = "server.properties",
    @SerializedName("pluginsPath") val pluginsPath: String? = "plugins",
    @SerializedName("modsPath") val modsPath: String? = "mods",
    @SerializedName("worldPath") val worldPath: String? = "world",
    @SerializedName("regionPath") val regionPath: String? = "region",

    // 备份
    @SerializedName("backupMaxCount") val backupMaxCount: Int? = 20,
    @SerializedName("backupDelay") val backupDelay: Int? = 10,
    @SerializedName("backupPath") val backupPath: String? = "MSLX://Backup/Instance",

    // FRP
    @SerializedName("bindFrpId") val bindFrpId: String? = null,

    // 过期时间
    @SerializedName("expireTime") val expireTime: String? = null,

    // ====== Docker ======
    @SerializedName("dockerImage") val dockerImage: String? = "MSLX://DockerImage/Java/25",
    @SerializedName("dockerWorkingDir") val dockerWorkingDir: String? = "/mslx-data",
    @SerializedName("dockerVolumes") val dockerVolumes: String? = null,
    @SerializedName("dockerEnvVars") val dockerEnvVars: String? = null,
    @SerializedName("dockerNetworkMode") val dockerNetworkMode: String? = "bridge",
    @SerializedName("dockerNetworkAlias") val dockerNetworkAlias: String? = null,
    @SerializedName("dockerPorts") val dockerPorts: String? = "25565:25565",
    @SerializedName("dockerCpuPercentage") val dockerCpuPercentage: Int? = null,
    @SerializedName("dockerCpuCores") val dockerCpuCores: String? = null,
    @SerializedName("dockerMaxMemoryMb") val dockerMaxMemoryMb: Int? = null,
    @SerializedName("dockerMaxSwapMb") val dockerMaxSwapMb: Int? = null,
    @SerializedName("dockerMaxStorage") val dockerMaxStorage: String? = null,
    @SerializedName("dockerUploadRate") val dockerUploadRate: String? = null,
    @SerializedName("dockerDownloadRate") val dockerDownloadRate: String? = null,
    @SerializedName("dockerExtraArgs") val dockerExtraArgs: String? = null,
    @SerializedName("dockerExtraHosts") val dockerExtraHosts: String? = null,
)
