package com.mslx.console.data.model

import com.google.gson.annotations.SerializedName

/**
 * POST /api/instance/createServer 请求体，字段与网页版 CreateInstanceQucikModeModel /
 * 后端 CreateServerRequest 保持一致(camelCase)。
 */
data class CreateServerRequest(
    @SerializedName("name") val name: String,
    @SerializedName("core") val core: String,
    @SerializedName("minM") val minM: Int,
    @SerializedName("maxM") val maxM: Int,
    @SerializedName("java") val java: String? = null,
    @SerializedName("args") val args: String? = null,
    @SerializedName("ignoreEula") val ignoreEula: Boolean = false,
    @SerializedName("path") val path: String? = null,
    @SerializedName("dockerImage") val dockerImage: String? = "MSLX://DockerImage/Java/25",
    @SerializedName("dockerPorts") val dockerPorts: String? = "25565:25565",
    @SerializedName("mcdr") val mcdr: Boolean = false,
    @SerializedName("mcdrPython") val mcdrPython: String? = null,
    @SerializedName("mcdrHandler") val mcdrHandler: String? = null,
    @SerializedName("mcdrInstall") val mcdrInstall: Boolean = true,
    @SerializedName("mcdrPipMirror") val mcdrPipMirror: String? = null,
    @SerializedName("coreUrl") val coreUrl: String? = null,
    @SerializedName("coreSha256") val coreSha256: String? = null,
    @SerializedName("coreFileKey") val coreFileKey: String? = null,
    @SerializedName("packageUrl") val packageUrl: String? = null,
    @SerializedName("packageSha256") val packageSha256: String? = null,
    @SerializedName("packageLocalPath") val packageLocalPath: String? = null,
    @SerializedName("packageFileKey") val packageFileKey: String? = null,
)

/** createServer 响应 data。 */
data class CreateServerData(
    @SerializedName("serverId") val serverId: String = "",
)

/** POST /api/instance/cancelCreation 请求体。 */
data class CancelCreationRequest(
    @SerializedName("serverId") val serverId: String,
    @SerializedName("cleanupFiles") val cleanupFiles: Boolean = false,
)

/** MSLAPI v4 /mirrors 分类数据。 */
data class ServerCoreClassify(
    @SerializedName("pluginsCore") val pluginsCore: List<String> = emptyList(),
    @SerializedName("pluginsAndModsCore_Forge") val pluginsAndModsCoreForge: List<String> = emptyList(),
    @SerializedName("pluginsAndModsCore_Fabric") val pluginsAndModsCoreFabric: List<String> = emptyList(),
    @SerializedName("modsCore_Forge") val modsCoreForge: List<String> = emptyList(),
    @SerializedName("modsCore_Fabric") val modsCoreFabric: List<String> = emptyList(),
    @SerializedName("vanillaCore") val vanillaCore: List<String> = emptyList(),
    @SerializedName("bedrockCore") val bedrockCore: List<String> = emptyList(),
    @SerializedName("proxyCore") val proxyCore: List<String> = emptyList(),
)

/** MSLAPI v4 /mirrors/{name} 版本列表。 */
data class ServerCoreGameVersion(
    @SerializedName("versions") val versions: List<String> = emptyList(),
    @SerializedName("description") val description: String? = null,
)

/** MSLAPI v4 /download/server/{name}/{version} 下载信息。 */
data class ServerCoreDownloadInfo(
    @SerializedName("url") val url: String = "",
    @SerializedName("sha256") val sha256: String? = null,
)
