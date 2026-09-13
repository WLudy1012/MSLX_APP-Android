package com.mslx.console.data.localengine

import android.content.Context
import com.mslx.console.data.AppLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本机开服的**数据目录**（对应上游 MSLX Daemon 的 `GetAppDataPath()`）。
 *
 * 布局（全部在应用私有目录下，外部存储是 noexec 且会随卸载清理）：
 * ```
 * filesDir/mslx/                    <- 数据根目录（AppData）
 *   runtime/jre17/                  <- Java 运行时（= java.home，含 lib/server/libjvm.so）
 *   servers/<实例名>/                <- 一个实例一个目录（对应 daemon 的 Servers/<id>）
 *     server.jar                    <- 服务端核心（下载后固定命名，同 daemon 约定）
 *     server.properties             <- 补全的服务端配置
 *     eula.txt                      <- 已同意 EULA（格式同 daemon）
 *     instance.json                 <- 实例元数据（核心/版本/内存/JVM 参数/创建时间…）
 *     ops.json / whitelist.json / banned-*.json
 *     logs/ plugins/ mods/ world/   <- 服务端运行期目录（按核心类型预建）
 *   cache/                          <- 下载临时文件
 * ```
 *
 * 之前把 JRE 直接放在 `filesDir/jre`、核心放在 `filesDir/worlds/` 是临时做法，
 * [migrateLegacy] 会把旧布局迁移到新结构（不删用户数据）。
 */
object LocalStorage {

    private const val ROOT_NAME = "mslx"
    private const val LEGACY_JRE_DIR = "jre"
    private const val LEGACY_WORLDS_DIR = "worlds"

    /** 数据根目录。 */
    fun root(context: Context): File = File(context.filesDir, ROOT_NAME)

    /** 运行时根目录（对应 daemon 的 Tools/Java）。 */
    fun runtimeDir(context: Context): File = File(root(context), "runtime")

    /** JRE 安装目录（= java.home）。 */
    fun jreHome(context: Context): File = File(runtimeDir(context), LocalJreManager.RUNTIME_ID)

    /** 实例根目录（对应 daemon 的 Servers）。 */
    fun serversDir(context: Context): File = File(root(context), "servers")

    /** 下载缓存目录（对应 daemon 的 Temp/Uploads）。 */
    fun cacheDir(context: Context): File = File(root(context), "cache")

    /** 某个实例的目录；[name] 会被消毒成合法目录名。 */
    fun serverDir(context: Context, name: String): File =
        File(serversDir(context), sanitizeName(name))

    /** 目录名消毒：只保留字母/数字/下划线/点/连字符，中文等其它字符用 `_` 代替。 */
    fun sanitizeName(name: String): String {
        val cleaned = name.trim().replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]"), "_")
        return cleaned.trim('.', '_', '-').ifBlank { "server" }.take(48)
    }

    /** 展示用的相对路径（相对数据根目录）。 */
    fun displayPath(context: Context, file: File): String =
        runCatching { file.relativeTo(root(context)).path }.getOrDefault(file.absolutePath)

    fun ensureBase(context: Context) {
        serversDir(context).mkdirs()
        runtimeDir(context).mkdirs()
        cacheDir(context).mkdirs()
    }

    /** 数据目录占用（MB），供 UI 展示。 */
    fun usedMb(context: Context): Long =
        runCatching { root(context).walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1024 / 1024 }
            .getOrDefault(0)

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
