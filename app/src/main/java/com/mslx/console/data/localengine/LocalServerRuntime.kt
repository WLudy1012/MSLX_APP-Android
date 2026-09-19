package com.mslx.console.data.localengine

import android.content.Context
import com.mslx.console.data.AppLogger
import com.mslx.console.localengine.NativeVm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本机服务端的**进程级**运行时。
 *
 * 为什么不是 ViewModel 持有：服务端跑在 App 进程内（见 LocalJvmLauncher），
 * 用户离开界面后要能继续运行、回到界面要能接着看日志，所以运行实例与日志
 * 必须挂在进程级单例上，由 `LocalServerService` 前台服务保证进程不被回收。
 */
object LocalServerRuntime {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _jvmCreated = MutableStateFlow(false)
    val jvmCreated: StateFlow<Boolean> = _jvmCreated.asStateFlow()

    private val _startedOnce = MutableStateFlow(false)

    /** 是否已经成功启动过（用于"停止后需重启 App"的提示）。 */
    val startedOnce: StateFlow<Boolean> = _startedOnce.asStateFlow()

    @Volatile
    private var engine: LocalEngine? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    var currentServerName: String = ""
        private set

    /** 当前运行实例的目录名（`mslx/servers/<dirName>`）；未启动为空。 */
    @Volatile
    var currentDirName: String = ""
        private set

    /** 当前引擎类型（进程内 / Shizuku exec）；null 表示尚未启动过。 */
    val activeKind: EngineKind?
        get() = engine?.kind

    /** 向当前运行实例发送一条控制台命令。 */
    fun sendCommand(command: String) {
        engine?.sendCommand(command)
    }

    fun append(line: String) {
        _logs.update { (it + line).takeLast(2000) }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    fun refreshJvmState() {
        _jvmCreated.value = NativeVm.isJvmCreated()
    }

    /**
     * 启动服务端。[keepAlive] 为 true 时同时拉起前台服务（常驻通知），
     * 使 App 退到后台/从最近任务划掉后进程仍存活、服务端不掉线。
     */
    suspend fun start(
        context: Context,
        jreHome: File,
        serverJar: File,
        workDir: File,
        serverName: String,
        minMemM: Int,
        maxMemM: Int,
        extraArgs: List<String>,
        useSerialGc: Boolean,
        keepAlive: Boolean,
        runtime: LocalJreManager.JavaRuntime = LocalJreManager.DEFAULT_RUNTIME,
        useExec: Boolean = false,
    ): Result<Unit> = runCatching {
        val app = context.applicationContext
        appContext = app
        if (engine?.running?.value == true) return@runCatching
        // 引擎选择：增强模式开启且 Shizuku 已授权 → exec 子进程；否则回退进程内 JVM 基线
        val exec = useExec && ShizukuController.isReady()
        val chosen: LocalEngine = if (exec) {
            ShizukuShellEngine(
                context = app,
                runtime = runtime,
                dirName = workDir.name,
                localInstanceDir = workDir,
                minMemM = minMemM,
                maxMemM = maxMemM,
                extraArgs = extraArgs,
                useSerialGc = useSerialGc,
            )
        } else {
            InProcessJvmEngine(
                jreHome = jreHome,
                serverJar = serverJar,
                workDir = workDir,
                minMemM = minMemM,
                maxMemM = maxMemM,
                extraArgs = extraArgs,
                useSerialGc = useSerialGc,
                runtime = runtime,
            )
        }
        engine = chosen
        currentServerName = serverName
        currentDirName = workDir.name
        scope.launch { chosen.logs.collect { append(it) } }
        chosen.onExit = { code ->
            _running.value = false
            refreshJvmState()
            AppLogger.i("LocalEngine", "服务端退出 rc=$code（${chosen.kind}），停止前台服务")
            if (keepAlive) runCatching { LocalServerService.stop(app) }
        }
        // 前台服务必须在引擎启动前就位：staging/exec 可能耗时数秒，若等服务端起来后再
        // startForegroundService，会撞上系统前台服务时限；且服务端秒退时 onExit 的 stop
        // 会与尾部的 start 竞态（两次调用跨线程，AMS 侧顺序无保证），触发
        // ForegroundServiceDidNotStartInTimeException 直接打崩 App。
        if (keepAlive) {
            runCatching { LocalServerService.start(app) }
                .onFailure { AppLogger.w("LocalEngine", "启动前台服务失败（不影响服务端运行）", it) }
        }
        val ok = withContext(Dispatchers.IO) { chosen.start() }
        if (!ok) {
            engine = null
            if (keepAlive) runCatching { LocalServerService.stop(app) }
            throw IllegalStateException("启动失败，详见日志")
        }
        _running.value = true
        _startedOnce.value = true
        refreshJvmState()
    }

    /** 优雅停止：向服务端 stdin 发 stop（exec 模式超时兜底 kill）。 */
    fun stop() {
        val current = engine ?: return
        scope.launch { withContext(Dispatchers.IO) { current.stop() } }
    }

    /** 用户离开 App/任务被划掉时不做任何事：前台服务会让进程继续存活。 */
    fun detach() {
        appContext = null
    }
}
