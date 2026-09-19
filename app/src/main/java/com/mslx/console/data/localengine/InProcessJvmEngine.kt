package com.mslx.console.data.localengine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 进程内 JVM 引擎（基线）：包装现有 [LocalJvmLauncher]，把它适配到统一的 [LocalEngine] 接口。
 *
 * 沿用进程内 JVM 的固有限制：一个 App 进程只能创建一次 JVM，停止后需完全重启 App 才能再起
 * （见 [LocalJvmLauncher] 顶部说明）。exec 语义见 [ShizukuShellEngine]。
 */
class InProcessJvmEngine(
    jreHome: File,
    serverJar: File,
    workDir: File,
    minMemM: Int,
    maxMemM: Int,
    extraArgs: List<String> = emptyList(),
    useSerialGc: Boolean = true,
    runtime: LocalJreManager.JavaRuntime = LocalJreManager.DEFAULT_RUNTIME,
) : LocalEngine {

    override val kind: EngineKind = EngineKind.IN_PROCESS

    private val launcher = LocalJvmLauncher(
        jreHome = jreHome,
        serverJar = serverJar,
        workDir = workDir,
        minMemM = minMemM,
        maxMemM = maxMemM,
        extraArgs = extraArgs,
        useSerialGc = useSerialGc,
        runtime = runtime,
    )

    override val logs: Flow<String> = launcher.logs

    private val _running = MutableStateFlow(false)
    override val running: StateFlow<Boolean> = _running.asStateFlow()

    override val exitCode: Int?
        get() = launcher.exitCode

    override var onExit: ((Int) -> Unit)? = null

    override suspend fun start(): Boolean {
        launcher.onExit = { code ->
            _running.value = false
            onExit?.invoke(code)
        }
        val ok = launcher.start()
        _running.value = ok && launcher.running
        return ok
    }

    override fun sendCommand(command: String) = launcher.sendCommand(command)

    override fun stop() = launcher.stop()
}
