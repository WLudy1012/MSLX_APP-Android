package com.mslx.console.data.localengine

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.mslx.console.data.AppLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本机实例的存放位置。
 *
 *  - [PRIVATE]：`filesDir/mslx/servers/<目录名>`，随应用卸载消失、无需任何权限；
 *  - [PUBLIC]：`/storage/emulated/0/MSLX/servers/<目录名>`，用户可用文件管理器直接看到
 *    （换核心、放整合包、备份存档都在系统文件里操作），需要「所有文件访问」授权。
 *
 * 两者**目录名全局唯一**（创建时经 [allocateDirName] 分配），因此 `ServerRef.local(目录名)`
 * 不会歧义，路由与通知都只带目录名。
 */
enum class InstanceStorage(val key: String, val label: String) {
    PRIVATE("private", "应用私有目录"),
    PUBLIC("public", "公共目录"),
    ;

    companion object {
        /** 兼容旧 instance.json（无该字段时按私有处理）。 */
        fun fromKey(key: String?): InstanceStorage = if (key?.equals(PUBLIC.key, ignoreCase = true) == true) PUBLIC else PRIVATE
    }
}

/**
 * 本机开服的**数据目录**（对应上游 MSLX Daemon 的 `GetAppDataPath()`）。
 *
 * 布局：
 * ```
 * filesDir/mslx/                    <- 私有数据根目录（AppData）
 *   runtime/jre17/                  <- Java 运行时（= java.home，含 lib/server/libjvm.so）
 *   servers/<实例名>/                <- 一个实例一个目录（对应 daemon 的 Servers/<id>）
 *     server.jar                    <- 服务端核心（下载后固定命名，同 daemon 约定）
 *     server.properties             <- 补全的服务端配置
 *     eula.txt                      <- 已同意 EULA（格式同 daemon）
 *     instance.json                 <- 实例元数据（核心/版本/内存/JVM 参数/创建时间…）
 *     ops.json / whitelist.json / banned-*.json
 *     logs/ plugins/ mods/ world/   <- 服务端运行期目录（按核心类型预建）
 *   cache/                          <- 下载临时文件
 * /storage/emulated/0/MSLX/servers/ <- 公共目录实例（仅服务器文件，运行时始终在私有目录）
 * ```
 *
 * 之前把 JRE 直接放在 `filesDir/jre`、核心放在 `filesDir/worlds/` 是临时做法，
 * [migrateLegacy] 会把旧布局迁移到新结构（不删用户数据）。
 */
object LocalStorage {

    private const val ROOT_NAME = "mslx"

    /** 公共目录下用大写 MSLX：用户在文件管理器里更易识别。 */
    private const val PUBLIC_ROOT_NAME = "MSLX"
    private const val LEGACY_JRE_DIR = "jre"
    private const val LEGACY_WORLDS_DIR = "worlds"

    /** 数据根目录。 */
    fun root(context: Context): File = File(context.filesDir, ROOT_NAME)

    /** 运行时根目录（对应 daemon 的 Tools/Java）。 */
    fun runtimeDir(context: Context): File = File(root(context), "runtime")

    /** 指定运行时的 JRE 安装目录（= java.home 根）：`runtime/<runtimeId>`（如 jre8/jre17/jre21/jre25）。 */
    fun jreHome(context: Context, runtimeId: String): File = File(runtimeDir(context), runtimeId)

    /** 默认运行时（jre17）的 JRE 安装目录。 */
    fun jreHome(context: Context): File = jreHome(context, LocalJreManager.RUNTIME_ID)

    /** 实例根目录（对应 daemon 的 Servers）：默认私有位置。 */
    fun serversDir(context: Context): File = serversDir(context, InstanceStorage.PRIVATE)

    fun serversDir(context: Context, storage: InstanceStorage): File =
        if (storage == InstanceStorage.PUBLIC) File(publicRoot(), "servers") else File(root(context), "servers")

    /** 下载缓存目录（对应 daemon 的 Temp/Uploads）。 */
    fun cacheDir(context: Context): File = File(root(context), "cache")

    /** 某个实例的目录；[name] 会被消毒成合法目录名。 */
    fun serverDir(context: Context, name: String): File =
        serverDir(context, name, InstanceStorage.PRIVATE)

