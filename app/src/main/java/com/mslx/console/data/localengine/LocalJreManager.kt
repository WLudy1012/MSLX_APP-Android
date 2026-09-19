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
 * Android JRE 运行时管理（**多版本注册表**）。
 *
 * 来源：上游 PojavLauncher 的 Android OpenJDK 构建（bionic，arm64-v8a / x86_64），
 * 发行包为 `.tar.xz`，解包后根目录即 `bin/ lib/ conf/`（Java 8 无 `conf/`、无 `lib/modules`）。
 *
 * 覆盖常用 Java 版本 8 / 17 / 21（见 [RUNTIMES]），但只有**确实存在 Android/bionic 构建**的版本
 * 才置 [JavaRuntime.supported]（Java 8 目前没有，UI 标为不可用，避免用户点了才吃 404）。
 * 每个版本按 ABI 提供下载源，顺序为「项目自托管 CNB 镜像 → 外部镜像 / 上游 GitHub」。
 * SHA-256 **可选**：留空则跳过哈希校验，
 * 改用结构校验（`libjvm.so` 存在 + 版本标志文件），并打印告警——维护者上传归档后可用
 * `fetch-jre-assets.ps1` 的 `Get-FileHash` 结果回填。
 *
 * 进程内 JVM 只依赖 `libjvm.so` 与（9+）`lib/modules`：JVM 由 native 层 dlopen +
 * JNI_CreateJavaVM 在 **App 进程内**起，不 exec `bin/java`（Android 10+ 禁止 targetSdk≥29
 * 的应用 exec 自己 data 目录里的文件）。exec 路线见 Shizuku 增强引擎（Phase 2）。
 */
object LocalJreManager {

    /** 内嵌 assets 子目录（随 APK 打包的运行时归档）。 */
    private const val ASSET_DIR = "jre"

    /**
     * CNB 镜像前缀（国内直连更快）：JRE 归档随 Release 一起挂载（见 .cnb.yml），
     * 下载顺序 CNB 首选 → 其他镜像回退。**发新版本时必须同步这里的 tag**（否则旧 tag 上
     * 只会有当时挂过的归档，新归档 404）。
     */
    private const val CNB_JRE_MIRROR_BASE =
        "https://cnb.cool/WLudy/MSLX_APP-Android/-/releases/download/v1.7"

    /** 上游 PojavLauncher 多架构 OpenJDK 构建发布根（当前仅 jre17 为 Android 构建）。 */
    private const val UPSTREAM_MULTIARCH =
        "https://github.com/PojavLauncherTeam/android-openjdk-build-multiarch/releases/download"

    /** GitHub Release 附件前缀（由 .github/workflows/release.yml 与 tag 同名发布）。 */
    private const val GITHUB_JRE_MIRROR_BASE =
        "https://github.com/WLudy1012/MSLX_APP-Android/releases/download/v1.7"

    /**
     * FCL 下载站镜像（作者自建的 Android/bionic JRE 归档，arm64）。
     * 清单见 github 仓库 XiaoluoFoxington/FCL.downsite.NEW 的 `data/down/8/index.json`。
     */
    private const val FCL_JRE_MIRROR = "https://pan.huang1111.cn/f/eWkQI1"

    /** 某个 ABI 的运行时归档：内嵌名 / 下载源（按序尝试）/ SHA-256（可空=跳过哈希校验）。 */
    data class AbiSpec(
        val assetName: String,
        val sources: List<String>,
        val sha256: String = "",
    )

