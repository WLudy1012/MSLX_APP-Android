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
 * 来源：PojavLauncher / FCL / ZalithLauncher 等项目维护的 Android OpenJDK 构建
 * （bionic，arm64-v8a / x86_64），发行包为 `.tar.xz`，解包后根目录即 `bin/ lib/ conf/`
 * （Java 8 无 `conf/`、无 `lib/modules`，类库以 `.jar.pack` 形式分发）。
 *
 * 覆盖 Java 版本 8 / 17 / 21 / 25（见 [RUNTIMES]）。每个版本按 ABI 提供下载源，
 * 顺序为「项目自托管 CNB 镜像 → 外部镜像 / 上游 GitHub」；SHA-256 **可选**：留空则跳过
 * 哈希校验，改用结构校验（`libjvm.so` 存在 + 版本标志文件）并打印告警——维护者上传镜像后
 * 可用 `fetch-jre-assets.ps1` 的 `Get-FileHash` 结果回填。
 *
 * Java 8 特殊：归档为「universal（类库）+ bin-<abi>（二进制）」两个包，解开后还需把
 * `.jar.pack` 还原成 `.jar`（见 [AbiSpec.needsUnpack200]，由随 APK 安装的
 * `libunpack200.so` 完成，见 [unpackPacks]）。
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

    /**
     * ZalithLauncher 2 内置运行时归档根（Android/bionic，其仓库
     * `ZalithLauncher/src/main/assets/runtimes/<jre-N>/`）：jsDelivr CDN 优先，GitHub raw 兜底。
     * Java 8 归档来自这里（OpenJDK 8u442 的 bionic 构建，GPL 系列许可）。
     */
    private const val ZALITH_RUNTIME_CDN =
        "https://cdn.jsdelivr.net/gh/ZalithLauncher/ZalithLauncher2@main/ZalithLauncher/src/main/assets/runtimes"
    private const val ZALITH_RUNTIME_RAW =
        "https://raw.githubusercontent.com/ZalithLauncher/ZalithLauncher2/main/ZalithLauncher/src/main/assets/runtimes"

    /**
     * pack200 解包器（Java 8 类库还原用）。随 APK 的 jniLibs 分发（见 app/src/main/jniLibs），
     * 安装后位于 `nativeLibraryDir`——只有那里的文件允许 exec（Android 10+ 禁止 exec 应用
     * data 目录里的文件）。
     */
    private const val UNPACK200_LIB = "libunpack200.so"

    /** 一个运行时归档：内嵌名 / 下载源（按序尝试）/ SHA-256（可空=跳过哈希校验）。 */
    data class AbiArchive(
        val assetName: String,
        val sources: List<String>,
        val sha256: String = "",
    )

    /**
     * 某个 ABI 的运行时规格：一个或多个归档 + 可选后处理。
     *
     * 多数版本只有一个归档；**Java 8 是两个**：`universal`（类库，含 `lib/rt.jar.pack`）+
     * `bin-<abi>`（平台二进制），两者解包到同一目录。
     *
     * @param extraArchives 追加归档（与主归档 [assetName] 一起按序解包到同一目录）。
     * @param needsUnpack200 解包后是否要用内置 `libunpack200.so` 把 `.pack` 还原为 `.jar`
     *   （Java 8 类库以 pack200 压缩分发；Java 9+ 已取消该格式）。
     */
    data class AbiSpec(
        val assetName: String,
        val sources: List<String>,
        val sha256: String = "",
        val extraArchives: List<AbiArchive> = emptyList(),
        val needsUnpack200: Boolean = false,
    ) {
        /** 全部归档（主归档在前）。 */
        val allArchives: List<AbiArchive>
            get() = listOf(AbiArchive(assetName, sources, sha256)) + extraArchives
    }

    /**
     * 一个 Java 运行时版本规格。
     * @param id 安装目录名（= runtimeId），如 `jre8` / `jre17` / `jre21` / `jre25`。
     * @param javaMajor Java 主版本（8 / 17 / 21 / 25）。
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
     * Java 8（arm64 + x86_64，1.16 及更早的原版/插件/模组服）。
     *
     * 归档取自 ZalithLauncher 2 内置运行时（OpenJDK 8u442 的 Android/bionic 构建，
     * GPL 系列许可；来源与构建见 ZalithLauncher2 仓库 runtimes/jre-8）：
     * `universal.tar.xz`（架构无关类库）+ `bin-<abi>.tar.xz`（平台二进制）。
     * 解包后类库是 `.jar.pack`，由 [unpackPacks] 用内置 `libunpack200.so` 还原；
     * 项目自托管 CNB / GitHub 镜像可挂同名归档（选传，404 自动跳到 Zalith 源）。
     */
    val JRE8 = JavaRuntime(
        id = "jre8",
        javaMajor = 8,
        label = "Java 8",
        hasModules = false,
        specs = mapOf(
            "arm64-v8a" to AbiSpec(
                assetName = "jre8-universal.tar.xz",
                sources = listOf(
                    "$CNB_JRE_MIRROR_BASE/jre8-universal.tar.xz",
                    "$GITHUB_JRE_MIRROR_BASE/jre8-universal.tar.xz",
                    "$ZALITH_RUNTIME_CDN/jre-8/universal.tar.xz",
                    "$ZALITH_RUNTIME_RAW/jre-8/universal.tar.xz",
                ),
                sha256 = "150072cfa1d9e037c31e4bf9770aa5470ae46d0bfefd366b7ce5b2f940ede3f3",
                extraArchives = listOf(
                    AbiArchive(
                        assetName = "jre8-arm64-bin.tar.xz",
                        sources = listOf(
                            "$CNB_JRE_MIRROR_BASE/jre8-arm64-bin.tar.xz",
                            "$GITHUB_JRE_MIRROR_BASE/jre8-arm64-bin.tar.xz",
                            "$ZALITH_RUNTIME_CDN/jre-8/bin-arm64.tar.xz",
                            "$ZALITH_RUNTIME_RAW/jre-8/bin-arm64.tar.xz",
                        ),
                        sha256 = "deed9083a1047af1afaf2d7f1a2de4ae39fadf62c52881f075793e80274956cf",
                    ),
                ),
                needsUnpack200 = true,
            ),
            "x86_64" to AbiSpec(
                assetName = "jre8-universal.tar.xz",
                sources = listOf(
                    "$CNB_JRE_MIRROR_BASE/jre8-universal.tar.xz",
                    "$GITHUB_JRE_MIRROR_BASE/jre8-universal.tar.xz",
                    "$ZALITH_RUNTIME_CDN/jre-8/universal.tar.xz",
                    "$ZALITH_RUNTIME_RAW/jre-8/universal.tar.xz",
                ),
                sha256 = "150072cfa1d9e037c31e4bf9770aa5470ae46d0bfefd366b7ce5b2f940ede3f3",
                extraArchives = listOf(
                    AbiArchive(
                        assetName = "jre8-x86_64-bin.tar.xz",
                        sources = listOf(
                            "$CNB_JRE_MIRROR_BASE/jre8-x86_64-bin.tar.xz",
                            "$GITHUB_JRE_MIRROR_BASE/jre8-x86_64-bin.tar.xz",
                            "$ZALITH_RUNTIME_CDN/jre-8/bin-x86_64.tar.xz",
                            "$ZALITH_RUNTIME_RAW/jre-8/bin-x86_64.tar.xz",
                        ),
                        sha256 = "37c3f7214ce3086575d7210baa25aee130d8db28c57739f598d24dfbfff32612",
                    ),
                ),
                needsUnpack200 = true,
            ),
        ),
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

    /**
     * Java 25（Minecraft 26.x 及以后，arm64）。
     *
     * 归档取自 FCL 下载站（bionic Android 构建，已核对 ELF：e_machine=183 AArch64、
     * 解释器 `/system/bin/linker64`、`lib/modules` 存在），本地 `Get-FileHash` 得到
     * SHA-256 后固定；下载顺序：项目自托管 CNB 镜像 → GitHub Release → FCL 直链。
     * 该上游不提供 x86_64 构建，故只注册 arm64-v8a。
     */
    val JRE25 = JavaRuntime(
        id = "jre25",
        javaMajor = 25,
        label = "Java 25",
        hasModules = true,
        specs = mapOf(
            "arm64-v8a" to AbiSpec(
                assetName = "jre25-arm64-20260223-release.tar.xz",
                sources = listOf(
                    "$CNB_JRE_MIRROR_BASE/jre25-arm64-20260223-release.tar.xz",
                    "$GITHUB_JRE_MIRROR_BASE/jre25-arm64-20260223-release.tar.xz",
                    "$FCL_JRE_MIRROR/jre25-arm64-20260223-release.tar.xz",
                ),
                sha256 = "0fdf6d19fe66ea61c12caa24bd655227ddb0d7d9c16c0f13281a7c2878635286",
            ),
        ),
    )

    /** 全部受支持运行时（按版本升序）。 */
    val RUNTIMES: List<JavaRuntime> = listOf(JRE8, JRE17, JRE21, JRE25)

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
            return "${runtime.label} 暂无 Android（bionic）构建，无法在本机安装"
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
        "lib/amd64/server/libjvm.so",
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
                val archives = spec.allArchives
                val perArchive = 1f / archives.size
                archives.forEachIndexed { index, archive ->
                    val base = index * perArchive
                    installArchive(context, runtime, archive, home) { p -> onProgress(base + p * perArchive) }
                }
                if (spec.needsUnpack200) {
                    unpackPacks(context, runtime, home)
                    onProgress(1f)
                }
                if (!isInstalled(context, runtime)) {
                    throw IllegalStateException(
                        "解包后结构校验失败（缺 libjvm.so 或 ${if (runtime.hasModules) "lib/modules" else "lib/rt.jar"}），" +
                            "归档可能不是匹配的 Android JRE 构建",
                    )
                }
                markerFile(context, runtime).writeText(
                    "${runtime.id}/${abi}\n" +
                        spec.allArchives.joinToString(";") { it.sha256.ifBlank { "unverified" } } + "\n",
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
    private fun embeddedArchive(context: Context, assetName: String): Pair<() -> InputStream, Long>? {
        val assets = context.assets.list(ASSET_DIR).orEmpty()
        if (assets.none { it == assetName }) return null
        val path = "$ASSET_DIR/$assetName"
        val length = runCatching { context.assets.openFd(path).length }.getOrDefault(-1L)
        return { context.assets.open(path) } to length
    }

    /** 安装单个归档：内嵌 assets 优先，其次按源下载；[onProgress] 覆盖该归档的 0→1。 */
    private suspend fun installArchive(
        context: Context,
        runtime: JavaRuntime,
        archive: AbiArchive,
        home: File,
        onProgress: (Float) -> Unit,
    ) {
        val embedded = embeddedArchive(context, archive.assetName)
        if (embedded != null) {
            AppLogger.i("LocalJre", "从内嵌 assets 安装 ${runtime.label}：${archive.assetName}")
            extractAndVerify(embedded.first, embedded.second, archive, home, runtime, onProgress)
            return
        }
        if (archive.sha256.isBlank()) {
            AppLogger.w(
                "LocalJre",
                "${runtime.label}（${archive.assetName}）未配置 SHA-256：将跳过哈希校验，仅做结构校验（建议维护者回填）",
            )
        }
        val tmp = File(context.cacheDir, archive.assetName)
        var lastError: Throwable? = null
        var done = false
        for (url in archive.sources) {
            try {
                AppLogger.i("LocalJre", "下载 ${runtime.label} 运行时：$url")
                LocalDownloader.download(url, tmp, archive.sha256.ifBlank { null }) { onProgress(it * 0.5f) }
                onProgress(0.5f)
                extractAndVerify({ FileInputStream(tmp) }, tmp.length(), archive, home, runtime) { p ->
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
                "所有下载源均失败（已尝试 ${archive.sources.size} 个源，最后：${lastError?.message ?: "未知错误"}）。" +
                    "若为 404，说明该运行时归档尚未上传到镜像，请按 fetch-jre-assets.ps1 流程补齐并重填 SHA-256。",
                lastError,
            )
        }
    }

    /**
     * 把运行时目录里的 `.jar.pack` 全部还原为 `.jar`（Java 8 的类库打包格式，9+ 已取消）。
     *
     * 解包器是随 APK 安装的 [UNPACK200_LIB]（jniLibs 打包，`nativeLibraryDir` 下才允许
     * exec）；`-r` 让解包器在成功后删除 `.pack` 源文件（rt.jar.pack 等，省 ~20MB）。
     */
    private suspend fun unpackPacks(context: Context, runtime: JavaRuntime, home: File) =
        withContext(Dispatchers.IO) {
            val exe = File(context.applicationInfo.nativeLibraryDir, UNPACK200_LIB)
            if (!exe.isFile) {
                throw IllegalStateException(
                    "${runtime.label} 需要 pack200 解包，但 APK 缺少 $UNPACK200_LIB（ABI 不匹配？），无法完成安装",
                )
            }
            val packs = home.walkTopDown().filter { it.isFile && it.extension == "pack" }.toList()
            if (packs.isEmpty()) {
                AppLogger.w("LocalJre", "${runtime.label} 未发现 .pack 文件，跳过 pack200 解包")
                return@withContext
            }
            AppLogger.i("LocalJre", "pack200 解包：${packs.size} 个文件（${runtime.label}）")
            for (pack in packs) {
                val dest = File(pack.parentFile, pack.name.removeSuffix(".pack"))
                val process = ProcessBuilder(exe.absolutePath, "-r", pack.absolutePath, dest.absolutePath)
                    .directory(exe.parentFile)
                    .redirectErrorStream(true)
                    .start()
                // 先读尽输出（进程结束即 EOF），避免写缓冲写满互相等待
                val output = runCatching { process.inputStream.bufferedReader().readText() }.getOrDefault("")
                val code = process.waitFor()
                if (code != 0) {
                    throw IllegalStateException(
                        "pack200 解包失败（exit=$code）：${pack.name}" +
                            output.trim().takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty(),
                    )
                }
            }
            postUnpack(runtime, home)
        }

    /**
     * 解包后修整（与 ZalithLauncher / PojavLauncher 的处理对齐）：部分归档的 libfreetype
     * 带版本后缀（`libfreetype.so.6`），JVM 按 SONAME 找无后缀名。
     */
    private fun postUnpack(runtime: JavaRuntime, home: File) {
        home.walkTopDown().filter { it.isFile && it.name == "libfreetype.so.6" }.forEach { ftIn ->
            val ftOut = File(ftIn.parentFile, "libfreetype.so")
            if (!ftOut.exists() || ftOut.length() != ftIn.length()) {
                if (!ftIn.renameTo(ftOut)) {
                    AppLogger.w("LocalJre", "${runtime.label} freetype 重命名失败：${ftIn.path}")
                }
            }
        }
    }

    private fun extractAndVerify(
        open: () -> InputStream,
        totalBytes: Long,
        archive: AbiArchive,
        dest: File,
        runtime: JavaRuntime,
        onProgress: (Float) -> Unit,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        open().use { raw ->
            val counting = CountingInputStream(raw, totalBytes, digest, onProgress)
            if (archive.assetName.endsWith(".zip")) extractZip(counting, dest) else extractTarXz(counting, dest)
            // 关键：tar/zip 解析在归档结束标记处就停止，不会读尽底层流。
            // 必须把剩余字节读尽，否则 SHA-256 只覆盖了文件前缀，导致校验误报失败。
            counting.readToEof()
        }
        // SHA-256 可选：留空则跳过（改用安装后的结构校验兜底）
        if (archive.sha256.isNotBlank()) {
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(archive.sha256, ignoreCase = true)) {
                throw IllegalStateException("${runtime.label} 归档 SHA-256 校验失败：期望 ${archive.sha256}，实际 $actual")
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

    /** 解压 .tar.xz（上游格式）；软链按目标内容落成普通文件（如 Java 8 的 libjsig.so 链）。 */
    private fun extractTarXz(input: InputStream, dest: File) {
        // 软链可能排在真实文件之前（lib/aarch64/server/libjsig.so → ../libjsig.so），
        // 先处理的会因目标尚不存在而跳过；此时记为待办，解包结束后统一补做。
        val deferredLinks = mutableListOf<Pair<String, String>>()
        TarArchiveInputStream(XZCompressorInputStream(input)).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val name = entry.name
                when {
                    entry.isDirectory -> safeResolve(dest, name).mkdirs()

                    entry.isSymbolicLink -> {
                        val link = entry.linkName.orEmpty()
                        if (!materializeLink(dest, name, link)) deferredLinks += name to link
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
        for ((name, link) in deferredLinks) materializeLink(dest, name, link)
    }

    /** 把软链实体化为目标内容的普通文件；目标不存在或越界返回 false（安全防线同 [safeResolve]）。 */
    private fun materializeLink(dest: File, name: String, link: String): Boolean {
        val base = File(dest, name).parentFile ?: dest
        val target = File(base, link).canonicalFile
        if (!target.isFile) return false
        if (!target.canonicalPath.startsWith(dest.canonicalPath + File.separator)) return false
        val out = safeResolve(dest, name)
        out.parentFile?.mkdirs()
        target.copyTo(out, overwrite = true)
        return true
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
