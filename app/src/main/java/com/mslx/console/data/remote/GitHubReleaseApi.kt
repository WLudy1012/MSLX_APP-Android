package com.mslx.console.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET

/** GitHub Releases API 的 release 元素(部分字段)。 */
data class GitHubRelease(
    @SerializedName("tag_name") val tagName: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("body") val body: String? = null,
    @SerializedName("prerelease") val prerelease: Boolean = false,
    @SerializedName("published_at") val publishedAt: String? = null,
    @SerializedName("assets") val assets: List<GitHubReleaseAsset> = emptyList(),
)

/** release 附件(APK)。 */
data class GitHubReleaseAsset(
    @SerializedName("name") val name: String? = null,
    @SerializedName("browser_download_url") val browserDownloadUrl: String? = null,
    @SerializedName("size") val size: Long = 0,
)

/** 仓库贡献者(部分字段)。 */
data class GitHubContributor(
    @SerializedName("login") val login: String? = null,
    @SerializedName("avatar_url") val avatarUrl: String? = null,
    @SerializedName("html_url") val htmlUrl: String? = null,
    @SerializedName("contributions") val contributions: Int = 0,
)

/** GitHub Releases 接口(公开仓库，无需认证)。 */
interface GitHubReleaseApi {

    /** 获取最新正式 release。 */
    @GET("repos/WLudy1012/MSLX_APP-Android/releases/latest")
    suspend fun latestRelease(): GitHubRelease

    /** 获取最近一批 release（含历史强制更新版本，用于强制更新判定）。 */
    @GET("repos/WLudy1012/MSLX_APP-Android/releases?per_page=30")
    suspend fun releases(): List<GitHubRelease>

    /** 获取仓库贡献者列表。 */
    @GET("repos/WLudy1012/MSLX_APP-Android/contributors?per_page=30")
    suspend fun contributors(): List<GitHubContributor>
}
