package com.mslx.console.data.localengine

import android.content.Context
import android.os.Build
import com.mslx.console.data.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Android JRE 运行时管理。
 *
 * 来源：上游 PojavLauncher 的 Android OpenJDK 构建（bionic，arm64-v8a / x86_64），
 * 发行包为 `.tar.xz`，解包后根目录即 `bin/ lib/ conf/`（见 38/40 节文档）。
 *
 * 我们只依赖 `lib/server/libjvm.so` 与 `lib/modules`：JVM 由 native 层
 * dlopen + JNI_CreateJavaVM 在 **App 进程内**起，不 exec `bin/java`
 * —— Android 10+ 禁止 targetSdk≥29 的应用 exec 自己 data 目录里的文件。
 *
 * 运行时包优先取 APK assets 内嵌归档（离线可用），缺失时回退到预设下载地址（固定 SHA-256）。
 */
object LocalJreManager {

    /** 运行时标识（决定安装目录与上游版本）。 */
    const val RUNTIME_ID = "jre17"

    /** 该运行时对应的 Java 主版本；服务端核心必须兼容（Paper 1.20.5+/1.21 需 Java 21，暂不支持）。 */
    const val JAVA_MAJOR = 17

    private const val ASSET_DIR = "jre"
    private const val UPSTREAM_BASE =
        "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download/jre17-ec28559"

    /**
     * CNB 镜像前缀（国内直连更快）：JRE 归档会随 Release 一起挂载（见 CI 配置），
     * 下载顺序 CNB 首选 → 上游 GitHub 回退。发新版本时同步更新这里的 tag。
     */
    private const val CNB_JRE_MIRROR_BASE =
        "https://cnb.cool/WLudy/MSLX_APP-Android/-/releases/download/v1.6.3-Beta"

    /** 某个 ABI 对应的运行时归档：assets 内嵌名 / 下载源（按序尝试）/ 固定 SHA-256。 */
    data class RuntimeSpec(
        val assetName: String,
        val url: String,
        val sha256: String,
        val mirrors: List<String> = emptyList(),
    ) {
        /** 实际尝试的下载源顺序。 */
        val sources: List<String> get() = mirrors.ifEmpty { listOf(url) }
    }

    val ARM64 = RuntimeSpec(
        assetName = "jre17-arm64-20210825-release.tar.xz",
        url = "$UPSTREAM_BASE/jre17-arm64-20210825-release.tar.xz",
        sha256 = "c64583ac2e0ec8857e43456fa9adcf482c6a8e454a7133173bf15692d2478b8d",
        mirrors = listOf(
            "$CNB_JRE_MIRROR_BASE/jre17-arm64-20210825-release.tar.xz",
            "$UPSTREAM_BASE/jre17-arm64-20210825-release.tar.xz",
        ),
    )

    val X86_64 = RuntimeSpec(
        assetName = "jre17-x86_64-20210825-release.tar.xz",
        url = "$UPSTREAM_BASE/jre17-x86_64-20210825-release.tar.xz",
        sha256 = "ebbdf75ab864a83671a108032c30e67174f79cc19596cfc1d7bfb71be26b6e71",
        mirrors = listOf(
            "$CNB_JRE_MIRROR_BASE/jre17-x86_64-20210825-release.tar.xz",
            "$UPSTREAM_BASE/jre17-x86_64-20210825-release.tar.xz",
        ),
    )

    fun specForAbi(abi: String): RuntimeSpec? = when (abi) {
        "arm64-v8a", "arm64" -> ARM64
        "x86_64" -> X86_64
        else -> null
    }

    /** 当前设备首选的受支持 ABI。 */
    fun currentAbi(): String {
        val supported = Build.SUPPORTED_ABIS.orEmpty()
        return supported.firstOrNull { specForAbi(it) != null } ?: supported.firstOrNull() ?: "arm64-v8a"
    }

