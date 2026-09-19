package com.mslx.console.data.localengine

import android.os.ParcelFileDescriptor
import com.mslx.console.data.AppLogger
import com.mslx.console.localengine.NativeVm
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 本机开服引擎：在 **App 进程内**创建 JVM 并运行服务端主类。
 *
 * 为什么不是 `ProcessBuilder("java", "-jar", ...)`：Android 10+ 禁止 targetSdk≥29 的应用
 * exec 自己 data 目录里的文件（SELinux），所以"解压 JRE 后 fork/exec bin/java"这条路
 * 在现代 targetSdk 下走不通。PojavLauncher 的做法是 dlopen(libjvm.so) + JNI_CreateJavaVM，
 * 这里沿用同一路线（native 实现在 src/main/cpp/mslxvm.c）。
 *
 * 代价与语义：
 *  - JVM 一旦在本进程创建就无法真正销毁/重建 → 停止后需**完全重启 App** 才能再次启动服务端；
 *  - 服务端与 App 同生命周期（进程被杀即停服）；
 *  - 进程内没有独立 TTY，控制台输入/输出通过 pipe + dup2 接管 fd 0/1/2。
 */
class LocalJvmLauncher(
    private val jreHome: File,
    private val serverJar: File,
    private val workDir: File,
    private val minMemM: Int,
    private val maxMemM: Int,
    private val extraArgs: List<String> = emptyList(),
    private val useSerialGc: Boolean = true,
    private val runtime: LocalJreManager.JavaRuntime = LocalJreManager.DEFAULT_RUNTIME,
) {

    /** 服务端主类返回时回调（工作线程里调用，实现方需自行切线程）。 */
    @Volatile
    var onExit: ((Int) -> Unit)? = null

    private val _logs = Channel<String>(Channel.BUFFERED)
    val logs: Flow<String> = _logs.receiveAsFlow()

    private var cmdPfd: ParcelFileDescriptor? = null
    private var logPfd: ParcelFileDescriptor? = null
    private var cmdOut: FileOutputStream? = null

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var exitCode: Int? = null
        private set

    private var worker: Thread? = null

    /** 启动服务端。返回 false 时 [logs] 里已有失败原因。 */
    fun start(): Boolean {
        if (running) return true
        if (NativeVm.isJvmCreated()) {
            emit("本进程已经创建过 JVM：Android 上 JVM 无法重启，请完全退出 App 后再试")
            return false
        }
        val libjvm = LocalJreManager.libjvmIn(jreHome)
        if (libjvm == null) {
            emit("JRE 不完整：在 ${jreHome.path} 下未找到 libjvm.so（请先安装 ${runtime.label}）")
            return false
        }
        // 真正的 java.home：从 libjvm 回溯到含 lib/ 的目录（兼容 Java 8 的 jre/ 嵌套布局）
        val javaHome = LocalJreManager.resolveJavaHome(jreHome, libjvm)
        if (!serverJar.isFile) {
            emit("服务端核心不存在：${serverJar.path}")
            return false
        }

        workDir.mkdirs()
        writeEula()

        val fds = NativeVm.setupStdio()
        if (fds == null || fds.size < 2) {
            emit("stdio 重定向失败，无法接管服务端控制台")
            return false
        }
        cmdPfd = ParcelFileDescriptor.adoptFd(fds[0])
        logPfd = ParcelFileDescriptor.adoptFd(fds[1])
        cmdOut = FileOutputStream(cmdPfd!!.fileDescriptor)
        startLogPump(FileInputStream(logPfd!!.fileDescriptor))

        val resolved = ServerEntrypoint.resolve(serverJar, File(workDir, ".mslx-runtime")) { emit(it) }
            .getOrElse { e ->
                emit("解析核心入口失败：${e.message}")
                return false
            }
        resolved.note?.let { emit(it) }

        val options = buildOptions(resolved, javaHome)
        emit("启动 JVM：${runtime.label}，-Xmx${maxMemM}M，主类 ${resolved.mainClass}")
        val rc = NativeVm.createJvm(libjvm.absolutePath, javaHome.absolutePath, options.toTypedArray())
        if (rc != 0) {
            emit("JVM 创建失败（rc=$rc），详见 App 日志")
            return false
        }

        running = true
        worker = Thread({
            val code = NativeVm.callMain(resolved.mainClass, arrayOf("nogui"))
            running = false
            exitCode = code
            emit(if (code == 0) "服务端已退出" else "服务端异常退出（rc=$code）")
            AppLogger.i("LocalEngine", "服务端线程结束 rc=$code")
            onExit?.invoke(code)
        }, "mslx-server").apply {
            isDaemon = true
            start()
        }
        AppLogger.i("LocalEngine", "进程内 JVM 服务端已启动：${resolved.mainClass}")
        return true
    }

    /** 向服务端 stdin 发送一条控制台命令（如 stop / say hi）。 */
    fun sendCommand(command: String) {
        val out = cmdOut ?: return
        runCatching {
            out.write((command + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
        }.onFailure { AppLogger.w("LocalEngine", "发送控制台命令失败: $command", it) }
    }

    /** 优雅关服：Minecraft 服务端收到 stdin 的 stop 后会自行保存并退出。 */
    fun stop() {
        if (!running) return
        emit("> stop")
        sendCommand("stop")
    }

    private fun buildOptions(resolved: ServerEntrypoint.Resolved, javaHome: File): List<String> {
        val classpath = resolved.classpath.joinToString(File.pathSeparator) { it.absolutePath }
        val tmpDir = File(workDir, "tmp").apply { mkdirs() }
        return buildList {
            add("-Xms${minMemM}M")
            add("-Xmx${maxMemM}M")
            // Android 上 G1 表现不稳（PojavLauncher 同样固定 SerialGC），可在设置里关闭或用额外参数覆盖
            if (useSerialGc) add("-XX:+UseSerialGC")
            add("-Djava.home=${javaHome.absolutePath}")
            add("-Djava.class.path=$classpath")
            add("-Djava.library.path=${javaHome.absolutePath}/lib:${javaHome.absolutePath}/lib/server")
            add("-Djava.io.tmpdir=${tmpDir.absolutePath}")
            add("-Duser.home=${workDir.absolutePath}")
            add("-Duser.dir=${workDir.absolutePath}")
            add("-Dfile.encoding=UTF-8")
            add("-Djava.awt.headless=true")
            // Paper/Vanilla 控制台在无 TTY 环境下的兼容开关
            add("-Dterminal.jline=false")
            add("-Dterminal.ansi=true")
            add("-Dlog4j2.formatMsgNoLookups=true")
            // 内嵌 JRE 的 java.version 形如 `17-internal`（含 `-`），Paper 会当成非 GA 预发布版
            // 直接退出；这是其官方跳过开关（进程内 JVM 同样需要）。
            add("-DPaper.IgnoreJavaVersion=true")
            addAll(extraArgs)
        }
    }

    /** 首次启动写 eula.txt（Minecraft 服务端要求显式同意）。 */
    private fun writeEula() {
        val eula = File(workDir, "eula.txt")
        if (eula.exists()) return
        runCatching {
            eula.writeText(
                "#By changing the setting below to TRUE you are indicating your agreement to our EULA (https://aka.ms/MinecraftEULA).\n" +
                    "#MSLX-Android auto agreed\n" +
                    "eula=true\n",
            )
        }.onFailure { AppLogger.w("LocalEngine", "写入 eula.txt 失败", it) }
    }

    /** fd 1/2 已指向管道，这里持续读取转发到 [logs]，避免缓冲区写满阻塞服务端。 */
    private fun startLogPump(input: FileInputStream) {
        Thread({
            runCatching {
                input.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    _logs.trySend(line)
                }
            }.onFailure { AppLogger.w("LocalEngine", "读取服务端输出中断", it) }
        }, "mslx-server-log").apply {
            isDaemon = true
            start()
        }
    }

    private fun emit(line: String) {
        _logs.trySend(line)
        AppLogger.i("LocalEngine", "[engine] $line")
    }
}