    fun serverDir(context: Context, name: String, storage: InstanceStorage): File =
        File(serversDir(context, storage), sanitizeName(name))

    // —— 公共目录（/storage/emulated/0/MSLX）——

    /** 公共数据根（`/storage/emulated/0/MSLX`）：仅拼路径，真正读写前须已过权限检查。 */
    fun publicRoot(): File =
        File(runCatching { Environment.getExternalStorageDirectory() }.getOrNull() ?: File("/storage/emulated/0"), PUBLIC_ROOT_NAME)

    /**
     * 本机是否可能使用公共目录：仅 Android 11（API 30）及以上。
     * 更低系统上 targetSdk 35 拿不到 legacy 外部存储，写 `/storage/emulated/0` 必然失败，
     * 因此 UI 直接把公共选项置灰而不是给用户一个永远授不上的权限入口。
     */
    fun publicStorageSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** 是否已授予「所有文件访问」（MANAGE_EXTERNAL_STORAGE）；不满足系统条件时恒为 false。 */
    fun publicStorageGranted(): Boolean =
        publicStorageSupported() && runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    /**
     * 「所有文件访问」授权页 Intent（系统设置里按应用粒度授权）。
     * 个别 ROM 上按包跳转的入口不存在，调用方需对 ActivityNotFoundException 兜底后
     * 再试 [publicStorageSettingsFallbackIntent]。
     */
    fun publicStorageSettingsIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )

    /** 兜底：不指定包的「所有文件访问」总页面（API 30+）。 */
    fun publicStorageSettingsFallbackIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)

    /** 目录名消毒：只保留字母/数字/下划线/点/连字符，中文等其它字符用 `_` 代替。 */
    fun sanitizeName(name: String): String {
        val cleaned = name.trim().replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]"), "_")
        return cleaned.trim('.', '_', '-').ifBlank { "server" }.take(48)
    }

    /**
     * 由实例目录反推存放位置：纯路径判断，不需要 Context（[ServerFiles.complete] 会调）。
     * 不在公共根下一律按私有处理（旧实例即属此类）。
     */
    fun storageOf(dir: File): InstanceStorage {
        val base = runCatching { publicRoot().canonicalPath }.getOrDefault("")
        val path = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        return if (base.isNotBlank() && path.startsWith(base + File.separator)) InstanceStorage.PUBLIC else InstanceStorage.PRIVATE
    }

    /**
     * 分配一个在**两个根目录下都不冲突**的实例目录名（保证 `ServerRef.local(目录名)` 唯一）。
     * 已存在时依次追加 `-2`、`-3`…（消毒后的名字本身不含非法字符，无需再消毒）。
     */
    fun allocateDirName(context: Context, name: String): String {
        val base = sanitizeName(name)
        var candidate = base
        var index = 2
        while (existsServerDir(context, candidate)) {
            candidate = "$base-$index"
            index++
        }
        return candidate
    }

    /** 公共目录下的实例根（`/storage/emulated/0/MSLX/servers`），拼路径不需要 Context。 */
    fun publicServersDir(): File = File(publicRoot(), "servers")

    /**
     * 创建前的路径预览（不需要 Context，创建向导的确认页/摘要用）：
     * 公共目录给出能在文件管理器里直接打开的绝对路径；私有目录只给相对标识，避免暴露一长串沙箱路径。
     */
    fun previewPath(storage: InstanceStorage, name: String): String {
        val dirName = sanitizeName(name)
        return if (storage == InstanceStorage.PUBLIC) File(publicServersDir(), dirName).path else "$ROOT_NAME/servers/$dirName"
    }

    /** 目录名是否已在任一根目录下被占用。 */
    fun existsServerDir(context: Context, dirName: String): Boolean =
        InstanceStorage.entries.any { serverDir(context, dirName, it).isDirectory }

    /** 按目录名解析实例目录（找不到返回 null；同名时私有优先，与历史行为一致）。 */
    fun resolveServerDir(context: Context, dirName: String): Pair<InstanceStorage, File>? {
        val safe = sanitizeName(dirName)
        // 目录名可能被消毒改写（如用户手建的奇怪目录），故按原始名与消毒名各试一次
        return sequenceOf(dirName, safe).distinct().firstOrNull { candidate ->
            candidate.isNotBlank() && !candidate.contains("..") && !candidate.contains(File.separator)
        }?.let { name ->
            InstanceStorage.entries.firstOrNull { serverDir(context, name, it).isDirectory }?.let { it to serverDir(context, name, it) }
        }
    }

    /** 展示用的路径：私有目录给相对路径，公共目录给绝对路径（用户能在文件管理器里找到）。 */
    fun displayPath(context: Context, file: File): String {
        val base = runCatching { publicRoot().canonicalPath }.getOrDefault("")
        val path = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        if (base.isNotBlank() && path.startsWith(base + File.separator)) return path
        return runCatching { file.relativeTo(root(context)).path }.getOrDefault(file.absolutePath)
    }

    fun ensureBase(context: Context) {
        ensureBase(context, InstanceStorage.PRIVATE)
    }

    /** 建好指定位置的数据目录；公共目录未授权时只记日志（调用方据 [publicStorageGranted] 决定是否回退）。 */
    fun ensureBase(context: Context, storage: InstanceStorage) {
        serversDir(context, storage).mkdirs()
        if (storage == InstanceStorage.PRIVATE) {
            runtimeDir(context).mkdirs()
            cacheDir(context).mkdirs()
        }
    }

    /** 数据目录占用（MB），供 UI 展示；含已存在的公共目录实例。 */
    fun usedMb(context: Context): Long {
        val private = runCatching {
            root(context).walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1024 / 1024
        }.getOrDefault(0)
        val publicUsed = runCatching {
            val publicServers = serversDir(context, InstanceStorage.PUBLIC)
            if (publicServers.isDirectory) {
                publicServers.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1024 / 1024
            } else {
                0
            }
        }.getOrDefault(0)
        return private + publicUsed
    }

    /**
     * 旧布局迁移：
     *  - `filesDir/jre/<runtimeId>` → `filesDir/mslx/runtime/<runtimeId>`
     *  - `filesDir/worlds/<实例名>/` → `filesDir/mslx/servers/<实例名>/`（同名已存在则跳过）
     *  - 旧 `filesDir/worlds` 根目录下的核心 jar → 移到对应实例目录并改名为 server.jar
     * 迁移失败只记日志，不影响启动。
     */
    fun migrateLegacy(context: Context) {
        runCatching {
            ensureBase(context)
            // JRE
            val legacyJre = File(context.filesDir, LEGACY_JRE_DIR)
            if (legacyJre.isDirectory && File(legacyJre, LocalJreManager.RUNTIME_ID).isDirectory) {
                val src = File(legacyJre, LocalJreManager.RUNTIME_ID)
                val dst = jreHome(context)
                if (!dst.exists()) {
                    dst.parentFile?.mkdirs()
                    if (src.renameTo(dst)) {
                        AppLogger.i("LocalStorage", "已迁移旧 JRE 目录到 ${displayPath(context, dst)}")
                    }
                }
            }
            // 实例目录 + 散放的核心 jar
            val legacyWorlds = File(context.filesDir, LEGACY_WORLDS_DIR)
            if (legacyWorlds.isDirectory) {
                val expectedServerDir = sanitizeName(ServerFiles.DEFAULT_SERVER_NAME)
                legacyWorlds.listFiles()?.forEach { entry ->
                    val target = File(serversDir(context), entry.name)
                    if (entry.isDirectory && !target.exists()) {
                        if (entry.renameTo(target)) {
                            AppLogger.i("LocalStorage", "已迁移旧实例目录 ${entry.name} → ${displayPath(context, target)}")
                        }
                    } else if (entry.isFile && entry.extension.equals("jar", ignoreCase = true)) {
                        // 旧布局把核心下载在 worlds 根：搬进实例目录并统一命名 server.jar
                        val dir = File(serversDir(context), expectedServerDir)
                        dir.mkdirs()
                        val jar = File(dir, ServerFiles.SERVER_JAR_NAME)
                        if (!jar.exists() && entry.renameTo(jar)) {
                            AppLogger.i("LocalStorage", "已迁移旧核心 ${entry.name} → ${displayPath(context, jar)}")
                        }
                    }
                }
            }
        }.onFailure { AppLogger.w("LocalStorage", "旧目录迁移失败（忽略）", it) }
    }

    /** 时间戳（instance.json 用）。 */
    fun nowIso(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
}