    /**
     * 一个 Java 运行时版本规格。
     * @param id 安装目录名（= runtimeId），如 `jre8` / `jre17` / `jre21`。
     * @param javaMajor Java 主版本（8 / 17 / 21）。
     * @param label UI 展示名。
     * @param hasModules Java 9+ 有 `lib/modules`；Java 8 无（用 `lib/rt.jar`）。
     * @param specs 归一化 ABI（`arm64-v8a` / `x86_64`）→ 归档规格；缺该键即该 ABI 不支持。
     * @param supported 是否存在**真正可用于 Android** 的构建。上游只有 iOS/macOS 产物时置 false，
     *   UI 会标为「暂无 Android 构建」并禁用安装，避免用户点了才收到 404。
     */
    data class JavaRuntime(
        val id: String,
        val javaMajor: Int,
        val label: String,
        val hasModules: Boolean,
        val specs: Map<String, AbiSpec>,
        val supported: Boolean = true,
    ) {
        fun spec(abi: String): AbiSpec? = specs[normalizeAbi(abi)]
        fun supportsAbi(abi: String): Boolean = spec(abi) != null
    }

    /** 归一化 ABI 名到注册表键。 */
    fun normalizeAbi(abi: String): String = when (abi) {
        "arm64-v8a", "arm64", "aarch64" -> "arm64-v8a"
        "x86_64", "x64", "amd64" -> "x86_64"
        else -> abi
    }

    /**
     * Java 8（仅 arm64，且**需维护者向 CNB 镜像上传 Android/bionic 构建**）。
     *
     * 注意：上游 android-openjdk-build-multiarch 的 `jre8-40df388` / `jre8-99f3f8b` 两个
     * Release 是 **iOS/macOS 专用**（归档内是 `lib` 下的 `.dylib`，文件名里的 arm64 指 Apple 主
     * 机），在 Android 上完全不可用，因此这里不挂上游回退源；该仓库当前只有
     * `jre17-ec28559`（JRE17 for Android）能直接用。
     */
    val JRE8 = JavaRuntime(
        id = "jre8",
        javaMajor = 8,
        label = "Java 8",
        hasModules = false,
        specs = mapOf(
            "arm64-v8a" to AbiSpec(
                assetName = "jre8-arm64-20220811-release.tar.xz",
                sources = listOf(
                    "$CNB_JRE_MIRROR_BASE/jre8-arm64-20220811-release.tar.xz",
                ),
                sha256 = "", // 镜像上传后用 Get-FileHash 回填
            ),
        ),
        supported = false,
    )

    /** Java 17（arm64 + x86_64，URL 与 SHA-256 均已验证）。 */
    val JRE17 = JavaRuntime(
        id = "jre17",
        javaMajor = 17,
        label = "Java 17",
        hasModules = true,
        specs = mapOf(
            "arm64-v8a" to AbiSpec(
                assetName = "jre17-arm64-20210825-release.tar.xz",
                sources = listOf(
                    "$CNB_JRE_MIRROR_BASE/jre17-arm64-20210825-release.tar.xz",
                    "$UPSTREAM_MULTIARCH/jre17-ec28559/jre17-arm64-20210825-release.tar.xz",
                ),
                sha256 = "c64583ac2e0ec8857e43456fa9adcf482c6a8e454a7133173bf15692d2478b8d",
            ),
            "x86_64" to AbiSpec(
                assetName = "jre17-x86_64-20210825-release.tar.xz",
                sources = listOf(
                    "$CNB_JRE_MIRROR_BASE/jre17-x86_64-20210825-release.tar.xz",
                    "$UPSTREAM_MULTIARCH/jre17-ec28559/jre17-x86_64-20210825-release.tar.xz",
                ),
                sha256 = "ebbdf75ab864a83671a108032c30e67174f79cc19596cfc1d7bfb71be26b6e71",
            ),
        ),
    )

