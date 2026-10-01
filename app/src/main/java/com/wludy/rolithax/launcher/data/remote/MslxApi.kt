package com.wludy.rolithax.launcher.data.remote

import com.wludy.rolithax.launcher.data.model.ActionRequest
import com.wludy.rolithax.launcher.data.model.ApiResponse
import com.wludy.rolithax.launcher.data.model.AdminCreateUserRequest
import com.wludy.rolithax.launcher.data.model.CancelCreationRequest
import com.wludy.rolithax.launcher.data.model.CreateServerData
import com.wludy.rolithax.launcher.data.model.CreateServerRequest
import com.wludy.rolithax.launcher.data.model.DeleteServerRequest
import com.wludy.rolithax.launcher.data.model.AdminUpdateUserRequest
import com.wludy.rolithax.launcher.data.model.FileItem
import com.wludy.rolithax.launcher.data.model.FrpSummary
import com.wludy.rolithax.launcher.data.model.InstanceInfo
import com.wludy.rolithax.launcher.data.model.InstanceSummary
import com.wludy.rolithax.launcher.data.model.LocalJava
import com.wludy.rolithax.launcher.data.model.PairCodeData
import com.wludy.rolithax.launcher.data.model.PairCodeRequest
import com.wludy.rolithax.launcher.data.model.PairRedeemData
import com.wludy.rolithax.launcher.data.model.PairRedeemRequest
import com.wludy.rolithax.launcher.data.model.PmListData
import com.wludy.rolithax.launcher.data.model.PmSetRequest
import com.wludy.rolithax.launcher.data.model.SaveFileRequest
import com.wludy.rolithax.launcher.data.model.SaveUploadRequest
import com.wludy.rolithax.launcher.data.model.ServerSettings
import com.wludy.rolithax.launcher.data.model.StatusData
import com.wludy.rolithax.launcher.data.model.UploadFinishRequest
import com.wludy.rolithax.launcher.data.model.UploadInitData
import com.wludy.rolithax.launcher.data.model.UpdateSelfRequest
import com.wludy.rolithax.launcher.data.model.UpdateSettingsData
import com.wludy.rolithax.launcher.data.model.ResourceInstallBatchRequest
import com.wludy.rolithax.launcher.data.model.ResourceInstallTaskData
import com.wludy.rolithax.launcher.data.model.UserInfo
import okhttp3.MultipartBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

private const val ROLITHAX_PLUGIN_ROUTE = "api/plugin/mslx-plugin-rolithax"

/**
 * MSLX Daemon 的 REST API。
 * 认证通过 OkHttp 拦截器统一附加 `x-api-key` 请求头完成。
 */
interface MslxApi {

    @GET("api/status")
    suspend fun status(): ApiResponse<StatusData>

    @GET("api/instance/list")
    suspend fun instanceList(): ApiResponse<List<InstanceSummary>>

    @GET("api/instance/info")
    suspend fun instanceInfo(@Query("id") id: Long): ApiResponse<InstanceInfo>

    @POST("api/instance/action")
    suspend fun action(@Body body: ActionRequest): ApiResponse<Any?>

    @POST("api/instance/createServer")
    suspend fun createServer(@Body body: CreateServerRequest): ApiResponse<CreateServerData>

    @POST("api/instance/delete")
    suspend fun deleteInstance(
        @Body body: DeleteServerRequest,
    ): ApiResponse<Any?>

    @POST("api/instance/cancelCreation")
    suspend fun cancelCreation(@Body body: CancelCreationRequest): ApiResponse<Any?>

    @GET("api/instance/settings/general/{id}")
    suspend fun instanceSettings(@Path("id") id: Long): ApiResponse<ServerSettings>

    @POST("api/instance/settings/general/{id}")
    suspend fun updateInstanceSettings(
        @Path("id") id: Long,
        @Body body: ServerSettings,
    ): ApiResponse<UpdateSettingsData>

    @GET("api/files/pm/instance/{id}/list")
    suspend fun pmList(
        @Path("id") id: Long,
        @Query("mode") mode: String,
        @Query("checkClient") checkClient: Boolean = false,
    ): retrofit2.Response<ApiResponse<PmListData>>

    @POST("api/files/pm/instance/{id}/set")
    suspend fun pmSet(
        @Path("id") id: Long,
        @Body body: PmSetRequest,
    ): ApiResponse<Any?>

