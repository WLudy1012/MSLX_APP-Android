package com.mslx.console.data.localengine

import android.content.Context
import com.mslx.console.data.AppLogger
import java.io.File

/** 实例摘要（列表展示用）。 */
data class LocalInstanceSummary(
    val dirName: String,
    val name: String,
    val core: String,
    val coreVersion: String,
    val jarReady: Boolean,
    val sizeMb: Long,
    val updatedAt: String,
    val meta: LocalInstanceMeta,
) {
    /** 一行式描述：核心 版本 · 大小。 */
    val subtitle: String
        get() = buildString {
            if (core.isNotBlank()) append(core)
            if (coreVersion.isNotBlank()) append(" ").append(coreVersion)
            if (sizeMb > 0) {
                if (isNotEmpty()) append(" · ")
                append(sizeMb).append("MB")
            }
            if (!jarReady) append(if (isNotEmpty()) " · " else "").append("缺核心")
        }
}

/**
 * 本机实例仓库：`mslx/servers/` 下的**一个目录一个实例**（对应 daemon 的 `Servers/<id>`）。
 * 只做文件层读写（列表/加载/删除/统计），不持有运行状态——运行状态见 [LocalServerRuntime]。
 */
object LocalInstanceStore {

    /** 扫描实例目录。缺少 instance.json 的目录也会列出（以目录名兜底），便于用户清理残留。 */
    fun list(context: Context): List<LocalInstanceSummary> {
        val base = LocalStorage.serversDir(context)
        if (!base.isDirectory) return emptyList()
        return base.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .map { dir -> summarize(context, dir) }
            .sortedByDescending { it.updatedAt }
    }

    fun summarize(context: Context, dir: File): LocalInstanceSummary {
        val meta = ServerFiles.readMeta(dir)
        val jar = File(dir, ServerFiles.SERVER_JAR_NAME)
        val sizeMb = runCatching { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1024 / 1024 }
            .getOrDefault(0)
        return LocalInstanceSummary(
            dirName = dir.name,
            name = meta?.name?.takeIf { it.isNotBlank() } ?: dir.name,
            core = meta?.core.orEmpty(),
            coreVersion = meta?.coreVersion.orEmpty(),
            jarReady = jar.isFile,
            sizeMb = sizeMb,
            updatedAt = meta?.updatedAt.orEmpty(),
            meta = meta ?: LocalInstanceMeta(name = dir.name, directory = dir.name),
        )
    }

    /** 按目录名加载实例元数据（不存在返回 null）。 */
    fun load(context: Context, dirName: String): LocalInstanceMeta? {
        val dir = File(LocalStorage.serversDir(context), dirName)
        if (!dir.isDirectory) return null
        return ServerFiles.readMeta(dir) ?: LocalInstanceMeta(name = dir.name, directory = dir.name)
    }

    fun exists(context: Context, dirName: String): Boolean =
        File(LocalStorage.serversDir(context), dirName).isDirectory

    fun coreJar(context: Context, dirName: String): File =
        File(File(LocalStorage.serversDir(context), dirName), ServerFiles.SERVER_JAR_NAME)

    fun dir(context: Context, dirName: String): File =
        File(LocalStorage.serversDir(context), dirName)

    /** 删除实例：整目录递归删除（世界存档一并删除，调用方需先确认）。 */
    fun delete(context: Context, dirName: String): Result<Unit> = runCatching {
        val dir = dir(context, dirName)
        if (!dir.isDirectory) throw IllegalStateException("实例不存在：$dirName")
        // 双保险：只允许删除 servers 根下的子目录，防止路径穿越
        val base = LocalStorage.serversDir(context).canonicalPath + File.separator
        if (!dir.canonicalPath.startsWith(base)) throw IllegalStateException("非法实例目录：${dir.path}")
        val ok = dir.deleteRecursively()
        if (!ok && dir.exists()) throw IllegalStateException("删除失败（可能有文件被占用）")
        AppLogger.i("LocalInstance", "已删除实例目录 $dirName")
    }
}
