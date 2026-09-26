package com.mslx.console.data.model

import com.google.gson.annotations.SerializedName

/**
 * 扫码配对（mslx-plugin-thirdparty-android-addons）相关模型。
 * 端点前缀：`/api/plugin/mslx-plugin-thirdparty-android-addons/pair`，响应沿用 Daemon 统一结构 { code, message, data }。
 */

/** POST /api/plugin/mslx-plugin-thirdparty-android-addons/pair/codes 请求体（生成一次性配对码）。 */
data class PairCodeRequest(
    /** 覆盖自动识别的对外地址（留空则取当前请求的 Host）。 */
    @SerializedName("publicUrl") val publicUrl: String? = null,
    /** full=兑换后获得 admin 级配对用户；limited=按 resources 授予受限权限。 */
    @SerializedName("scope") val scope: String = "full",
    @SerializedName("resources") val resources: List<String>? = null,
    @SerializedName("deviceTtlDays") val deviceTtlDays: Int = 30,
)

/** POST /api/plugin/mslx-plugin-thirdparty-android-addons/pair/codes 的 data 字段。 */
data class PairCodeData(
    @SerializedName("code") val code: String = "",
    @SerializedName("expiresAt") val expiresAt: String? = null,
    @SerializedName("expiresInSeconds") val expiresInSeconds: Int = 120,
    @SerializedName("scope") val scope: String = "full",
    @SerializedName("deviceTtlDays") val deviceTtlDays: Int = 30,
    /** 二维码内容（`mslxp1:` 前缀 + Base64Url(JSON)）。 */
    @SerializedName("payload") val payload: String = "",
    @SerializedName("url") val url: String? = null,
)

/** POST /api/plugin/mslx-plugin-thirdparty-android-addons/pair/redeem 请求体（兑换二维码载荷）。 */
data class PairRedeemRequest(
    @SerializedName("payload") val payload: String,
    @SerializedName("deviceName") val deviceName: String? = null,
    @SerializedName("deviceFingerprint") val deviceFingerprint: String? = null,
)

/** POST /api/plugin/mslx-plugin-thirdparty-android-addons/pair/redeem 的 data 字段（完整 API Key 仅此一次返回）。 */
data class PairRedeemData(
    @SerializedName("daemonUrl") val daemonUrl: String? = null,
    @SerializedName("apiKey") val apiKey: String? = null,
    @SerializedName("deviceId") val deviceId: String? = null,
    @SerializedName("userId") val userId: String? = null,
    @SerializedName("role") val role: String? = null,
    @SerializedName("expiresAt") val expiresAt: String? = null,
)
