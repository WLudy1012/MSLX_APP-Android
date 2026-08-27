package com.mslx.console.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET

/** 一言 API（https://v1.hitokoto.cn/）返回结构（部分字段）。 */
data class HitokotoQuote(
    @SerializedName("hitokoto") val hitokoto: String? = null,
    @SerializedName("from") val from: String? = null,
    @SerializedName("from_who") val fromWho: String? = null,
)

/** 一言（Hitokoto）每日金句接口。 */
interface HitokotoApi {

    /** 获取一句随机金句（根路径，无需参数）。 */
    @GET(".")
    suspend fun quote(): HitokotoQuote
}