    /**
     * Java 21（Paper 1.20.5+ / 1.21 需要，arm64）。
     *
     * 归档取自 FCL 下载站（bionic Android 构建，已核对 ELF：e_machine=183 AArch64、
     * 解释器 `/system/bin/linker64`），本地 `Get-FileHash` 得到 SHA-256 后固定；
     * 下载顺序：项目自托管 CNB 镜像 → GitHub Release（两条流水线都会挂同名附件）
     * → FCL 直链。该上游不提供 x86_64 构建，故只注册 arm64-v8a。
     */
    val JRE21 = JavaRuntime(
        id = "jre21",
        javaMajor = 21,
        label = "Java 21",
        hasModules = true,
        specs = mapOf(
            "arm64-v8a" to AbiSpec(
                assetName = "jre21-arm64-20260223-release.tar.xz",
                sources = listOf(
                    "$CNB_JRE_MIRROR_BASE/jre21-arm64-20260223-release.tar.xz",
                    "$GITHUB_JRE_MIRROR_BASE/jre21-arm64-20260223-release.tar.xz",
                    "$FCL_JRE_MIRROR/jre21-arm64-20260223-release.tar.xz",
                ),
                sha256 = "d055fc953771e6cfd2206ef770ca675d69b5e4cfd9f3f1e6820ba5820c3cc1dc",
            ),
        ),
    )

    /** 全部受支持运行时（按版本升序）。 */
    val RUNTIMES: List<JavaRuntime> = listOf(JRE8, JRE17, JRE21)

    /** 默认运行时（离线内嵌、最稳）。 */
    val DEFAULT_RUNTIME: JavaRuntime = JRE17

    // —— 向后兼容常量（旧代码引用；等价于 DEFAULT_RUNTIME）——
    const val RUNTIME_ID = "jre17"
    const val JAVA_MAJOR = 17

    fun runtimeById(id: String?): JavaRuntime =
        RUNTIMES.firstOrNull { it.id == id } ?: DEFAULT_RUNTIME

    fun runtimeForMajor(major: Int): JavaRuntime =
        RUNTIMES.firstOrNull { it.javaMajor == major } ?: DEFAULT_RUNTIME

    /**
     * 按 Minecraft 游戏版本推荐 Java 主版本（与 MSLAPI / 创建向导规则一致）：
     * 1.16 及以下 → 8；1.17–1.20.4 → 17；1.20.5 及以上 → 21；26.x+ → 25。版本串无法识别返回 null。
     */
    fun recommendedMajorForGame(gameVersion: String): Int? {
        val version = gameVersion.trim().removePrefix("v")
        val match = Regex("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?").find(version) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: return null
        val patch = match.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
        if (major >= 26) return 25
        if (major != 1) return null
        return when {
            minor <= 16 -> 8
            minor <= 20 && (minor < 20 || patch <= 4) -> 17
            else -> 21
        }
    }

    /** 运行时与核心游戏版本的匹配度。 */
    enum class RuntimeFit { MATCH, TOO_OLD, TOO_NEW, UNKNOWN }

    /**
     * 判定 [runtime] 跑 [gameVersion] 核心的匹配度，第二项为推荐主版本（识别不出为 null）。
     *
     * TOO_OLD 几乎必失败（新版 Paper 会直接拒绝 JVM），调用方应拦下来并引导安装对应运行时；
     * TOO_NEW 只是风险提示（老核心在新 Java 上可能崩，但我们往往没有更低版本可给）。
     */
    fun fitForGame(runtime: JavaRuntime, gameVersion: String): Pair<RuntimeFit, Int?> {
        val recommended = recommendedMajorForGame(gameVersion) ?: return RuntimeFit.UNKNOWN to null
        val fit = when {
            runtime.javaMajor < recommended -> RuntimeFit.TOO_OLD
            runtime.javaMajor > recommended -> RuntimeFit.TOO_NEW
            else -> RuntimeFit.MATCH
        }
        return fit to recommended
    }

    /** 按游戏版本选当前设备可用的推荐运行时（推荐版本不可用时向上取最近的可装版本）。 */
    fun recommendedRuntime(gameVersion: String, abi: String = currentAbi()): JavaRuntime {
        val candidates = installableRuntimes(abi)
        val recommended = recommendedMajorForGame(gameVersion)
            ?: return candidates.firstOrNull { it.id == DEFAULT_RUNTIME.id } ?: DEFAULT_RUNTIME
        candidates.firstOrNull { it.javaMajor == recommended }?.let { return it }
        return candidates.filter { it.javaMajor > recommended }.minByOrNull { it.javaMajor }
            ?: candidates.maxByOrNull { it.javaMajor }
            ?: DEFAULT_RUNTIME
    }

