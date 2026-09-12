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
    private var launcher: LocalJvmLauncher? = null

    @Volatile
    private var appContext: Context? = null

    @Volatile
    var currentServerName: String = ""
        private set

    /** 当前运行实例（用于发送控制台命令）。 */
    val activeLauncher: LocalJvmLauncher?
        get() = launcher

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
    ): Result<Unit> = runCatching {
        val app = context.applicationContext
        appContext = app
        if (launcher?.running == true) return@runCatching
        val engine = LocalJvmLauncher(
            jreHome = jreHome,
            serverJar = serverJar,
            workDir = workDir,
            minMemM = minMemM,
            maxMemM = maxMemM,
            extraArgs = extraArgs,
            useSerialGc = useSerialGc,
        )
        launcher = engine
        currentServerName = serverName
        scope.launch { engine.logs.collect { append(it) } }
        engine.onExit = { code ->
            _running.value = false
            refreshJvmState()
            AppLogger.i("LocalEngine", "服务端退出 rc=$code，停止前台服务")
            if (keepAlive) runCatching { LocalServerService.stop(app) }
        }
        val ok = withContext(Dispatchers.IO) { engine.start() }
        if (!ok) throw IllegalStateException("启动失败，详见日志")
        _running.value = true
        _startedOnce.value = true
        refreshJvmState()
        if (keepAlive) {
            runCatching { LocalServerService.start(app) }
                .onFailure { AppLogger.w("LocalEngine", "启动前台服务失败（不影响服务端运行）", it) }
        }
    }

    /** 优雅停止：向服务端 stdin 发 stop。 */
    fun stop() {
        val engine = launcher ?: return
        scope.launch { withContext(Dispatchers.IO) { engine.stop() } }
    }

    /** 用户离开 App/任务被划掉时不做任何事：前台服务会让进程继续存活。 */
    fun detach() {
        appContext = null
    }
}
