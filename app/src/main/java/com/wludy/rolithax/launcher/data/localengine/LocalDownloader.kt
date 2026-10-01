package com.wludy.rolithax.launcher.data.localengine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 通用下载器：流式下载到临时文件，完成长度和 SHA-256 校验后原子替换目标。
 */
object LocalDownloader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .build()
    private val redirectSafeClient = client.newBuilder().followRedirects(false).build()

    /** 下载 [url] 到 [target]，必须提供可信 SHA-256。 */
    suspend fun download(
        url: String,
        target: File,
        expectedSha256: String,
        onProgress: (Float) -> Unit = {},
    ): File = downloadVerified(url, target, expectedSha256, "SHA-256", onProgress)

    suspend fun downloadVerified(
        url: String,
        target: File,
        expectedHash: String,
        algorithm: String,
        onProgress: (Float) -> Unit = {},
        allowedHosts: Set<String>? = null,
    ): File = withContext(Dispatchers.IO) {
        val expected = expectedHash.trim().lowercase()
        val expectedLength = when (algorithm.uppercase()) {
            "SHA-512" -> 128
            "SHA-256" -> 64
            "SHA-1" -> 40
            else -> throw IllegalArgumentException("不支持的摘要算法")
        }
        require(expected.length == expectedLength && expected.matches(Regex("[0-9a-f]+"))) { "缺少有效摘要，拒绝安装制品" }
        val requestUrl = Request.Builder().url(url).build().url
        if (allowedHosts != null) requireAllowedUrl(requestUrl, allowedHosts)
        val request = Request.Builder().url(requestUrl).build()
        val partial = File(target.parentFile ?: File("."), "${target.name}.part")
        partial.delete()
        var lastError: Throwable? = null
        try {
            repeat(3) { attempt ->
                try {
                    val response = if (allowedHosts == null) client.newCall(request).execute() else executeAllowed(requestUrl, allowedHosts)
                    response.use {
                        if (!response.isSuccessful) {
                            val retryable = response.code == 429 || response.code in 500..599
                            throw IllegalStateException(if (retryable) "HTTP ${response.code}（可重试）" else "HTTP ${response.code}")
                        }
                        val body = response.body ?: throw IllegalStateException("响应为空")
                        val total = body.contentLength()
                        val digest = MessageDigest.getInstance(algorithm)
                        target.parentFile?.mkdirs()
                        body.byteStream().use { input ->
                            partial.outputStream().use { output ->
                                val buf = ByteArray(64 * 1024)
                                var done = 0L
                                while (true) {
                                    val read = input.read(buf)
                                    if (read < 0) break
                                    if (read == 0) continue
                                    output.write(buf, 0, read)
                                    digest.update(buf, 0, read)
                                    done += read
                                    if (total > 0) onProgress(done.toFloat() / total)
                                }
                                if (total >= 0 && done != total) throw IllegalStateException("下载内容不完整：期望 $total 字节，实际 $done 字节")
                            }
                        }
                        val actual = digest.digest().joinToString("") { "%02x".format(it) }
                        if (actual != expected) throw IllegalStateException("$algorithm 校验失败")
                    }
                    if (target.exists() && !target.delete()) throw IllegalStateException("无法替换旧制品")
                    if (!partial.renameTo(target)) throw IllegalStateException("无法提交已校验制品")
                    return@withContext target
                } catch (error: Throwable) {
                    lastError = error
                    partial.delete()
                    val retryable = error.message?.contains("可重试") == true
                    if (!retryable || attempt == 2) throw error
                    delay(500L * (attempt + 1))
                }
            }
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
        throw lastError ?: IllegalStateException("下载失败")
    }

    private fun executeAllowed(initialUrl: HttpUrl, allowedHosts: Set<String>): Response {
        var url = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val response = redirectSafeClient.newCall(Request.Builder().url(url).build()).execute()
            if (response.code !in REDIRECT_CODES) return response
            val target = response.header("Location")?.let(response.request.url::resolve)
            response.close()
            if (redirectCount == MAX_REDIRECTS || target == null) throw IllegalStateException("下载重定向无效或次数过多")
            requireAllowedUrl(target, allowedHosts)
            url = target
        }
        throw IllegalStateException("下载重定向次数过多")
    }

    private fun requireAllowedUrl(url: HttpUrl, allowedHosts: Set<String>) {
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.port == 443 &&
            allowedHosts.any { it.equals(url.host, ignoreCase = true) }) { "资源下载域名不受支持" }
    }

    private const val MAX_REDIRECTS = 5
    private val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
}