    /** 该 ABI 的归档是否随 APK 内嵌（UI 用来显示"内嵌 / 需下载"）。 */
    fun hasEmbeddedAsset(context: Context, abi: String = currentAbi()): Boolean {
        val spec = specForAbi(abi) ?: return false
        return context.assets.list(ASSET_DIR).orEmpty().any { it == spec.assetName }
    }

    /** JRE 安装根目录（= java.home）；必须在私有目录（外部存储 noexec 且 dlopen 也不可靠）。 */
    fun jreHome(context: Context): File = File(context.filesDir, "jre/$RUNTIME_ID")

    /** 进程内 JVM 真正需要的库。 */
    fun libjvmFile(context: Context): File = File(jreHome(context), "lib/server/libjvm.so")

    private fun modulesFile(context: Context): File = File(jreHome(context), "lib/modules")

    private fun markerFile(context: Context): File = File(jreHome(context), ".mslx-runtime")

    fun isInstalled(context: Context): Boolean =
        libjvmFile(context).isFile && modulesFile(context).isFile

    /** 已安装运行时描述（供 UI 展示），未安装返回 null。 */
    fun installedInfo(context: Context): String? {
        if (!isInstalled(context)) return null
        val marker = markerFile(context).takeIf { it.isFile }
            ?.readText()?.trim()?.lineSequence()?.firstOrNull().orEmpty()
        val sizeMb = jreHome(context).walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1024 / 1024
        return (if (marker.isBlank()) RUNTIME_ID else marker) + " · ${sizeMb}MB"
    }

    /**
     * 安装（或修复）JRE：优先 assets 内嵌归档，其次按预设地址下载；两种都做 SHA-256 校验后解压。
     * 失败会清理半成品目录，绝不留下"看起来装好了"的残缺运行时。
     */
    suspend fun install(context: Context, onProgress: (Float) -> Unit = {}): Result<File> = runCatching {
        val abi = currentAbi()
        val spec = specForAbi(abi) ?: throw IllegalStateException("当前设备 ABI（$abi）暂无可用 Android JRE")
        val home = jreHome(context)
        withContext(Dispatchers.IO) {
            home.deleteRecursively()
            home.mkdirs()
            try {
                val embedded = embeddedArchive(context, spec)
                if (embedded != null) {
                    AppLogger.i("LocalJre", "从内嵌 assets 安装 JRE：${spec.assetName}")
                    extractAndVerify(embedded.first, embedded.second, spec, home, onProgress)
                } else {
                    // 未内嵌该 ABI：按 CNB → GitHub 顺序尝试下载（任一源失败自动换下一个）
                    val tmp = File(context.cacheDir, spec.assetName)
                    var lastError: Throwable? = null
                    var done = false
                    for (url in spec.sources) {
                        try {
                            AppLogger.i("LocalJre", "下载 JRE 运行时：$url")
                            LocalDownloader.download(url, tmp, spec.sha256) { onProgress(it * 0.5f) }
                            onProgress(0.5f)
                            extractAndVerify({ FileInputStream(tmp) }, tmp.length(), spec, home) { p ->
                                onProgress(0.5f + p * 0.5f)
                            }
                            done = true
                            break
                        } catch (e: Exception) {
                            lastError = e
                            AppLogger.w("LocalJre", "JRE 下载源失败，尝试下一个：$url", e)
                            tmp.delete()
                        }
                    }
                    tmp.delete()
                    if (!done) {
                        throw IllegalStateException(
                            "所有下载源均失败（最后一次：${lastError?.message ?: "未知错误"}）",
                            lastError,
                        )
                    }
                }
                if (!isInstalled(context)) {
                    throw IllegalStateException("解包后缺少 lib/server/libjvm.so 或 lib/modules，归档可能不是 Android JRE 构建")
                }
                markerFile(context).writeText("$RUNTIME_ID/$abi\n${spec.sha256}\n")
            } catch (e: Exception) {
                home.deleteRecursively()
                throw e
            }
        }
        AppLogger.i("LocalJre", "JRE 安装完成：${jreHome(context)}")
        jreHome(context)
    }

