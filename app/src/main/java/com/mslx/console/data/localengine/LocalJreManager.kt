package com.mslx.console.data.localengine

import com.mslx.console.data.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * 通用下载器：流式下载到目标文件，支持进度回调与 SHA-256 校验。
 */
object LocalDownloader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /** 下载 [url] 到 [target]；[expectedSha256] 非空时校验（不匹配抛异常）。 */
    suspend fun download(url: String, target: File, expectedSha256: String? = null, onProgress: (Float) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            val request = okhttp3.Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                val body = response.body ?: throw IllegalStateException("响应为空")
                val total = body.contentLength()
                val digest = MessageDigest.getInstance("SHA-256")
                target.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val read = input.read(buf)
                            if (read < 0) break
                            output.write(buf, 0, read)
                            digest.update(buf, 0, read)
                            done += read
                            if (total > 0) onProgress(done.toFloat() / total)
                        }
                    }
                }
                if (expectedSha256 != null) {
                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actual.equals(expectedSha256.lowercase(), ignoreCase = true)) {
                        throw IllegalStateException("SHA-256 校验失败: 期望 $expectedSha256 实际 $actual")
                    }
                }
                target
            }
        }
}

/**
 * Android JRE 管理器：从 zip 包下载/解压到 App 私有目录（PojavLauncher 同思路的 bionic JRE），
 * 供本机开服引擎直接 exec java。
 */
object LocalJreManager {

    fun jreRoot(context: android.content.Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "jre")

    fun installedJava(context: android.content.Context): File =
        File(jreRoot(context), "bin/java")

    fun isInstalled(context: android.content.Context): Boolean = installedJava(context).isFile

    /**
     * 从 [url]（.zip 归档，内含 jre 根内容，如 bin/java）下载并解压到 [jreRoot]。
     * [expectedSha256] 可空。解压前清空旧目录，含 zip-slip 防护。
     */
    suspend fun installFromZip(context: android.content.Context, url: String, expectedSha256: String?, onProgress: (Float) -> Unit = {}): Result<File> =
        runCatching {
            val root = jreRoot(context)
            val tmp = File(root.parentFile ?: root, "jre-download.zip")
            withContext(Dispatchers.IO) {
                LocalDownloader.download(url, tmp, expectedSha256) { onProgress(it) }
                root.deleteRecursively()
                root.mkdirs()
                extractZip(tmp, root)
                tmp.delete()
            }
            val java = installedJava(context)
            if (!java.isFile) throw IllegalStateException("归档内未找到 bin/java，请确认是 Android JRE zip")
            AppLogger.i("LocalJre", "JRE 安装完成: $java")
            java
        }

    private fun extractZip(zip: File, destDir: File) {
        ZipInputStream(FileInputStream(zip)).use { zis ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory) continue
                // zip-slip 防护
                val resolved = File(destDir, name).canonicalFile
                if (!resolved.path.startsWith(destDir.canonicalPath + File.separator)) {
                    throw IllegalStateException("非法压缩条目: $name")
                }
                resolved.parentFile?.mkdirs()
                resolved.outputStream().use { out ->
                    while (true) {
                        val read = zis.read(buf)
                        if (read < 0) break
                        out.write(buf, 0, read)
                    }
                }
                zis.closeEntry()
            }
        }
    }
}