    @GET("api/files/instance/{id}/lists")
    suspend fun fileList(
        @Path("id") id: Long,
        @Query("path") path: String = "",
    ): ApiResponse<List<FileItem>>

    @GET("api/files/instance/{id}/content")
    suspend fun fileContent(
        @Path("id") id: Long,
        @Query("path") path: String,
    ): ApiResponse<String>

    @POST("api/files/instance/{id}/content")
    suspend fun saveFileContent(
        @Path("id") id: Long,
        @Body body: SaveFileRequest,
    ): ApiResponse<Any?>

    @GET("api/java/list")
    suspend fun javaList(@Query("refresh") refresh: Boolean = false): ApiResponse<List<LocalJava>>

    @GET("api/user/me")
    suspend fun userMe(): ApiResponse<UserInfo>

    @POST("api/user/me/update")
    suspend fun updateSelf(@Body body: UpdateSelfRequest): ApiResponse<Any?>

    @GET("api/admin/user/list")
    suspend fun adminUserList(): ApiResponse<List<UserInfo>>

    @POST("api/admin/user/create")
    suspend fun adminCreateUser(@Body body: AdminCreateUserRequest): ApiResponse<Any?>

    @POST("api/admin/user/update/{id}")
    suspend fun adminUpdateUser(
        @Path("id") id: String,
        @Body body: AdminUpdateUserRequest,
    ): ApiResponse<Any?>

    @POST("api/admin/user/delete/{id}")
    suspend fun adminDeleteUser(@Path("id") id: String): ApiResponse<Any?>

    @GET("api/frp/list")
    suspend fun frpList(): ApiResponse<List<FrpSummary>>

    @POST("api/files/upload/init")
    suspend fun uploadInit(): ApiResponse<UploadInitData>

    @Multipart
    @POST("api/files/upload/chunk/{uploadId}")
    suspend fun uploadChunk(
        @Path("uploadId") uploadId: String,
        @Part("index") index: Int,
        @Part file: MultipartBody.Part,
    ): ApiResponse<Any?>

    @POST("api/files/upload/finish/{uploadId}")
    suspend fun uploadFinish(
        @Path("uploadId") uploadId: String,
        @Body body: UploadFinishRequest,
    ): ApiResponse<Any?>

    @POST("api/files/upload/delete/{uploadId}")
    suspend fun deleteUpload(@Path("uploadId") uploadId: String): ApiResponse<Any?>

    @POST("api/files/instance/{id}/upload")
    suspend fun saveUpload(
        @Path("id") id: Long,
        @Body body: SaveUploadRequest,
    ): ApiResponse<Any?>

    // ---------------- Rolithax 扫码配对插件 ----------------

    /** 生成一次性配对码（需要 admin 权限，TTL 120s）。 */
    @POST("$ROLITHAX_PLUGIN_ROUTE/pair/codes")
    suspend fun pairCreateCode(@Body body: PairCodeRequest): ApiResponse<PairCodeData>

    /** 兑换配对码（匿名端点，插件内部做签名/时效/一次性/IP 限速校验）。 */
    @POST("$ROLITHAX_PLUGIN_ROUTE/pair/redeem")
    suspend fun pairRedeem(@Body body: PairRedeemRequest): ApiResponse<PairRedeemData>

    @POST("$ROLITHAX_PLUGIN_ROUTE/resources/install")
    suspend fun installResources(@Body body: ResourceInstallBatchRequest): ApiResponse<ResourceInstallTaskData>

    @GET("$ROLITHAX_PLUGIN_ROUTE/resources/install/{id}")
    suspend fun resourceInstallTask(@Path("id") id: String): ApiResponse<ResourceInstallTaskData>

    @POST("$ROLITHAX_PLUGIN_ROUTE/resources/install/{id}/cancel")
    suspend fun cancelResourceInstall(@Path("id") id: String): ApiResponse<Any?>

    // ---------------- 实例图标 ----------------

    /** Daemon 内置图标端点：读取实例目录下的 server-icon.png（未放置时 404 + JSON）。 */
    @GET("api/instance/icon/{id}.png")
    suspend fun instanceIcon(@Path("id") id: Long): retrofit2.Response<okhttp3.ResponseBody>

    /** 图标插件端点：Daemon 侧本地 → 磁盘缓存 → 第三方查询三级回退。 */
    @GET("$ROLITHAX_PLUGIN_ROUTE/icon/server/{id}")
    suspend fun pluginServerIcon(@Path("id") id: Long): retrofit2.Response<okhttp3.ResponseBody>
}
