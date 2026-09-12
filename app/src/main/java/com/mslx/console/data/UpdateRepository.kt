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
    /**
     * CNB（cnb.cool）镜像下载直链（稳定/测试渠道）。CNB 为首选更新源，
     * 下载失败时客户端自动回退 [downloadUrl]（GitHub）。为 null 时仅使用 GitHub。
     */
    val cnbUrl: String? = null,
    /**
     * 精简版 APK（**不含**内嵌 JRE 运行时，体积小）直链；为 null 表示该 Release 只提供完整版。
     * 同一个 Release 会同时挂载完整版与精简版两个包（见 CI 配置）。
     */
    val liteUrl: String? = null,
    /** 精简版 APK 大小（字节）。 */
    val liteSize: Long = 0,
    /** 精简版 CNB 镜像直链。 */
    val liteCnbUrl: String? = null,
    /** 该更新包是否内嵌 JRE 运行时（本机开服可离线使用），按体积判定。 */
    val embeddedJre: Boolean = false,
) {
    /** 该 Release 是否同时提供精简版。 */
    val hasLiteVariant: Boolean get() = !liteUrl.isNullOrBlank()
}

/** CNB Release 资产下载地址前缀（形如 .../-/releases/download/v1.5/app-release.apk）。 */
private const val CNB_RELEASE_DOWNLOAD_BASE = "https://cnb.cool/WLudy/MSLX_APP-Android/-/releases/download"

/** 内嵌 JRE 运行时的包体积下限（arm64 运行时约 36MB，远大于无 JRE 的 ~10MB）。 */
private const val EMBEDDED_JRE_MIN_BYTES = 30L * 1024 * 1024

/** 完整版 / 精简版资产名（CI 固定命名，见 .github/workflows/release.yml 与 .cnb.yml）。 */
private const val APK_FULL_NAME = "app-release.apk"
private const val APK_LITE_NAME = "app-release-lite.apk"

/** 单个 release 的解析结果（内部使用）。 */
private data class ParsedRelease(
    val version: String,
    val beta: Boolean,
    val force: Boolean,
    val release: GitHubRelease,
    val apk: com.mslx.console.data.remote.GitHubReleaseAsset,
    val lite: com.mslx.console.data.remote.GitHubReleaseAsset? = null,
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
                val (full, lite) = pickApkAssets(release)
                val url = full?.browserDownloadUrl
                if (full == null || url.isNullOrBlank()) null
                else ParsedRelease(p.version, p.beta, p.force, release, full, lite)
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

        val apkName = newest.apk.name ?: APK_FULL_NAME
        val tag = newest.release.tagName?.takeIf { it.isNotBlank() }
        val lite = newest.lite
        return AppUpdateInfo(
            version = newest.version,
            notes = newest.release.body.orEmpty(),
            downloadUrl = newest.apk.browserDownloadUrl.orEmpty(),
            apkName = apkName,
            apkSize = newest.apk.size ?: 0,
            beta = newest.beta,
            forceUpdate = forceUpdate,
            embeddedJre = (newest.apk.size ?: 0) >= EMBEDDED_JRE_MIN_BYTES,
            // CNB 首选：镜像仓库同名 tag 的 Release 资产（下载失败时由客户端回退 GitHub）
            cnbUrl = tag?.let { "$CNB_RELEASE_DOWNLOAD_BASE/$it/$apkName" },
            liteUrl = lite?.browserDownloadUrl,
            liteSize = lite?.size ?: 0,
            liteCnbUrl = if (lite != null && tag != null) {
                "$CNB_RELEASE_DOWNLOAD_BASE/$tag/${lite.name}"
            } else {
                null
            },
        )
    }

    /**
     * 从 release 资产里挑出「完整版（含内嵌 JRE）」与「精简版（不含 JRE）」。
     * 命名不匹配时回退：把最大的 APK 当完整版，其余非同名 APK 视为候选精简版。
     */
    private fun pickApkAssets(
        release: GitHubRelease,
    ): Pair<com.mslx.console.data.remote.GitHubReleaseAsset?, com.mslx.console.data.remote.GitHubReleaseAsset?> {
        val apks = release.assets.filter { it.name?.endsWith(".apk", ignoreCase = true) == true }
        if (apks.isEmpty()) return null to null
        val lite = apks.firstOrNull { it.name.equals(APK_LITE_NAME, ignoreCase = true) }
        val full = apks.firstOrNull { it.name.equals(APK_FULL_NAME, ignoreCase = true) }
            ?: apks.filter { it !== lite }.maxByOrNull { it.size ?: 0 }
        return full to lite
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
