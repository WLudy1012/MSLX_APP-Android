package com.mslx.console.data.localengine

import com.mslx.console.data.AppLogger
import com.mslx.console.data.InstanceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 服务端核心安装器：对接 MSLAPI（v4 mirrors/download）获取核心下载信息，
 * 下载到本机世界目录并做 SHA-256 校验（与 daemon 侧创建实例流程同源）。
 */
class LocalCoreInstaller(
    private val repository: InstanceRepository,
) {

    /** 从 MSLAPI 获取可下载核心名（vanilla/插件服常用子集，需与 v4/mirrors 字段一致）。 */
    suspend fun fetchCoreNames(): List<String> =
        repository.serverCoreClassify().getOrNull()?.let { c ->
            (c.vanillaCore + c.pluginsCore + c.bedrockCore)
                .filter { it.isNotBlank() }
                .distinct()
                .take(20)
        } ?: emptyList()

    /** 获取某核心支持的游戏版本。 */
    suspend fun fetchVersions(core: String): List<String> =
        repository.serverCoreGameVersion(core).getOrNull()?.versions.orEmpty()

    /**
     * 下载核心到 [worldsDir]，返回 jar 文件。
     * 复用 daemon 创建实例的核心契约：ServerCoreDownloadInfo{url, sha256}。
     */
    suspend fun installCore(
        core: String,
        version: String,
        build: String,
        worldsDir: File,
        onProgress: (Float) -> Unit = {},
    ): Result<File> = runCatching {
        val info = repository.serverCoreDownloadInfo(core, version, build).getOrNull()
            ?: throw IllegalStateException("MSLAPI 未返回核心下载信息")
        val url = info.url ?: throw IllegalStateException("核心下载地址为空")
        val safeName = "$core-$version".replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(worldsDir, "$safeName.jar")
        withContext(Dispatchers.IO) {
            LocalDownloader.download(url, target, info.sha256) { onProgress(it) }
        }
        AppLogger.i("LocalCore", "核心下载完成: $core $version -> ${target.absolutePath}")
        target
    }
}
