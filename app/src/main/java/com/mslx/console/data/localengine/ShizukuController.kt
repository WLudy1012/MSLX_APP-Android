package com.mslx.console.data.localengine

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import com.mslx.console.data.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import rikka.shizuku.Shizuku
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** Shizuku 可用性状态。 */
enum class ShizukuStatus {
    /** 未安装 / 未启动 / binder 未收到。 */
    DEAD,

    /** Shizuku 运行中，但本应用尚未获得授权。 */
    NO_PERMISSION,

    /** 已授权，可 exec。 */
    READY,
}

/** 一次 exec 的结果（仅用于短命令 staging；服务端进程走流式，不用它）。 */
data class ExecResult(val code: Int, val out: String, val err: String) {
    val ok: Boolean get() = code == 0
}

/**
 * Shizuku/ADB 增强引擎的底座：绑定状态、权限申请、`exec` 子进程、以及经「tar 管道」在
 * 应用私有目录与 `/data/local/tmp/mslx`（shell 可执行区）之间双向搬运文件。
 *
 * 为什么需要搬运：Android 10+ 禁止 targetSdk≥29 的应用 exec 自己 data 目录里的文件，
 * 而 shell(2000) 用户又读不到应用私有目录 —— 所以 exec 模式必须把 JRE 与实例 stage 到
 * `/data/local/tmp`（shell 可读写且可执行）后再 `bin/java` 拉起。
 *
 * Manifest 里注册了 `ShizukuProvider`，App 启动即自动接收 binder（无需手动 bind）。
 */
object ShizukuController {

    private const val TAG = "Shizuku"
    private const val REQUEST_CODE = 0x4D53 // "MS"

    /** shell 可执行区的数据根（与私有 `filesDir/mslx` 布局对应）。 */
    const val REMOTE_BASE = "/data/local/tmp/mslx"

    private val JAVA_BIN_CANDIDATES = listOf("bin/java", "jre/bin/java")

    /** 堆打标签垫片的 assets 目录与远端路径（用途见 [ensureShimStaged]）。 */
    private const val SHIM_ASSET_DIR = "mslx"
    val remoteShimPath = "$REMOTE_BASE/lib/libmslxnotag.so"

    private val _status = MutableStateFlow(ShizukuStatus.DEAD)
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    @Volatile
    private var listenersAdded = false

    private val receivedListener = object : Shizuku.OnBinderReceivedListener {
        override fun onBinderReceived() = refresh()
    }
    private val deadListener = object : Shizuku.OnBinderDeadListener {
        override fun onBinderDead() {
            _status.value = ShizukuStatus.DEAD
        }
    }

    /** 注册 binder 监听（幂等）。ViewModel 初始化时调用，使状态能随 Shizuku 启停自动更新。 */
    fun init() {
        if (!listenersAdded) {
            runCatching {
                Shizuku.addBinderReceivedListener(receivedListener)
                Shizuku.addBinderDeadListener(deadListener)
                listenersAdded = true
            }.onFailure { AppLogger.w(TAG, "注册 Shizuku 监听失败（可能未安装）", it) }
        }
        refresh()
    }

    /** 重新计算并广播状态。 */
    fun refresh() {
        _status.value = computeStatus()
    }

    private fun computeStatus(): ShizukuStatus = runCatching {
        when {
            !Shizuku.pingBinder() -> ShizukuStatus.DEAD
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuStatus.READY
            else -> ShizukuStatus.NO_PERMISSION
        }
    }.getOrDefault(ShizukuStatus.DEAD)

    /** 是否已授权可 exec（每次实时探测，不依赖缓存状态）。 */
    fun isReady(): Boolean = computeStatus() == ShizukuStatus.READY

