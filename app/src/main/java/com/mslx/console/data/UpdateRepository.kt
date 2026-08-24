package com.mslx.console.data

import com.mslx.console.data.remote.ApiClient
import com.mslx.console.data.remote.GitHubRelease

/**
 * 应用更新信息（由 GitHub Release 解析而来）。
 */
data class AppUpdateInfo(
    /** 最新版本号（去掉 v 前缀与 Beta/Force 后缀），如 "1.2.13"。 */
    val version: String,
    /** Release 介绍（更新内容）。 */
    val notes: String,
    /** APK 下载直链。 */
    val downloadUrl: String,
    /** APK 文件名。 */
    val apkName: String,
    /** APK 大小（字节）。 */
    val apkSize: Long,
    /** 是否为测试版（tag 带 -Beta 后缀）。 */
    val beta: Boolean = false,
    /**
     * 是否强制更新：任一高于当前版本的 release tag 带 -Force 后缀，
     * 或其说明含"强制更新"标记（旧版兼容）时为 true。
     */
    val forceUpdate: Boolean = false,
)

/** 单个 release 的解析结果（内部使用）。 */
private data class ParsedRelease(
    val version: String,
    val beta: Boolean,
    val force: Boolean,
    val release: GitHubRelease,
    val apk: com.mslx.console.data.remote.GitHubReleaseAsset,
)

/**
 * 检查应用更新：查询 GitHub 仓库 Release 列表，
 * 按更新渠道（稳定/测试）过滤，与当前版本比较，返回更新信息（无更新时返回 null）。
 */
class UpdateRepository {

    /** 检查是否有新版本。currentVersion 形如 "1.2.13"。 */
    suspend fun checkLatest(currentVersion: String, channel: UpdateChannel): Result<AppUpdateInfo?> = runCatching {
        val releases = ApiClient.buildGitHubReleaseApi().releases()
        parseUpdate(releases, currentVersion, channel)
    }

    /** 从 Release 列表解析更新信息；无更新时返回 null。 */
    private fun parseUpdate(
        releases: List<GitHubRelease>,
        currentVersion: String,
        channel: UpdateChannel,
    ): AppUpdateInfo? {
        // 过滤出正式(非预发布)且带 APK 资产的版本，解析 tag 的 Beta/Force 后缀
        val parsed = releases
            .filter { !it.prerelease }
            .mapNotNull { release -> parseTag(release.tagName)?.let { p ->
                val apk = release.assets.firstOrNull { it.name?.endsWith(".apk", ignoreCase = true) == true }
                val url = apk?.browserDownloadUrl
                if (url.isNullOrBlank()) null else ParsedRelease(p.version, p.beta, p.force, release, apk)
            } }

        // 渠道过滤：稳定渠道只出稳定版；测试渠道稳定+测试都出
        val candidates = if (channel == UpdateChannel.BETA) {
            parsed
        } else {
            parsed.filter { !it.beta }
        }

        val newest = candidates
            .sortedWith { a, b -> compareVersions(b.version, a.version) }
            .firstOrNull() ?: return null
        if (compareVersions(newest.version, currentVersion) <= 0) return null

        // 强制判定：任一高于当前版本的 release 带 Force 后缀或说明含"强制更新"（旧版兼容）
        val forceUpdate = parsed.any { p ->
            compareVersions(p.version, currentVersion) > 0 &&
                (p.force || p.release.body.orEmpty().contains("强制更新", ignoreCase = true))
        }

        return AppUpdateInfo(
            version = newest.version,
            notes = newest.release.body.orEmpty(),
            downloadUrl = newest.apk.browserDownloadUrl.orEmpty(),
            apkName = newest.apk.name ?: "app-release.apk",
            apkSize = newest.apk.size ?: 0,
            beta = newest.beta,
            forceUpdate = forceUpdate,
        )
    }

    /** 解析 tag 为 (版本号, 是否测试版, 是否强制版)。支持 v 前缀与 -Beta/-Force 后缀。 */
    private fun parseTag(tagName: String?): ParsedTag? {
        val raw = tagName?.trim()?.removePrefix("v") ?: return null
        if (raw.isBlank()) return null
        val lower = raw.lowercase()
        val beta = lower.endsWith("-beta")
        val force = lower.endsWith("-force")
        var version = raw
        if (beta || force) version = raw.substringBeforeLast("-").trim()
        if (version.isBlank()) return null
        return ParsedTag(version, beta, force)
    }

    private data class ParsedTag(val version: String, val beta: Boolean, val force: Boolean)

    /** 语义化版本比较："1.10.0" > "1.9.9"。返回正数表示 a 更新。 */
    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".").mapNotNull { it.toIntOrNull() }
        val pb = b.split(".").mapNotNull { it.toIntOrNull() }
        val max = maxOf(pa.size, pb.size)
        for (i in 0 until max) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}
