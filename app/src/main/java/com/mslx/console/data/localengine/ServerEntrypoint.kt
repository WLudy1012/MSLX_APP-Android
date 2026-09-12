package com.mslx.console.data.localengine

import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarFile

/**
 * 解析服务端核心 jar 的启动入口。
 *
 * 普通核心（Paper/Spigot/Fabric/≤1.16 原版）直接读 MANIFEST 的 Main-Class 即可；
 * vanilla 1.18+ 的官方 jar 是 **bundler 壳**：它的 Main-Class 会 fork 一个子 `java` 进程，
 * 而 Android 10+ 禁止 targetSdk≥29 的应用 exec 自己 data 目录里的文件 —— 因此这里
 * 复刻 bundler 的行为：就地解出 `META-INF/versions.list` 指向的内层 server jar，
 * 直接以它的 Main-Class 在**同一 JVM 内**启动。
 */
object ServerEntrypoint {

    private const val BUNDLER_MAIN = "net.minecraft.bundler.Main"
    private const val VERSIONS_LIST = "META-INF/versions.list"

    /** [classpath] 为实际要加载的 jar（bundler 情况下是解出来的内层 jar）。 */
    data class Resolved(
        val classpath: List<File>,
        val mainClass: String,
        val note: String? = null,
    )

    fun resolve(serverJar: File, cacheDir: File, log: (String) -> Unit = {}): Result<Resolved> = runCatching {
        val mainClass = readMainClass(serverJar)
            ?: throw IllegalStateException("核心 jar 缺少 Main-Class")
        if (mainClass != BUNDLER_MAIN) {
            return@runCatching Resolved(listOf(serverJar), mainClass)
        }
        val inner = extractBundledJar(serverJar, cacheDir, log)
        val innerMain = readMainClass(inner) ?: "net.minecraft.server.Main"
        Resolved(
            classpath = listOf(inner),
            mainClass = innerMain,
            note = "官方 bundler 壳已在本地解包（Android 上无法 fork 子 java 进程）",
        )
    }

    private fun readMainClass(jar: File): String? = runCatching {
        JarFile(jar).use { it.manifest?.mainAttributes?.getValue(Attributes.Name.MAIN_CLASS) }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun extractBundledJar(bundlerJar: File, cacheDir: File, log: (String) -> Unit): File {
        JarFile(bundlerJar).use { jar ->
            val listEntry = jar.getJarEntry(VERSIONS_LIST)
                ?: jar.getJarEntry("META-INF/versions.list")
                ?: throw IllegalStateException("bundler 壳缺少 $VERSIONS_LIST")
            val line = jar.getInputStream(listEntry).bufferedReader().use { reader ->
                reader.readLines().firstOrNull { it.isNotBlank() }
            } ?: throw IllegalStateException("$VERSIONS_LIST 为空")
            // 格式：<sha1>\t<id>\t<path>
            val raw = line.split('\t', ' ').lastOrNull { it.isNotBlank() }?.trim()
                ?: throw IllegalStateException("$VERSIONS_LIST 格式异常：$line")
            val candidates = listOf(raw, "META-INF/$raw", "META-INF/versions/$raw")
            val versionEntry = candidates.firstNotNullOfOrNull { jar.getJarEntry(it) }
                ?: throw IllegalStateException("bundler 壳内找不到内层 jar：$raw")

            val out = File(cacheDir, File(versionEntry.name).name)
            if (out.isFile && out.length() > 0) return out
            log("解包 bundler 内层核心：${versionEntry.name}（${versionEntry.size / 1024 / 1024}MB）")
            out.parentFile?.mkdirs()
            jar.getInputStream(versionEntry).use { input ->
                out.outputStream().use { input.copyTo(it, 64 * 1024) }
            }
            if (!out.isFile || out.length() == 0L) {
                throw IllegalStateException("内层 jar 解包失败：${out.path}")
            }
            return out
        }
    }
}
