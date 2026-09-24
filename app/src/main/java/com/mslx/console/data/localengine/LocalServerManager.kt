package com.mslx.console.data.localengine

import android.content.Context
import com.mslx.console.data.AppSettings
import com.mslx.console.data.SettingsStore
import com.mslx.console.localengine.NativeVm
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * 本机开服协调器（挂在 [com.mslx.console.data.AppContainer] 上，进程级唯一）。
 *
 * 职责：把「按目录名启动某个本机实例」的公共逻辑（解析持久化的 [LocalInstanceMeta]、
 * 选择引擎、进程内 JVM 约束、启动前补全文件）收敛到一处，供统一控制台
 * （[com.mslx.console.ui.console.LocalConsoleViewModel]）与各创建/管理入口复用；
 * 运行状态本身仍委托给进程级单例 [LocalServerRuntime]（当前为单活跃实例）。
 */
class LocalServerManager(
    context: Context,
    private val settingsStore: SettingsStore,
) {

    private val app: Context = context.applicationContext

    /** 是否正在运行（进程级单活跃实例）。 */
    val running: StateFlow<Boolean> = LocalServerRuntime.running

    /** 运行日志行流。 */
    val logs: StateFlow<List<String>> = LocalServerRuntime.logs

    /** 当前引擎类型（进程内 / Shizuku exec）。 */
    val activeKind: EngineKind? get() = LocalServerRuntime.activeKind

    /** 当前运行实例目录名（未运行为空）。 */
    val currentDirName: String get() = LocalServerRuntime.currentDirName

    /** 指定实例是否正在运行。 */
    fun isRunning(dirName: String): Boolean =
        running.value && currentDirName == dirName

    /** 向当前运行实例发送控制台命令。 */
    fun sendCommand(command: String) = LocalServerRuntime.sendCommand(command)

    fun clearLogs() = LocalServerRuntime.clearLogs()

    fun stop() = LocalServerRuntime.stop()

    /** 是否已开启增强模式（设置开关 + Shizuku 已授权就绪）。 */
    suspend fun enhancedReady(): Boolean {
        val settings = runCatching { settingsStore.settingsFlow.first() }.getOrDefault(AppSettings())
        return settings.localUseShizuku && ShizukuController.isReady()
    }

    /**
     * 启动指定本机实例：读取持久化元数据（[LocalInstanceStore.load]）→ 解析运行时 →
     * 选择引擎（增强模式开启且 Shizuku 就绪走 exec，否则进程内基线）→ 交给 [LocalServerRuntime]。
     * 实例可以在私有或公共目录（由 [LocalInstanceStore.resolve] 反解），公共目录实例在
     * 增强模式下会直接以该路径为工作目录跑，不再复制到 `/data/local/tmp`。
     * 失败原因以 [Result.failure] 返回，消息可直接展示给用户。
     */
    suspend fun start(dirName: String): Result<Unit> {
        if (running.value) {
            return Result.failure(IllegalStateException("已有本机服务端在运行，请先停止后再启动"))
        }
        val (storage, serverDir) = LocalInstanceStore.resolve(app, dirName)
            ?: return Result.failure(IllegalStateException("实例不存在：$dirName"))
        if (storage == InstanceStorage.PUBLIC && !LocalStorage.publicStorageGranted()) {
            return Result.failure(
                IllegalStateException("该实例在公共目录，需先授予「所有文件访问」权限（设置 → 应用 → 特殊应用权限）"),
            )
        }
        val meta = LocalInstanceStore.load(app, dirName)
            ?: return Result.failure(IllegalStateException("实例不存在：$dirName"))
        val serverJar = File(serverDir, ServerFiles.SERVER_JAR_NAME)
        if (!serverJar.isFile) {
            return Result.failure(IllegalStateException("缺少服务端核心（server.jar），请重新创建或下载核心"))
        }
        val runtime = LocalJreManager.runtimeById(meta.runtimeId.ifBlank { null })
        if (!LocalJreManager.isInstalled(app, runtime)) {
            return Result.failure(IllegalStateException("请先在「设置 → 本机运行时与开服设置」安装 ${runtime.label}"))
        }
        // 运行时与核心的游戏版本不匹配：低于要求直接拦下（Paper 之流会拒给 JVM，
        // 用户只能看到一堆看不懂的报错）；高于要求仅在日志里给风险提示。
        val gameVersion = meta.coreVersion.ifBlank { meta.core }
        val (fit, recommendedMajor) = LocalJreManager.fitForGame(runtime, gameVersion)
        if (fit == LocalJreManager.RuntimeFit.TOO_OLD) {
            val needed = recommendedMajor?.let { LocalJreManager.runtimeForMajor(it) }
            val guidance = if (needed == null || !needed.supported) {
                "该 Java 版本暂无可用的 Android 构建，请改选当前运行时能跑的核心版本（1.17–1.20.4 用 Java 17、1.20.5+ 用 Java 21）"
            } else {
                "请到「设置 → 本机运行时与开服设置」安装 ${needed.label}，再把本实例运行时切过去"
            }
            return Result.failure(
                IllegalStateException(
                    "核心需要 Java $recommendedMajor，当前实例用的是 ${runtime.label}：$guidance",
                ),
            )
        }
        val settings = runCatching { settingsStore.settingsFlow.first() }.getOrDefault(AppSettings())
        val useExec = settings.localUseShizuku && ShizukuController.isReady()
        // 进程内 JVM 无法在进程内重建：仅走基线且已创建过 JVM 时拦截（exec 模式不受此限）
        if (!useExec && NativeVm.isJvmCreated()) {
            return Result.failure(
                IllegalStateException("本进程已创建过 JVM，无法原地再启动：请完全退出 App 后重试，或开启增强模式（Shizuku）"),
            )
        }
        // 启动前补全实例文件（幂等、不覆盖用户改动），拿最新的展示名/内存等
        val completed = ServerFiles.complete(serverDir, meta).getOrElse { meta }
        // 跟随全局的实例：启时以「本机运行时与开服设置」的全局默认现算（改全局参数不用逐个改实例）
        val effective = if (completed.inheritGlobal) {
            completed.copy(
                minMemMb = settings.localMinMemMb,
                maxMemMb = settings.localMaxMemMb,
                jvmArgs = settings.localJvmArgs,
                useSerialGc = settings.localUseSerialGc,
                keepAlive = settings.localKeepAlive,
            )
        } else {
            completed
        }
        val extraArgs = effective.jvmArgs.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        LocalServerRuntime.clearLogs()
        if (fit == LocalJreManager.RuntimeFit.TOO_NEW) {
            LocalServerRuntime.append(
                "提示：当前运行时为 ${runtime.label}，该核心推荐 Java $recommendedMajor；" +
                    "旧版核心跑在更新的 Java 上可能启动失败或崩溃（可先试旧版端如 Fabric/Spigot 对应版本）。",
            )
        }
        if (storage == InstanceStorage.PUBLIC) {
            LocalServerRuntime.append("实例位置：${serverDir.absolutePath}（公共目录）")
            if (!useExec) {
                // 共享存储走 FUSE，文件锁与原子重命名不如内部存储可靠，先把退路说清
                LocalServerRuntime.append("提示：未开启增强模式，服务端直接在公共目录读写；若遇到文件锁/重命名类报错，多为共享存储限制所致。")
            }
        }
        return LocalServerRuntime.start(
            context = app,
            jreHome = LocalJreManager.jreHome(app, runtime),
            serverJar = serverJar,
            workDir = serverDir,
            serverName = effective.name.ifBlank { meta.name.ifBlank { dirName } },
            minMemM = effective.minMemMb,
            maxMemM = effective.maxMemMb,
            extraArgs = extraArgs,
            useSerialGc = effective.useSerialGc,
            keepAlive = effective.keepAlive,
            runtime = runtime,
            useExec = useExec,
        )
    }

    /**
     * 原地重启：exec 模式下先优雅停止、等待退出，再启动；进程内模式停止后需完全退出 App，
     * 故对进程内引擎返回失败提示（引导用户开启增强模式）。
     */
    suspend fun restart(dirName: String): Result<Unit> {
        if (!running.value) return start(dirName)
        if (currentDirName != dirName) {
            return Result.failure(IllegalStateException("其它实例正在运行，请先停止"))
        }
        if (activeKind == EngineKind.IN_PROCESS) {
            return Result.failure(
                IllegalStateException("进程内模式停止后需完全退出 App 才能再启动；如需原地重启请开启增强模式（Shizuku）"),
            )
        }
        stop()
        var waited = 0
        while (running.value && waited < 40) {
            delay(500)
            waited++
        }
        return start(dirName)
    }
}