    /** 不可安装原因（null = 可装）：统一给可直接展示给用户的中文提示。 */
    fun installBlockReason(runtime: JavaRuntime, abi: String = currentAbi()): String? {
        if (!runtime.supported) {
            return "${runtime.label} 暂无 Android（bionic）构建：上游只发布了 iOS/macOS 产物，无法在本机安装"
        }
        if (runtime.spec(normalizeAbi(abi)) == null) {
            return "${runtime.label} 暂无当前设备架构（$abi）的运行时归档"
        }
        return null
    }

    /** 当前设备首选的受支持 ABI。 */
    fun currentAbi(): String {
        val supported = Build.SUPPORTED_ABIS.orEmpty()
        // 优先选任一**可安装**运行时能提供归档的 ABI
        return supported.firstOrNull { abi -> installableRuntimes(abi).isNotEmpty() }
            ?: supported.firstOrNull()
            ?: "arm64-v8a"
    }

    /** 当前 ABI 下**可安装**的运行时（有对应 ABI 归档，含暂无 Android 构建项，供 UI 标注不可用）。 */
    fun availableRuntimes(abi: String = currentAbi()): List<JavaRuntime> =
        RUNTIMES.filter { it.supportsAbi(abi) }

    /** 当前 ABI 下真正可安装的运行时（过滤掉 [JavaRuntime.supported] = false）。 */
    fun installableRuntimes(abi: String = currentAbi()): List<JavaRuntime> =
        availableRuntimes(abi).filter { it.supported }

    /** 某 ABI 是否有可用运行时（用于判断本机开服是否被设备支持）。 */
    fun isAbiSupported(abi: String = currentAbi()): Boolean = availableRuntimes(abi).isNotEmpty()

    /** 该运行时/ABI 的归档是否随 APK 内嵌（UI 显示"内嵌 / 需下载"）。 */
    fun hasEmbeddedAsset(context: Context, runtime: JavaRuntime, abi: String = currentAbi()): Boolean {
        val spec = runtime.spec(abi) ?: return false
        return context.assets.list(ASSET_DIR).orEmpty().any { it == spec.assetName }
    }

    /** JRE 安装根目录（= java.home 的候选根）：`<数据目录>/runtime/<id>`。 */
    fun jreHome(context: Context, runtime: JavaRuntime): File = LocalStorage.jreHome(context, runtime.id)

    /** 兼容旧签名：默认运行时的安装目录。 */
    fun jreHome(context: Context): File = jreHome(context, DEFAULT_RUNTIME)

    /** libjvm.so 的候选相对路径（不同版本/构建布局可能不同）。 */
    private val LIBJVM_CANDIDATES = listOf(
        "lib/server/libjvm.so",
        "lib/jli/libjvm.so",
        "jre/lib/server/libjvm.so",
        "lib/aarch64/server/libjvm.so",
        "lib/x86_64/server/libjvm.so",
        "lib/arm64/server/libjvm.so",
    )

    /** 在给定 JRE 根目录下探测 `libjvm.so`；未找到返回 null。 */
    fun libjvmIn(home: File): File? =
        LIBJVM_CANDIDATES.firstNotNullOfOrNull { rel -> File(home, rel).takeIf { it.isFile } }

    /**
     * 解析实际的 `libjvm.so`：不同版本/构建的相对路径可能不同，按候选顺序探测。
     * 未安装返回 null。
     */
    fun libjvmFile(context: Context, runtime: JavaRuntime): File? = libjvmIn(jreHome(context, runtime))