    /** 是否需要向用户解释为何申请权限（用于 UI 文案）。 */
    fun shouldShowRationale(): Boolean =
        runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)

    /** 申请授权；结果经回调返回（Shizuku 会拉起授权界面）。 */
    fun requestPermission(onResult: (granted: Boolean) -> Unit) {
        runCatching {
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    runCatching { Shizuku.removeRequestPermissionResultListener(this) }
                    refresh()
                    onResult(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(REQUEST_CODE)
        }.onFailure { e ->
            AppLogger.w(TAG, "请求 Shizuku 授权失败", e)
            refresh()
            onResult(false)
        }
    }

    /** 经 Shizuku 服务端 exec 一个远端进程（调用方负责读写流与 waitFor）。 */
    fun newProcess(cmd: Array<String>, env: Array<String>?, dir: String?): RemoteShellProcess {
        val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
        return RemoteShellProcess(service.newProcess(cmd, env, dir))
    }

    // —— 短命令 exec（staging 用）——

    suspend fun exec(cmd: Array<String>, dir: String? = null): ExecResult =
        execStreaming(cmd, dir, null)

    private suspend fun execStreaming(
        cmd: Array<String>,
        dir: String?,
        stdin: ((OutputStream) -> Unit)?,
    ): ExecResult = withContext(Dispatchers.IO) {
        val p = newProcess(cmd, null, dir)
        val out = StringBuilder()
        val err = StringBuilder()
        val tOut = drain(p.inputStream, out)
        val tErr = drain(p.errorStream, err)
        runCatching {
            if (stdin != null) p.outputStream.use { stdin(it) } else p.outputStream.close()
        }.onFailure { AppLogger.w(TAG, "写入子进程 stdin 失败", it) }
        val code = runCatching { p.waitFor() }.getOrElse {
            runCatching { p.destroy() }
            -1
        }
        tOut.join()
        tErr.join()
        ExecResult(code, out.toString(), err.toString())
    }

    private fun drain(input: InputStream, sink: StringBuilder): Thread =
        Thread {
            runCatching {
                input.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    synchronized(sink) { sink.append(line).append('\n') }
                }
            }
        }.apply {
            isDaemon = true
            name = "shizuku-drain"
            start()
        }

    // —— 文件 staging（tar 管道，避免逐文件 exec 的高往返开销）——

    /** 在给定 JRE 根下定位 `bin/java` 的相对路径（兼容 `jre/bin/java` 嵌套布局）。 */
    fun javaBinRel(localJre: File): String? =
        JAVA_BIN_CANDIDATES.firstOrNull { File(localJre, it).isFile }

    fun remoteRuntimeDir(runtimeId: String): String = "$REMOTE_BASE/runtime/$runtimeId"

    fun remoteServerDir(dirName: String): String = "$REMOTE_BASE/servers/$dirName"

    /**
     * 确保 JRE 已 stage 到 `/data/local/tmp/mslx/runtime/<id>`（一次性，带完成标记）。
     * 返回远端 JRE 根目录；失败返回原因。
     */
    suspend fun ensureRuntimeStaged(
        context: Context,
        runtime: LocalJreManager.JavaRuntime,
    ): Result<String> = withContext(Dispatchers.IO) {
        val local = LocalJreManager.jreHome(context, runtime)
        if (!local.isDirectory) {
            return@withContext Result.failure(IllegalStateException("本地未安装 ${runtime.label}"))
        }
        val rel = javaBinRel(local)
            ?: return@withContext Result.failure(IllegalStateException("${runtime.label} 缺少 bin/java"))
        val remote = remoteRuntimeDir(runtime.id)
        val javaBin = "$remote/$rel"
        val marker = "$remote/.mslx-ok"
        val probe = exec(
            arrayOf("sh", "-c", "[ -x ${q(javaBin)} ] && [ -f ${q(marker)} ] && echo OK || echo NO"),
        )
        if (probe.out.trim() == "OK") return@withContext Result.success(remote)

        AppLogger.i(TAG, "stage ${runtime.label} → $remote（首次较慢）")
        exec(arrayOf("sh", "-c", "rm -rf ${q(remote)}; mkdir -p ${q(remote)}"))
        val pushed = pushDir(local, remote) { f -> isJreExec(f, local) }
        if (!pushed) {
            return@withContext Result.failure(IllegalStateException("推送运行时到 $remote 失败"))
        }
        // 修正可执行位（tar 里已按启发式设置，这里再兜底一次），并落完成标记
        exec(
            arrayOf(
                "sh",
                "-c",
                "chmod 755 ${q(remote)}/bin/* ${q(remote)}/jre/bin/* 2>/dev/null; " +
                    "chmod -R a+rX ${q(remote)} 2>/dev/null; : > ${q(marker)}",
            ),
        )
        val verify = exec(arrayOf("sh", "-c", "[ -x ${q(javaBin)} ] && echo OK || echo NO"))
        if (verify.out.trim() != "OK") {
            return@withContext Result.failure(IllegalStateException("$javaBin 不可执行（chmod 失败）"))
        }
        Result.success(remote)
    }

    /**
     * 确保「堆打标签垫片」已 stage 到 [remoteShimPath]。
     *
     * 为什么需要：Android 12+ 的 Scudo 分配器会给堆指针打上 top-byte tag，并在 free()
     * 校验标签；而旧版 OpenJDK（PojavLauncher 2021 的 jre17 构建）内部存在截断指针高位
     * 的行为，于是任何稍重的负载都会 `Pointer tag ... was truncated` → SIGABRT
     * （真机实测连 `keytool -genkeypair` 都跑不完）。垫片经 LD_PRELOAD 在 java main 之前
     * 调用 `mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, M_HEAP_TAGGING_LEVEL_NONE)`，
     * 只关掉本进程的堆打标签，不改系统设置。
     *
     * @return 可直接用作 LD_PRELOAD 的远端绝对路径；当前 ABI 无内嵌垫片或上传失败返回 null。
     */
    suspend fun ensureShimStaged(context: Context): String? = withContext(Dispatchers.IO) {
        val abi = LocalJreManager.currentAbi()
        val assetName = "libmslxnotag-$abi.so"
        if (!context.assets.list(SHIM_ASSET_DIR).orEmpty().contains(assetName)) {
            AppLogger.i(TAG, "无 $abi 的堆打标签垫片，跳过（旧 JRE 可能因 tagged pointers 退出）")
            return@withContext null
        }
        val probe = exec(arrayOf("sh", "-c", "[ -d ${q(remoteShimPath.substringBeforeLast('/'))} ] && echo OK || echo NO"))
        if (probe.out.trim() != "OK") exec(arrayOf("sh", "-c", "mkdir -p ${q(remoteShimPath.substringBeforeLast('/'))}"))
        runCatching {
            // 不做“已存在就跳过”：垫片只有几 KB，而且旧版本 APK 可能残留不兼容的
            // 副本（曾经因多导出一个 Agent_OnLoad 而插入 libinstrument 的同名符号，
            // 导致 Paper 启动 SIGSEGV），每次启动重推最便宜也最可靠。
            val tmp = File(context.cacheDir, "mslx-shim/libmslxnotag.so")
            tmp.parentFile?.mkdirs()
            context.assets.open("$SHIM_ASSET_DIR/$assetName").use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            val ok = pushFile(tmp, remoteShimPath)
            tmp.delete()
            if (!ok) throw IllegalStateException("上传 $remoteShimPath 失败")
            AppLogger.i(TAG, "已上传堆打标签垫片（$abi）")
            remoteShimPath
        }.onFailure { AppLogger.w(TAG, "垫片上传失败，exec 可能因 Scudo tagged pointers 异常退出", it) }
            .getOrNull()
    }

    /** 把实例目录整体 stage 到 `/data/local/tmp/mslx/servers/<dirName>`（每次启动，权威副本在私有目录）。 */
    suspend fun pushInstance(localDir: File, dirName: String): Result<String> =
        withContext(Dispatchers.IO) {
            if (!localDir.isDirectory) {
                return@withContext Result.failure(IllegalStateException("实例目录不存在：${localDir.path}"))
            }
            val remote = remoteServerDir(dirName)
            exec(arrayOf("sh", "-c", "rm -rf ${q(remote)}; mkdir -p ${q(remote)}/tmp"))
            val ok = pushDir(localDir, remote) { false }
            if (ok) Result.success(remote)
            else Result.failure(IllegalStateException("推送实例目录到 $remote 失败"))
        }

    /** 停止后把 world/logs/配置从远端回同步到私有目录（权威存储）。 */
    suspend fun syncInstanceBack(dirName: String, localDir: File): Boolean =
        withContext(Dispatchers.IO) {
            val remote = remoteServerDir(dirName)
            val names = listOf(
                "world", "world_nether", "world_the_end", "logs", "server.properties",
                "ops.json", "whitelist.json", "banned-players.json", "banned-ips.json",
                "plugins", "mods",
            )
            val script = buildString {
                append("cd ")
                append(q(remote))
                append(" 2>/dev/null && for n in ")
                append(names.joinToString(" "))
                append("; do [ -e \"\$n\" ] && echo \"\$n\"; done")
            }
            val listed = exec(arrayOf("sh", "-c", script))
            val existing = listed.out.lineSequence().map { it.trim() }
                .filter { it.isNotBlank() && names.contains(it) }.toList()
            if (existing.isEmpty()) return@withContext true
            pullTar(remote, existing, localDir)
        }

    /** 单个小文件经 stdin 管道写到远端路径（垫片之类不必走 tar）。 */
    private suspend fun pushFile(local: File, remotePath: String): Boolean =
        execStreaming(
            arrayOf("sh", "-c", "cat > ${q(remotePath)} && chmod 644 ${q(remotePath)}"),
            null,
        ) { stdin ->
            local.inputStream().use { it.copyTo(stdin, 32 * 1024) }
        }.ok

    /** 打包 [localSrc] 经管道喂给远端 `toybox tar x` 解包到 [remoteDst]。 */
    private suspend fun pushDir(
        localSrc: File,
        remoteDst: String,
        isExec: (File) -> Boolean,
    ): Boolean {
        val cmd = arrayOf("sh", "-c", "toybox tar x -C ${q(remoteDst)}")
        val res = execStreaming(cmd, null) { stdin ->
            TarArchiveOutputStream(BufferedOutputStream(stdin)).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                localSrc.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = f.relativeTo(localSrc).path.replace(File.separatorChar, '/')
                    val entry = TarArchiveEntry(f, rel)
                    entry.mode = if (isExec(f)) 0x1ED else 0x1A4 // 0755 : 0644
                    tar.putArchiveEntry(entry)
                    f.inputStream().use { it.copyTo(tar, 64 * 1024) }
                    tar.closeArchiveEntry()
                }
                tar.finish()
            }
        }
        if (!res.ok && res.err.isNotBlank()) AppLogger.w(TAG, "tar x 告警：${res.err.take(200)}")
        return res.ok
    }

    /** 让远端 `toybox tar c` 把 [names] 打到 stdout，本地边读边解包到 [localDst]。 */
    private suspend fun pullTar(remoteSrc: String, names: List<String>, localDst: File): Boolean =
        withContext(Dispatchers.IO) {
            if (names.isEmpty()) return@withContext true
            val cmd = arrayOf(
                "sh",
                "-c",
                "toybox tar c -C ${q(remoteSrc)} ${names.joinToString(" ") { q(it) }}",
            )
            runCatching {
                val p = newProcess(cmd, null, null)
                val tErr = drain(p.errorStream, StringBuilder())
                TarArchiveInputStream(BufferedInputStream(p.inputStream)).use { tar ->
                    var entry = tar.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val out = safeChild(localDst, entry.name)
                            if (out != null) {
                                out.parentFile?.mkdirs()
                                out.outputStream().use { tar.copyTo(it, 64 * 1024) }
                            }
                        }
                        entry = tar.nextEntry
                    }
                }
                runCatching { p.outputStream.close() }
                runCatching { p.waitFor() }
                tErr.join()
                true
            }.getOrElse {
                AppLogger.w(TAG, "回同步解包失败", it)
                false
            }
        }

    private fun isJreExec(f: File, base: File): Boolean {
        val rel = f.relativeTo(base).path.replace(File.separatorChar, '/')
        return rel.startsWith("bin/") || rel.startsWith("jre/bin/") || rel.endsWith(".so")
    }

    /** shell 单引号安全引用。 */
    private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** tar-slip 防护：解包路径必须落在 [base] 内。 */
    private fun safeChild(base: File, name: String): File? {
        val out = File(base, name).canonicalFile
        return if (out.path.startsWith(base.canonicalPath + File.separator)) out else null
    }
}

