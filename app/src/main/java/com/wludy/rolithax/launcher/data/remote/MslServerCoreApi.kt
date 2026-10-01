package com.wludy.rolithax.launcher.data.remote

import com.google.gson.JsonElement
import com.wludy.rolithax.launcher.data.model.ApiResponse
import com.wludy.rolithax.launcher.data.model.ServerCoreDownloadInfo
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * MSLAPI v4 服务端核心接口(无需认证)。
 * 与网页版 api/mslapi/serverCore.ts 保持一致。
 *
 * MSLAPI 统一响应为 { code, message, data }，因此一律用 ApiResponse<T> 接收，
 * 由 Gson 自动解包 data 字段。
 */
interface MslServerCoreApi {

    /** 服务端核心分类。data 字段可能返回对象，也可能返回单元素数组，故用 JsonElement 手动解析。 */
    @GET("mirrors")
    suspend fun classify(): ApiResponse<JsonElement>

    /** 服务端核心支持版本。data 字段可能返回对象，也可能返回单元素数组，故用 JsonElement 手动解析。 */
    @GET("mirrors/{name}")
    suspend fun gameVersion(@Path("name") name: String): ApiResponse<JsonElement>

    @GET("mirrors/{name}/{version}")
    suspend fun builds(
        @Path("name") name: String,
        @Path("version") version: String,
    ): ApiResponse<List<String>>

    @GET("download/server/{name}/{version}")
    suspend fun downloadInfo(
        @Path("name") name: String,
        @Path("version") version: String,
        @Query("build") build: String = "latest",
    ): ApiResponse<ServerCoreDownloadInfo>
}
