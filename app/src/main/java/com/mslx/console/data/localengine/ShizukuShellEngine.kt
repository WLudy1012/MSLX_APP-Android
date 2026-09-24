package com.mslx.console.data.localengine

import android.content.Context
import com.mslx.console.data.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Shizuku/ADB exec 引擎（增强）：把 JRE stage 到 `/data/local/tmp/mslx` 后，
 * 经 [ShizukuController] 在 shell 权限下 exec 真正的 `bin/java` 子进程。
 *
 * 相比进程内 JVM（[InProcessJvmEngine]）：
 *  - 支持多实例并发（各自独立子进程）；
 *  - 可正常启停/重启（子进程退出即释放，无需重启 App）；
 *  - 跨 Java 版本（8/17/21/25 用各自的 `bin/java`）；
 *  - 官方 bundler 壳可直接 `java -jar`（真 launcher 能 fork 子进程）。
 *
 * 工作目录分两种：
 *  - **私有目录实例**：权威副本在 `filesDir/mslx/servers/<dir>`，shell 读不到，所以启动前
 *    [ShizukuController.pushInstance] 全量推一次、退出后 [ShizukuController.syncInstanceBack] 回同步；
 *  - **公共目录实例**：目录本身 shell 可读写，**直接以公共路径为 cwd exec**，省掉两遍全量复制
 *    （大存档启动时间从数十秒降到秒级）；仅 tmp/user.home 等临时项落在 `/data/local/tmp` 的
 *    暂存区。探测到 shell 写不了该路径（如部分 ROM 的 FUSE 限制）时自动退回上一种模式。
 */