/**
 * 对 `moe.shizuku.server.IRemoteProcess` 的轻量包装，暴露成熟悉的 [Process] 式 API
 * （stdin/stdout/stderr + waitFor/destroy）。
 *
 * Shizuku 13.x 起 `Shizuku.newProcess` 已私有化，改由
 * `IShizukuService.Stub.asInterface(Shizuku.getBinder()).newProcess(...)` 获取远端进程。
 * 各 stdio 为跨进程 [ParcelFileDescriptor]，用 AutoClose 流包装后按普通流读写。
 */
class RemoteShellProcess(private val remote: IRemoteProcess) {

    /** 写入子进程 stdin。 */
    val outputStream: OutputStream by lazy { ParcelFileDescriptor.AutoCloseOutputStream(remote.outputStream) }

    /** 读取子进程 stdout。 */
    val inputStream: InputStream by lazy { ParcelFileDescriptor.AutoCloseInputStream(remote.inputStream) }

    /** 读取子进程 stderr。 */
    val errorStream: InputStream by lazy { ParcelFileDescriptor.AutoCloseInputStream(remote.errorStream) }

    fun waitFor(): Int = remote.waitFor()

    fun exitValue(): Int = remote.exitValue()

    fun alive(): Boolean = remote.alive()

    fun destroy() = remote.destroy()

    /** 带超时的等待；返回 true 表示进程已结束。 */
    fun waitForTimeout(timeout: Long, unit: TimeUnit): Boolean =
        remote.waitForTimeout(timeout, unit.name)
}
