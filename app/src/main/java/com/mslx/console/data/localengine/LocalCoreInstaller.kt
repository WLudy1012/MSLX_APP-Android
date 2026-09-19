package com.mslx.console.data.localengine

import com.mslx.console.data.AppLogger
import com.mslx.console.data.InstanceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 服务端核心安装器：对接 MSLAPI（v4 mirrors/download）取核心下载信息，
 * 下载到**实例目录并统一命名 `server.jar`**（同 daemon 约定），
 * 随后由 [ServerFiles] 补全实例其余文件（eula / server.properties / 名单 / 元数据）。
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
     * 下载核心到实例目录 [serverDir]/server.jar（含 SHA-256 校验），
     * 成功后按 daemon 流程补全实例文件，返回 (核心文件, 实例元数据)。
     */
    suspend fun installCore(
        core: String,
        version: String,
        build: String,
        serverDir: File,
        meta: LocalInstanceMeta,
        onProgress: (Float) -> Unit = {},
    ): Result<Pair<File, LocalInstanceMeta>> = runCatching {
        val info = repository.serverCoreDownloadInfo(core, version, build).getOrNull()
            ?: throw IllegalStateException("MSLAPI 未返回核心下载信息")
        val url = info.url ?: throw IllegalStateException("核心下载地址为空")
        serverDir.mkdirs()
        val target = File(serverDir, ServerFiles.SERVER_JAR_NAME)
        withContext(Dispatchers.IO) {
            LocalDownloader.download(url, target, info.sha256) { onProgress(it) }
        }
        AppLogger.i(
            "LocalCore",
            "核心下载完成：$core $version → ${target.absolutePath}",
        )
        // 补全实例剩余文件（eula/server.properties/名单/目录/instance.json），模仿 daemon 创建流程
        val finalMeta = ServerFiles.complete(
            serverDir,
            meta.copy(core = core, coreVersion = version, coreBuild = build),
        ).getOrThrow()
        target to finalMeta
    }

    /**
     * 从已解析的下载地址 [url]（+ 可选 [sha256]）下载核心到 [serverDir]/server.jar，
     * 供创建向导「本机」目标复用 MSLAPI 核心选择器已拿到的下载信息（无需按 build 再解析一次）。
     */
    suspend fun installFromUrl(
        url: String,
        sha256: String,
        core: String,
        version: String,
        serverDir: File,
        meta: LocalInstanceMeta,
        onProgress: (Float) -> Unit = {},
    ): Result<Pair<File, LocalInstanceMeta>> = runCatching {
        if (url.isBlank()) throw IllegalStateException("核心下载地址为空")
        serverDir.mkdirs()
        val target = File(serverDir, ServerFiles.SERVER_JAR_NAME)
        withContext(Dispatchers.IO) {
            LocalDownloader.download(url, target, sha256.ifBlank { null }) { onProgress(it) }
        }
        AppLogger.i("LocalCore", "核心下载完成（URL）：$core $version → ${target.absolutePath}")
        val finalMeta = ServerFiles.complete(
            serverDir,
            meta.copy(core = core, coreVersion = version),
        ).getOrThrow()
        target to finalMeta
    }
}