class ShizukuShellEngine(
    private val context: Context,
    private val runtime: LocalJreManager.JavaRuntime,
    private val dirName: String,
    private val localInstanceDir: File,
    private val serverJarName: String = ServerFiles.SERVER_JAR_NAME,
    private val minMemM: Int = 1024,
    private val maxMemM: Int = 2048,
    private val extraArgs: List<String> = emptyList(),
    private val useSerialGc: Boolean = true,
) : LocalEngine {

    override val kind: EngineKind = EngineKind.SHIZUKU

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _logs = Channel<String>(Channel.BUFFERED)
    override val logs: Flow<String> = _logs.receiveAsFlow()

    private val _running = MutableStateFlow(false)
    override val running: StateFlow<Boolean> = _running.asStateFlow()

    @Volatile
    override var exitCode: Int? = null

    @Volatile
    override var onExit: ((Int) -> Unit)? = null

    @Volatile
    private var process: RemoteShellProcess? = null

    @Volatile
    private var stdin: OutputStream? = null

    /** 本次启动是否把实例 stage 到了 /data/local/tmp（公共目录直跑时为 false，退出后不回同步）。 */
    @Volatile
    private var staged = false

    /** 启动服务端（挂起：先 stage 文件再 exec；调用方需在 IO 线程）。返回 false 时 [logs] 里已有失败原因。 */
    override suspend fun start(): Boolean {
        if (_running.value) return true
        if (!ShizukuController.isReady()) {
            emit("Shizuku 未就绪或未授权，无法使用增强模式")
            return false
        }
        emit("增强模式：以 shell 权限 exec ${runtime.label} 子进程")

        val localJre = LocalJreManager.jreHome(context, runtime)
        val javaRel = ShizukuController.javaBinRel(localJre)
        if (javaRel == null) {
            emit("${runtime.label} 缺少 bin/java，无法 exec")
            return false
        }

        emit("准备运行时（首次会复制到 /data/local/tmp，可能较慢）…")
        val remoteJre = ShizukuController.ensureRuntimeStaged(context, runtime).getOrElse {
            emit("运行时 staging 失败：${it.message}")
            return false
        }
        val javaBin = "$remoteJre/$javaRel"

        // 工作目录：公共目录且 shell 可写 → 直跑；否则（私有目录/写不了）→ stage 到 /data/local/tmp
        val publicDir = LocalStorage.storageOf(localInstanceDir) == InstanceStorage.PUBLIC
        val workDir: String
        val scratchDir: String
        if (publicDir) {
            val directPath = localInstanceDir.absolutePath
            if (ShizukuController.canWriteRemotePath(directPath)) {
                workDir = directPath
                scratchDir = ShizukuController.ensureScratch(dirName).getOrElse {
                    emit("暂存目录准备失败：${it.message}")
                    return false
                }
                staged = false
                emit("公共目录直跑：不复制实例文件（临时项→$scratchDir）")
            } else {
                emit("shell 写不了公共目录，退回传统模式（复制到 /data/local/tmp 运行，结束后回同步）")
                workDir = stageInstanceOrFail() ?: return false
                scratchDir = workDir
                staged = true
            }
        } else {
            workDir = stageInstanceOrFail() ?: return false
            scratchDir = workDir
            staged = true
        }

        val opts = buildOptions(workDir, scratchDir)
        val cmd = arrayOf(javaBin) + opts + arrayOf("-jar", serverJarName, "nogui")
        emit("启动：${cmd.joinToString(" ")}")

        // Android linker 不认 JDK 自带的 $ORIGIN/../lib 搜索路径，必须显式给
        // LD_LIBRARY_PATH，否则 exec 会立即失败："libjli.so" not found（rc=1）。
        // Java 8 的库在 `lib/<arch>[/jli]`（如 lib/aarch64/jli/libjli.so），
        // 17/21/25 平铺在 `lib[/jli]`；不存在的目录会被 linker 忽略，全部列上。
        val libDirs = listOf(
            "lib", "lib/jli",
            "lib/aarch64", "lib/aarch64/jli",
            "lib/amd64", "lib/amd64/jli",
        ).joinToString(":") { "$remoteJre/$it" }
        val env = buildList {
            add("LD_LIBRARY_PATH=$libDirs")
            // 旧版 OpenJDK（如 jre17 的 2021 构建）在 Android 12+ 上会被 Scudo 的堆指针
            // 打标签校验直接 SIGABRT（"Pointer tag ... was truncated"），垫片在 java main
            // 之前关掉本进程打标签；无垫片（未内嵌该 ABI）时退回原样启动。
            val shim = ShizukuController.ensureShimStaged(context)
            if (shim != null) {
                add("LD_PRELOAD=$shim")
                emit("已启用堆打标签垫片（兼容旧版 JRE）")
            }
        }
        val p = runCatching { ShizukuController.newProcess(cmd, env.toTypedArray(), workDir) }.getOrElse {
            emit("exec 失败：${it.message}")
            return false
        }
        process = p
        stdin = p.outputStream
        _running.value = true

        scope.launch { pumpLines(p.inputStream) }
        scope.launch { pumpLines(p.errorStream) }
        scope.launch {
            val code = runCatching { p.waitFor() }.getOrElse { -1 }
            exitCode = code
            _running.value = false
            emit(if (code == 0) "服务端已退出" else "服务端退出（rc=$code）")
            if (staged) {
                val synced = runCatching { ShizukuController.syncInstanceBack(dirName, localInstanceDir) }
                    .getOrElse { false }
                emit(if (synced) "已回同步 world/logs/配置到应用目录" else "回同步失败，本次世界改动可能未保存")
                AppLogger.i(TAG, "exec 子进程结束 rc=$code，回同步=$synced")
            } else {
                emit("世界/日志已直接写在公共目录，无需回同步")
                AppLogger.i(TAG, "exec 子进程结束 rc=$code（公共目录直跑）")
            }
            onExit?.invoke(code)
        }
        return true
    }

    /** 把实例全量 stage 到 `/data/local/tmp/mslx/servers/<dir>`，返回远端目录（失败返回 null，原因已进日志）。 */
    private suspend fun stageInstanceOrFail(): String? {
        emit("同步实例文件到运行目录…")
        return ShizukuController.pushInstance(localInstanceDir, dirName).getOrElse {
            emit("实例 staging 失败：${it.message}")
            null
        }
    }

    override fun sendCommand(command: String) {
        val out = stdin ?: return
        runCatching {
            out.write((command + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
        }.onFailure { AppLogger.w(TAG, "发送控制台命令失败: $command", it) }
    }

    /** 优雅停止：先发 stop，轮询等待 8s；未退出则强杀子进程（暂存模式仍会回同步）。 */
    override fun stop() {
        val p = process ?: return
        if (!_running.value) return
        emit("> stop")
        sendCommand("stop")
        scope.launch {
            // 用 alive() 轮询而非阻塞 waitFor，避免与 watcher 的 waitFor 并发同一远端进程
            var waited = 0L
            while (waited < 8_000L && runCatching { p.alive() }.getOrDefault(false)) {
                delay(500)
                waited += 500
            }
            if (runCatching { p.alive() }.getOrDefault(false)) {
                emit("优雅停止超时，强制结束子进程")
                runCatching { p.destroy() }
            }
        }
    }

    /** exec 模式跑的是真 launcher，java.home/classpath/library.path 由其自动设置，这里只给业务参数。 */
    private fun buildOptions(workDir: String, scratchDir: String): List<String> = buildList {
        add("-Xms${minMemM}M")
        add("-Xmx${maxMemM}M")
        if (useSerialGc) add("-XX:+UseSerialGC")
        add("-Dfile.encoding=UTF-8")
        // 临时目录/用户目录始终落在 shell 可写的暂存区：公共目录走 FUSE，部分设备对
        // 文件锁与原子重命名支持不全（存档保存、native 解压都会踩到）。
        add("-Djava.io.tmpdir=$scratchDir/tmp")
        add("-Duser.home=$scratchDir/home")
        add("-Djava.library.path=$scratchDir/natives")
        add("-Djava.awt.headless=true")
        add("-Dterminal.jline=false")
        add("-Dterminal.ansi=true")
        add("-Dlog4j2.formatMsgNoLookups=true")
        // 内嵌 JRE 是上游自制构建，java.version 形如 `17-internal`（含 `-`），Paper 会把它
        // 当成非 GA 预发布版本并拒绝启动（Unsupported Java detected）；这是其官方跳过开关。
        add("-DPaper.IgnoreJavaVersion=true")
        addAll(extraArgs)
    }

    private suspend fun pumpLines(input: InputStream) = withContext(Dispatchers.IO) {
        runCatching {
            input.bufferedReader(Charsets.UTF_8).forEachLine { _logs.trySend(it) }
        }.onFailure { AppLogger.w(TAG, "读取子进程输出中断", it) }
    }

    private fun emit(line: String) {
        _logs.trySend(line)
        AppLogger.i(TAG, "[shizuku] $line")
    }

    private companion object {
        const val TAG = "LocalEngine"
    }
}
