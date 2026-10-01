package com.mslx.console.data.localengine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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

    /** 下载 [url] 到 [target]，必须提供可信 SHA-256。 */
    suspend fun download(
        url: String,
        target: File,
        expectedSha256: String,
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val expected = expectedSha256.trim().lowercase()
        require(expected.matches(Regex("[0-9a-f]{64}"))) { "缺少有效 SHA-256，拒绝安装制品" }
        val request = Request.Builder().url(url).build()
        val partial = File(target.parentFile ?: File("."), "${target.name}.part")
        partial.delete()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                val body = response.body ?: throw IllegalStateException("响应为空")
                val total = body.contentLength()
                val digest = MessageDigest.getInstance("SHA-256")
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
                        if (total >= 0 && done != total) {
                            throw IllegalStateException("下载内容不完整：期望 $total 字节，实际 $done 字节")
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (actual != expected) {
                    throw IllegalStateException("SHA-256 校验失败: 期望 $expected 实际 $actual")
                }
            }
            if (target.exists() && !target.delete()) throw IllegalStateException("无法替换旧制品")
            if (!partial.renameTo(target)) throw IllegalStateException("无法提交已校验制品")
            target
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }
}
