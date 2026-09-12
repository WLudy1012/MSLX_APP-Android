package com.mslx.console.data.localengine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 通用下载器：流式下载到目标文件，支持进度回调与 SHA-256 校验。
 */
object LocalDownloader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    /** 下载 [url] 到 [target]；[expectedSha256] 非空时校验（不匹配抛异常）。 */
    suspend fun download(
        url: String,
        target: File,
        expectedSha256: String? = null,
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
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
                    target.delete()
                    throw IllegalStateException("SHA-256 校验失败: 期望 $expectedSha256 实际 $actual")
                }
            }
            target
        }
    }
}
