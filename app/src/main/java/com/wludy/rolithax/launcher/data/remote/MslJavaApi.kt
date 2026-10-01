package com.wludy.rolithax.launcher.data.remote

import com.wludy.rolithax.launcher.data.model.ApiResponse
import retrofit2.http.GET
import retrofit2.http.Query

/** MSLX 官方在线 API(MSLAPI)，无需认证。 */
interface MslJavaApi {

    @GET("query/jdk")
    suspend fun jdkVersions(
        @Query("os") os: String,
        @Query("arch") arch: String,
    ): ApiResponse<List<String>>
}

/** Microsoft OpenJDK 官方仓库的 Release 摘要。 */
data class MicrosoftOpenJdkRelease(
    val tag_name: String? = null,
)

interface MicrosoftJavaApi {
    @GET("repos/microsoft/openjdk/releases")
    suspend fun releases(@Query("per_page") pageSize: Int = 100): List<MicrosoftOpenJdkRelease>
}
