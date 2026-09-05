package com.mslx.console.data.localengine

import com.mslx.console.data.AppLogger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.File
import java.io.PrintWriter
import java.util.concurrent.TimeUnit

/**
 * 本机开服引擎（P0 实验）：直接以 ProcessBuilder 启动一个 Java 服务端进程。
 *
 * 前提（P0）：设备上需有可执行的 Android(bionic) JRE（参考 PojavLauncher 方案），
 * 由用户在实验页填写 java 可执行文件与 server.jar 路径。
 * 后续 P1 会接入 JRE 自动下载（Android 专用源）与实例管理。
 */
class LocalServerProcess(
    private val javaBin: File,
    private val serverJar: File,
    private val workDir: File,
    private val minMemM: Int = 1024,
    private val maxMemM: Int = 2048,
) {
    private val _logs = Channel<String>(capacity = Channel.BUFFERED)
    val logs: Flow<String> = _logs.receiveAsFlow()

    private var process: Process? = null
    private var input: PrintWriter? = null
    private var readerThread: Thread? = null

    @Volatile
    var running: Boolean = false
        private set

    /** 启动服务端：写入 eula.txt=true（若缺失），拉起 java 进程并转发 stdout/stderr。 */
    fun start(): Boolean {
        if (running) return true
        if (!javaBin.isFile || !serverJar.isFile) {
            AppLogger.e("LocalEngine", "java 或 server.jar 不存在: java=${javaBin.path} jar=${serverJar.path}")
            return false
        }
        workDir.mkdirs()
        val eula = File(workDir, "eula.txt")
        if (!eula.exists()) {
            eula.writeText(
                "#By changing the setting below to TRUE you are indicating your agreement to our EULA (https://aka.ms/MinecraftEULA).\n#MSLX-Android auto agreed\neula=true\n",
            )
        }
        return runCatching {
            val cmd = listOf(
                javaBin.absolutePath,
                "-Xms${minMemM}M",
                "-Xmx${maxMemM}M",
                "-jar",
                serverJar.absolutePath,
                "nogui",
            )
            val p = ProcessBuilder(cmd)
                .directory(workDir)
                .redirectErrorStream(true)
                .start()
            process = p
            input = PrintWriter(p.outputStream, true)
            running = true
            readerThread = Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        if (running) _logs.trySend(line)
                    }
                } catch (e: Exception) {
                    if (running) AppLogger.w("LocalEngine", "读取服务端日志中断", e)
                }
            }.apply { name = "local-server-log"; isDaemon = true; start() }
            AppLogger.i("LocalEngine", "服务端已启动 cmd=${cmd.joinToString(" ")}")
            true
        }.getOrElse { e ->
            AppLogger.e("LocalEngine", "启动服务端失败", e)
            running = false
            false
        }
    }

    /** 停止：先向 stdin 发 stop（Minecraft 优雅关服），超时后强制结束。 */
    fun stop() {
        if (!running) return
        val p = process ?: return
        runCatching { input?.println("stop"); input?.flush() }
        try {
            if (!p.waitFor(8, TimeUnit.SECONDS)) {
                p.destroy()
                if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
            }
        } catch (_: InterruptedException) {
            p.destroyForcibly()
        }
        running = false
        process = null
        input = null
        _logs.close()
        AppLogger.i("LocalEngine", "服务端已停止")
    }
}