    /** 内嵌归档：存在则返回 (流工厂, 长度)，否则 null。 */
    private fun embeddedArchive(context: Context, spec: RuntimeSpec): Pair<() -> InputStream, Long>? {
        if (!hasEmbeddedAsset(context, currentAbi())) return null
        val path = "$ASSET_DIR/${spec.assetName}"
        val length = runCatching { context.assets.openFd(path).length }.getOrDefault(-1L)
        return { context.assets.open(path) } to length
    }

    private fun extractAndVerify(
        open: () -> InputStream,
        totalBytes: Long,
        spec: RuntimeSpec,
        dest: File,
        onProgress: (Float) -> Unit,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        open().use { raw ->
            val counting = CountingInputStream(raw, totalBytes, digest, onProgress)
            if (spec.assetName.endsWith(".zip")) extractZip(counting, dest) else extractTarXz(counting, dest)
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(spec.sha256, ignoreCase = true)) {
            throw IllegalStateException("JRE 归档 SHA-256 校验失败：期望 ${spec.sha256}，实际 $actual")
        }
    }

    /** 透传流：统计已读字节（进度），同时累积 SHA-256。 */
    private class CountingInputStream(
        private val delegate: InputStream,
        private val total: Long,
        private val digest: MessageDigest,
        private val onProgress: (Float) -> Unit,
    ) : InputStream() {
        private var read = 0L

        override fun read(): Int {
            val b = delegate.read()
            if (b >= 0) {
                digest.update(b.toByte())
                read += 1
                report()
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = delegate.read(b, off, len)
            if (n > 0) {
                digest.update(b, off, n)
                read += n
                report()
            }
            return n
        }

        private fun report() {
            if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
        }

        /** 必须透传：InputStream 默认返回 0，部分解压实现会据此误判流已结束。 */
        override fun available(): Int = delegate.available()

        /** skip 一律走 read，保证 SHA-256 与进度统计不漏字节。 */
        override fun skip(n: Long): Long {
            if (n <= 0) return 0
            val buf = ByteArray(minOf(n, 64 * 1024).toInt().coerceAtLeast(1))
            val r = read(buf, 0, buf.size)
            return if (r < 0) 0 else r.toLong()
        }

        override fun close() = delegate.close()
    }

    /** 解压 .tar.xz（上游格式）；软链按目标内容落成普通文件（上游仅 legal/ 下有）。 */
    private fun extractTarXz(input: InputStream, dest: File) {
        TarArchiveInputStream(XZCompressorInputStream(input)).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val name = entry.name
                when {
                    entry.isDirectory -> safeResolve(dest, name).mkdirs()

                    entry.isSymbolicLink -> {
                        val link = entry.linkName.orEmpty()
                        val base = File(dest, name).parentFile ?: dest
                        val target = File(base, link).canonicalFile
                        if (target.isFile && target.canonicalPath.startsWith(dest.canonicalPath + File.separator)) {
                            val out = safeResolve(dest, name)
                            out.parentFile?.mkdirs()
                            target.copyTo(out, overwrite = true)
                        }
                    }

                    else -> {
                        val out = safeResolve(dest, name)
                        out.parentFile?.mkdirs()
                        out.outputStream().use { tar.copyTo(it, 64 * 1024) }
                    }
                }
                entry = tar.nextEntry
            }
        }
    }

    /** 解压 .zip（第三方来源兜底）。 */
    private fun extractZip(input: InputStream, dest: File) {
        ZipInputStream(input).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.isDirectory) continue
                val out = safeResolve(dest, entry.name)
                out.parentFile?.mkdirs()
                out.outputStream().use { zis.copyTo(it, 64 * 1024) }
                zis.closeEntry()
            }
        }
    }

    /** tar-slip / zip-slip 防护：解包路径必须落在目标目录内。 */
    private fun safeResolve(dest: File, name: String): File {
        val resolved = File(dest, name).canonicalFile
        if (!resolved.path.startsWith(dest.canonicalPath + File.separator)) {
            throw IllegalStateException("非法归档条目：$name")
        }
        return resolved
    }
}
