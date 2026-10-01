package com.wludy.rolithax.launcher.data.resources

import android.content.Context
import com.google.gson.JsonObject
import com.wludy.rolithax.launcher.BuildConfig
import okhttp3.Cache
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

interface ModrinthApi {
    @GET("search")
    suspend fun search(
        @Query("query") query: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int,
        @Query("project_type") type: String,
        @Query("game_version") gameVersion: String?,
        @Query("loader") loader: String?,
        @Header("If-None-Match") etag: String?,
    ): Response<JsonObject>

    @GET("project/{id}/version")
    suspend fun versions(
        @Path("id") id: String,
        @Query("game_version") gameVersion: String?,
        @Query("loader") loader: String?,
    ): Response<List<JsonObject>>

    @GET("project/{id}")
    suspend fun project(@Path("id") id: String): Response<JsonObject>

    @GET("version/{id}")
    suspend fun version(@Path("id") id: String): Response<JsonObject>
}

object ModrinthApiFactory {
    fun create(context: Context): ModrinthApi {
        val cacheDir = java.io.File(context.cacheDir, "http/modrinth-v3").apply { mkdirs() }
        val client = OkHttpClient.Builder()
            .cache(Cache(cacheDir, 32L * 1024 * 1024))
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "Rolithax-Launcher/${BuildConfig.VERSION_NAME} (https://github.com/WLudy1012/MSLX_APP-Android)")
                        .header("Accept", "application/json")
                        .build(),
                )
            }
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl("https://api.modrinth.com/v3/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ModrinthApi::class.java)
    }
}
