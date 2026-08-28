package com.mslx.console.data

import com.mslx.console.data.remote.ApiClient
import com.mslx.console.data.remote.GitHubRelease

/**
 * 应用更新信息（由 GitHub Release 解析而来）。
 */
data class AppUpdateInfo(
    /** 展示用版本标识：普通渠道为语义化版本；Actions 渠道为 "dev"。 */
    val version: String,
    /** Release 介绍（更新内容）。 */
    val notes: String,
    /** APK 下载直链。 */
    val downloadUrl: String,
    /** APK 文件名。 */
    val apkName: String,
    /** APK 大小（字节）。 */
    val apkSize: Long,
    /** 是否为测试版（tag 带 -Beta 后缀，或 Actions 调试构建）。 */
    val beta: Boolean = false,
    /**
     * 是否强制更新：任一高于当前版本的 release tag 带 -Force 后缀，
     * 或其说明含"强制更新"标记（旧版兼容）时为 true。
     */
    val forceUpdate: Boolean = false,
    /** 是否为 Actions 调试构建（来自 dev Release，不稳定，需应用内下载安装）。 */
    val actions: Boolean = false,
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
 * 按更新渠道（稳定/测试/Actions）过滤，与当前版本比较，返回更新信息（无更新时返回 null）。
 */
class UpdateRepository {

    /** 检查是否有新版本。currentVersion 形如 "1.2.16"。 */
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
        // Actions 渠道：取 dev Release 的四段版本号并与当前版本比较，已是最新则不再提示
        if (channel == UpdateChannel.ACTIONS) {
            return parseActionsRelease(releases, currentVersion)
        }

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

    /**
     * 解析 Actions 渠道：取 tag 为 dev 的 Release（由 android.yml 每次 main push 覆盖发布）。
     * 版本号取 Release name（如 1.3.0.28）；若其不高于当前版本（已安装同版本或更新构建）则返回 null，
     * 避免"即使已是最新 Actions 构建仍提示更新"。
     */
    private fun parseActionsRelease(releases: List<GitHubRelease>, currentVersion: String): AppUpdateInfo? {
        val dev = releases.firstOrNull { it.tagName?.trim()?.equals("dev", ignoreCase = true) == true } ?: return null
        val apk = dev.assets.firstOrNull { it.name?.endsWith(".apk", ignoreCase = true) == true } ?: return null
        val url = apk.browserDownloadUrl ?: return null
        // Release name 即四段版本号（如 1.3.0.28）；无法解析时回退 "dev" 并照常提示
        val version = dev.name?.trim()?.takeIf { it.firstOrNull()?.isDigit() == true } ?: "dev"
        if (version != "dev" && compareVersions(version, currentVersion) <= 0) return null
        return AppUpdateInfo(
            version = version,
            notes = dev.body.orEmpty(),
            downloadUrl = url,
            apkName = apk.name ?: "app-debug.apk",
            apkSize = apk.size ?: 0,
            beta = true,
            actions = true,
        )
    }

    /** 解析 tag 为 (版本号, 是否测试版, 是否强制版)。支持 v 前缀与 -Beta/-Force 后缀。 */
    private fun parseTag(tagName: String?): ParsedTag? {
        val raw = tagName?.trim()?.removePrefix("v") ?: return null
        if (raw.isBlank()) return null
        // 非数字开头的 tag（如 dev / nightly）不属于语义化版本，稳定/测试渠道一律跳过
        if (raw.firstOrNull()?.isDigit() != true) return null
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