    /**
     * 解析真正的 java.home：从 `libjvm.so` 路径回溯到含 `lib/` 的目录
     * （兼容 `<root>/lib/...`、`<root>/jre/lib/...`、`<root>/lib/<arch>/...` 等布局）。
     */
    fun resolveJavaHome(runtimeHome: File, libjvm: File): File {
        val path = libjvm.canonicalPath
        val marker = File.separator + "lib" + File.separator
        val idx = path.indexOf(marker)
        return if (idx > 0) File(path.substring(0, idx)) else runtimeHome
    }

    private fun markerFile(context: Context, runtime: JavaRuntime): File =
        File(jreHome(context, runtime), ".mslx-runtime")

    /** Java 8 无 lib/modules，改看 rt.jar；9+ 看 lib/modules。 */
    private fun hasVersionMarker(context: Context, runtime: JavaRuntime): Boolean {
        val home = jreHome(context, runtime)
        return if (runtime.hasModules) {
            File(home, "lib/modules").isFile
        } else {
            File(home, "lib/rt.jar").isFile || File(home, "jre/lib/rt.jar").isFile
        }
    }

    fun isInstalled(context: Context, runtime: JavaRuntime): Boolean =
        libjvmFile(context, runtime) != null && hasVersionMarker(context, runtime)

    /** 兼容旧签名：默认运行时是否已安装。 */
    fun isInstalled(context: Context): Boolean = isInstalled(context, DEFAULT_RUNTIME)

    /** 已安装运行时描述（供 UI 展示），未安装返回 null。 */
    fun installedInfo(context: Context, runtime: JavaRuntime): String? {
        if (!isInstalled(context, runtime)) return null
        val marker = markerFile(context, runtime).takeIf { it.isFile }
            ?.readText()?.trim()?.lineSequence()?.firstOrNull().orEmpty()
        val sizeMb = jreHome(context, runtime).walkTopDown().filter { it.isFile }
            .sumOf { it.length() } / 1024 / 1024
        return (if (marker.isBlank()) runtime.id else marker) + " · ${sizeMb}MB"
    }

    /**
     * 安装（或修复）指定运行时：优先 assets 内嵌归档，其次按预设源下载；
     * 配置了 SHA-256 则校验，未配置则跳过哈希、改用结构校验（并告警）。
     * 失败会清理半成品目录，绝不留下"看起来装好了"的残缺运行时。
     */
    suspend fun install(
        context: Context,
        runtime: JavaRuntime = DEFAULT_RUNTIME,
        abi: String = currentAbi(),
        onProgress: (Float) -> Unit = {},
    ): Result<File> = runCatching {
        installBlockReason(runtime, abi)?.let { throw IllegalStateException(it) }
        val spec = runtime.spec(abi)
            ?: throw IllegalStateException("${runtime.label} 暂无当前设备 ABI（$abi）的 Android 运行时")
        val home = jreHome(context, runtime)
        withContext(Dispatchers.IO) {
            home.deleteRecursively()
            home.mkdirs()
            try {
                val embedded = embeddedArchive(context, runtime, spec, abi)
                if (embedded != null) {
                    AppLogger.i("LocalJre", "从内嵌 assets 安装 ${runtime.label}：${spec.assetName}")
                    extractAndVerify(embedded.first, embedded.second, spec, home, runtime, onProgress)
                } else {
                    if (spec.sha256.isBlank()) {
                        AppLogger.w(
                            "LocalJre",
                            "${runtime.label}（$abi）未配置 SHA-256：将跳过哈希校验，仅做结构校验（建议维护者回填）",
                        )
                    }
                    val tmp = File(context.cacheDir, spec.assetName)
                    var lastError: Throwable? = null
                    var done = false
                    for (url in spec.sources) {
                        try {
                            AppLogger.i("LocalJre", "下载 ${runtime.label} 运行时：$url")
                            LocalDownloader.download(url, tmp, spec.sha256.ifBlank { null }) { onProgress(it * 0.5f) }
                            onProgress(0.5f)
                            extractAndVerify({ FileInputStream(tmp) }, tmp.length(), spec, home, runtime) { p ->
                                onProgress(0.5f + p * 0.5f)
                            }
                            done = true
                            break
                        } catch (e: Exception) {
                            lastError = e
                            AppLogger.w("LocalJre", "运行时下载源失败，尝试下一个：$url", e)
                            tmp.delete()
                        }
                    }
                    tmp.delete()
                    if (!done) {
                        throw IllegalStateException(
                            "所有下载源均失败（已尝试 ${spec.sources.size} 个源，最后：${lastError?.message ?: "未知错误"}）。" +
                                "若为 404，说明该运行时归档尚未上传到镜像，请按 fetch-jre-assets.ps1 流程补齐并重填 SHA-256。",
                            lastError,
                        )
                    }
                }
                if (!isInstalled(context, runtime)) {
                    throw IllegalStateException(
                        "解包后结构校验失败（缺 libjvm.so 或 ${if (runtime.hasModules) "lib/modules" else "lib/rt.jar"}），" +
                            "归档可能不是匹配的 Android JRE 构建",
                    )
                }
                markerFile(context, runtime).writeText(
                    "${runtime.id}/${abi}\n${spec.sha256.ifBlank { "unverified" }}\n",
                )
            } catch (e: Exception) {
                home.deleteRecursively()
                throw e
            }
        }
        AppLogger.i("LocalJre", "${runtime.label} 安装完成：${jreHome(context, runtime)}")
        jreHome(context, runtime)
    }

