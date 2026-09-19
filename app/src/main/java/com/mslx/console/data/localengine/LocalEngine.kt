package com.mslx.console.data.localengine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 本机开服引擎类型。 */
enum class EngineKind {
    /**
     * 进程内 JVM（dlopen `libjvm.so` + `JNI_CreateJavaVM`，见 [LocalJvmLauncher]）。
     * 基线模式，无需额外授权；但受 JNI 限制**全局仅 1 个**，且停止后需完全重启 App 才能再起。
     */
    IN_PROCESS,

    /**
     * Shizuku/ADB shell 下 exec 真正的 `bin/java` 子进程（见 [ShizukuShellEngine]）。
     * 增强模式：多实例并发、可正常启停重启、跨 Java 版本；需用户安装并授权 Shizuku。
     */
    SHIZUKU,
}

/**
 * 本机开服引擎抽象：屏蔽「进程内 JVM」与「Shizuku exec 子进程」两种运行方式的差异，
 * 让 [LocalServerRuntime] 只面向统一接口管理实例（启动 / 停止 / 控制台输入 / 日志 / 退出码）。
 *
 * 两个实现：
 *  - [InProcessJvmEngine]：包装 [LocalJvmLauncher]（基线）。
 *  - [ShizukuShellEngine]：经 [ShizukuController] 在 shell 权限下 exec `bin/java`（增强）。
 */
interface LocalEngine {

    /** 引擎类型（供上层决定是否提示「需重启 App」等进程内语义）。 */
    val kind: EngineKind

    /** 服务端 stdout/stderr 行流（引擎内部已合流为可读日志行）。 */
    val logs: Flow<String>

    /** 是否正在运行。 */
    val running: StateFlow<Boolean>

    /** 最近一次退出码；尚未退出为 null。 */
    val exitCode: Int?

    /** 服务端退出回调（在工作线程调用，实现方需自行切线程）。 */
    var onExit: ((Int) -> Unit)?

    /** 启动服务端；返回 false 时 [logs] 里已有失败原因。（挂起：exec 引擎需先 stage 文件） */
    suspend fun start(): Boolean

    /** 向服务端 stdin 发送一条控制台命令（如 stop / say hi）。 */
    fun sendCommand(command: String)

    /** 优雅停止：先发 stop；exec 模式超时兜底 kill。 */
    fun stop()
}
