package com.wludy.rolithax.launcher.data.model

import com.google.gson.annotations.SerializedName

/** 插件/模组列表响应。 */
data class PmListData(
    @SerializedName("totalCount") val totalCount: Int = 0,
    @SerializedName("activeCount") val activeCount: Int = 0,
    @SerializedName("clientOnlyCount") val clientOnlyCount: Int = 0,
    @SerializedName("disabledCount") val disabledCount: Int = 0,
    @SerializedName("jarFiles") val jarFiles: List<String> = emptyList(),
    @SerializedName("clientJarFiles") val clientJarFiles: List<String> = emptyList(),
    @SerializedName("disableJarFiles") val disableJarFiles: List<String> = emptyList(),
)

/** 插件/模组批量操作请求。 */
data class PmSetRequest(
    @SerializedName("mode") val mode: String,
    @SerializedName("action") val action: String,
    @SerializedName("targets") val targets: List<String>,
)

/** 保存文件内容请求。 */
data class SaveFileRequest(
    @SerializedName("path") val path: String,
    @SerializedName("content") val content: String,
)

/** 上传初始化响应 data。 */
data class UploadInitData(
    @SerializedName("uploadId") val uploadId: String = "",
)

/** 完成分片上传请求。 */
data class UploadFinishRequest(
    @SerializedName("totalChunks") val totalChunks: Int,
)

/** 保存上传文件到实例目录请求。 */
data class SaveUploadRequest(
    @SerializedName("uploadId") val uploadId: String,
    @SerializedName("fileName") val fileName: String,
    @SerializedName("currentPath") val currentPath: String,
)