    /** 内嵌归档：存在则返回 (流工厂, 长度)，否则 null。 */
    private fun embeddedArchive(
        context: Context,
        runtime: JavaRuntime,
        spec: AbiSpec,
        abi: String,
    ): Pair<() -> InputStream, Long>? {
        if (!hasEmbeddedAsset(context, runtime, abi)) return null
        val path = "$ASSET_DIR/${spec.assetName}"
        val length = runCatching { context.assets.openFd(path).length }.getOrDefault(-1L)
        return { context.assets.open(path) } to length
    }

    private fun extractAndVerify(
        open: () -> InputStream,
        totalBytes: Long,
        spec: AbiSpec,
        dest: File,
        runtime: JavaRuntime,
        onProgress: (Float) -> Unit,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        open().use { raw ->
            val counting = CountingInputStream(raw, totalBytes, digest, onProgress)
            if (spec.assetName.endsWith(".zip")) extractZip(counting, dest) else extractTarXz(counting, dest)
            // 关键：tar/zip 解析在归档结束标记处就停止，不会读尽底层流。
            // 必须把剩余字节读尽，否则 SHA-256 只覆盖了文件前缀，导致校验误报失败。
            counting.readToEof()
        }
        // SHA-256 可选：留空则跳过（改用安装后的结构校验兜底）
        if (spec.sha256.isNotBlank()) {
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(spec.sha256, ignoreCase = true)) {
                throw IllegalStateException("${runtime.label} 归档 SHA-256 校验失败：期望 ${spec.sha256}，实际 $actual")
            }
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

        /**
         * 不关闭底层流：tar/xz 解包器会在结束时关闭包装流，从而连带
         * close 到这里；但解析器往往提前停止，必须让外层能继续把剩余
         * 字节读完以完成 SHA-256 覆盖。底层流的关闭由 extractAndVerify
         * 的 `open().use { raw -> ... }` 负责。
         */
        override fun close() { /* no-op，见方法注释 */ }

        /** 读尽剩余字节（同步累积 SHA-256 与进度），保证摘要覆盖整份文件。 */
        fun readToEof() {
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = read(buf, 0, buf.size)
                if (n < 0) break
            }
        }
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
        val destCanonical = dest.canonicalPath
        val resolved = File(dest, name).canonicalFile
        // 归档根条目（"./"、""）规范化后即目标目录本身，允许通过；
        // 其余必须严格落在 dest 目录内，拦截 "../" 等越界。
        if (resolved.path != destCanonical &&
            !resolved.path.startsWith(destCanonical + File.separator)
        ) {
            throw IllegalStateException("非法归档条目：$name")
        }
        return resolved
    }
}
