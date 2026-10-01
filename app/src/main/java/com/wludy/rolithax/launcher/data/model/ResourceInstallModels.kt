package com.wludy.rolithax.launcher.data.model

import com.google.gson.annotations.SerializedName

data class ResourceInstallBatchRequest(
    @SerializedName("instanceId") val instanceId: Long,
    @SerializedName("gameVersion") val gameVersion: String,
    @SerializedName("loader") val loader: String,
    @SerializedName("items") val items: List<ResourceInstallItemRequest>,
)

data class ResourceInstallItemRequest(
    @SerializedName("projectId") val projectId: String,
    @SerializedName("versionId") val versionId: String,
    @SerializedName("type") val type: String,
    @SerializedName("provider") val providerId: String = "modrinth",
)

data class ResourceInstallTaskData(
    @SerializedName("id") val id: String,
    @SerializedName("status") val status: String,
    @SerializedName("progress") val progress: Int,
    @SerializedName("message") val message: String,
    @SerializedName("installedFiles") val installedFiles: List<String> = emptyList(),
)
