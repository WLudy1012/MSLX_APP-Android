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
    /** 存放位置（公共/私有）；旧实例无 instance.json 时按目录所在根判定。 */
    val storage: InstanceStorage = InstanceStorage.PRIVATE,
    val meta: LocalInstanceMeta,
) {
    /** 一行式描述：核心 版本 · 大小（公共目录额外标一下位置）。 */
    val subtitle: String
        get() = buildString {
            if (core.isNotBlank()) append(core)
            if (coreVersion.isNotBlank()) append(" ").append(coreVersion)
            if (sizeMb > 0) {
                if (isNotEmpty()) append(" · ")
                append(sizeMb).append("MB")
            }
            if (storage == InstanceStorage.PUBLIC) {
                if (isNotEmpty()) append(" · ")
                append("公共目录")
            }
            if (!jarReady) append(if (isNotEmpty()) " · " else "").append("缺核心")
        }
}

/** 新建实例的落地结果（实际存放位置可能因未授权而回退为私有）。 */
data class LocalInstancePlacement(val dirName: String, val dir: File, val storage: InstanceStorage)

/**
 * 本机实例仓库：一个目录一个实例，分别住在
 * `filesDir/mslx/servers/`（私有）与 `/storage/emulated/0/MSLX/servers/`（公共）两个根下。
 * 只做文件层读写（列表/加载/删除/统计），不持有运行状态——运行状态见 [LocalServerRuntime]。
 *
 * 目录名经 [LocalStorage.allocateDirName] 在两个根之间分配为**唯一**，所以按目录名寻址就够，
 * 不必把 location 塞进路由（`ServerRef.local(dirName)`）。
 */
object LocalInstanceStore {

    /** 扫描两个根目录下的实例（缺 instance.json 的目录也会列出，以目录名兜底，便于用户清理残留）。 */
    fun list(context: Context): List<LocalInstanceSummary> =
        InstanceStorage.entries
            .flatMap { storage ->
                val base = LocalStorage.serversDir(context, storage)
                if (!base.isDirectory) return@flatMap emptyList()
                base.listFiles().orEmpty().filter { it.isDirectory }.map { dir -> summarize(context, dir, storage) }
            }
            .sortedByDescending { it.updatedAt }

    fun summarize(context: Context, dir: File, storage: InstanceStorage = LocalStorage.storageOf(dir)): LocalInstanceSummary {
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
            storage = storage,
            meta = meta?.copy(storage = storage.key) ?: LocalInstanceMeta(name = dir.name, directory = dir.name, storage = storage.key),
        )
    }

    /** 按目录名加载实例元数据（不存在返回 null）。 */
    fun load(context: Context, dirName: String): LocalInstanceMeta? {
        val (storage, dir) = resolve(context, dirName) ?: return null
        return (ServerFiles.readMeta(dir) ?: LocalInstanceMeta(name = dir.name, directory = dir.name)).copy(storage = storage.key)
    }

    fun exists(context: Context, dirName: String): Boolean = resolve(context, dirName) != null

    /** 按目录名解析出（存放位置, 目录）；两边都没有时返回 null。 */
    fun resolve(context: Context, dirName: String): Pair<InstanceStorage, File>? =
        LocalStorage.resolveServerDir(context, dirName)

    /** 实例所在存放位置（未知/不存在时按私有）。 */
    fun storageOf(context: Context, dirName: String): InstanceStorage =
        resolve(context, dirName)?.first ?: InstanceStorage.PRIVATE

    fun coreJar(context: Context, dirName: String): File =
        File(dir(context, dirName), ServerFiles.SERVER_JAR_NAME)

    /** 已存在实例的目录；不存在时退回私有目录下的同名路径（便于调用方直接报错）。 */
    fun dir(context: Context, dirName: String): File =
        resolve(context, dirName)?.second ?: LocalStorage.serverDir(context, dirName)

    /**
     * 新建实例目录：在两个根下分配唯一名后建目录。[storage] 为 PUBLIC 但未授权时自动回退私有，
     * 调用方据返回的 [LocalInstancePlacement.storage] 提示用户实际落地位置（不阻断开服）。
     */
    fun createDir(context: Context, name: String, storage: InstanceStorage): LocalInstancePlacement {
        val actual = if (storage == InstanceStorage.PUBLIC && !LocalStorage.publicStorageGranted()) {
            InstanceStorage.PRIVATE
        } else {
            storage
        }
        LocalStorage.ensureBase(context, actual)
        val dirName = LocalStorage.allocateDirName(context, name)
        val dir = LocalStorage.serverDir(context, dirName, actual)
        dir.mkdirs()
        return LocalInstancePlacement(dirName, dir, actual)
    }

    /** 删除实例：整目录递归删除（世界存档一并删除，调用方需先确认）。 */
    fun delete(context: Context, dirName: String): Result<Unit> = runCatching {
        val (storage, dir) = resolve(context, dirName) ?: throw IllegalStateException("实例不存在：$dirName")
        // 双保险：只允许删除对应根目录下的子目录，防止路径穿越
        val base = LocalStorage.serversDir(context, storage).canonicalPath + File.separator
        if (!dir.canonicalPath.startsWith(base)) throw IllegalStateException("非法实例目录：${dir.path}")
        val ok = dir.deleteRecursively()
        if (!ok && dir.exists()) throw IllegalStateException("删除失败（可能有文件被占用）")
        AppLogger.i("LocalInstance", "已删除实例目录 $dirName（${storage.label}）")
    }
}
