package com.mslx.console.data.remote

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.mslx.console.data.AppLogger
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object ApiClient {

    /** 客户端 User-Agent（发版时与 build.gradle.kts 的 versionName 保持同步）。 */
    private const val USER_AGENT = "MSLX-Android/1.5.1"

    fun build(baseUrl: String, apiKey: String): MslxApi {
        val builder = OkHttpClient.Builder()
            .addInterceptor(httpLoggingInterceptor())
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("x-api-key", apiKey)
                    .addHeader("User-Agent", USER_AGENT)
                    .build()
                chain.proceed(request)
            }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)

        // 守护进程常启用自签 HTTPS 证书，默认 TrustManager 会抛 CertPathValidatorException；
        // 此处信任所有证书（仅用于连接用户自己的守护进程）。
        configureDaemonHttpClient(builder)
        val client = builder.build()

        return Retrofit.Builder()
            .baseUrl(ensureTrailingSlash(baseUrl))
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MslxApi::class.java)
    }

    /**
     * 给 OkHttpClient.Builder 配置信任所有证书的 SSL（仅守护进程内网自签场景使用）。
     * sslSocketFactory 与 hostnameVerifier 使用同一个 X509TrustManager 实例。
     */
    fun configureDaemonHttpClient(builder: OkHttpClient.Builder) {
        val manager = trustAllManager()
        builder
            .sslSocketFactory(trustAllSslSocketFactory(manager), manager)
            .hostnameVerifier { _, _ -> true }
    }

    /**
     * 脱敏 HTTP 日志拦截器：仅记录方法 + scheme://host/path（不含查询参数与请求头），
     * 响应只记录状态码与耗时；异常记录 exception class/message。API Key 永不落日志。
     */
    private fun httpLoggingInterceptor(): okhttp3.Interceptor = okhttp3.Interceptor { chain ->
        val request = chain.request()
        val url = request.url
        val safeUrl = "${url.scheme}://${url.host}${url.encodedPath}"
        val started = System.currentTimeMillis()
        try {
            val response = chain.proceed(request)
            val cost = System.currentTimeMillis() - started
            AppLogger.d("HTTP", "${request.method} $safeUrl -> ${response.code} (${cost}ms)")
            response
        } catch (e: Exception) {
            AppLogger.w("HTTP", "请求失败 ${request.method} $safeUrl", e)
            throw e
        }
    }

    /** 信任所有证书的 X509TrustManager（仅守护进程内网自签场景使用）。 */
    private fun trustAllManager(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private fun trustAllSslSocketFactory(manager: X509TrustManager): javax.net.ssl.SSLSocketFactory {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(manager), SecureRandom())
        return context.socketFactory
    }

    /** 构建 MSLX 官方在线 API 客户端(无需认证)。 */
    fun buildMslJavaApi(): MslJavaApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(httpLoggingInterceptor())
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("User-Agent", USER_AGENT)
                    .build()
                chain.proceed(request)
            }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl("https://api.mslmc.cn/v3/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MslJavaApi::class.java)
    }

    /** 构建 MSLAPI v4 服务端核心接口客户端(无需认证)。 */
    fun buildMslServerCoreApi(): MslServerCoreApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(httpLoggingInterceptor())
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("User-Agent", USER_AGENT)
                    .build()
                chain.proceed(request)
            }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl("https://api.mslmc.cn/v4/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MslServerCoreApi::class.java)
    }

    /** 构建 Microsoft OpenJDK GitHub API 客户端(无需认证)。 */
    fun buildMicrosoftJavaApi(): MicrosoftJavaApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(httpLoggingInterceptor())
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Accept", "application/vnd.github+json")
                    .addHeader("User-Agent", USER_AGENT)
                    .build()
                chain.proceed(request)
            }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MicrosoftJavaApi::class.java)
    }

    /** 构建 GitHub Releases API 客户端(公开仓库，无需认证)。 */
    fun buildGitHubReleaseApi(): GitHubReleaseApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(httpLoggingInterceptor())
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Accept", "application/vnd.github+json")
                    .addHeader("User-Agent", USER_AGENT)
                    .build()
                chain.proceed(request)
            }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GitHubReleaseApi::class.java)
    }

    /** 构建一言（Hitokoto）金句 API 客户端（公开接口，无需认证）。 */
    fun buildHitokotoApi(): HitokotoApi {
        val client = OkHttpClient.Builder()
            .addInterceptor(httpLoggingInterceptor())
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Accept", "application/json")
                    .addHeader("User-Agent", USER_AGENT)
                    .build()
                chain.proceed(request)
            }
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl("https://v1.hitokoto.cn/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(HitokotoApi::class.java)
    }

    /**
     * 规范化 Daemon 地址：
     * - trim + trimEnd('/')；
     * - 无协议前缀（忽略大小写）时补 https://；
     * - allowHttp=false（默认，安全）：明文 http:// 自动升级为 https://；
     * - allowHttp=true（用户勾选并确认后）：保留用户输入的 http:// 明文地址，
     *   仅用于完全可信的内网场景（明文传输有被窃听/篡改风险）。
     */
    fun normalizeDaemonUrl(input: String, allowHttp: Boolean = false): String {
        var url = input.trim().trimEnd('/')
        if (url.isNotBlank() && !url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            url = "https://$url"
        }
        if (!allowHttp && url.startsWith("http://", ignoreCase = true)) {
            url = "https://" + url.substringAfter("://", url)
        }
        return url
    }

    private fun ensureTrailingSlash(url: String): String =
        if (url.endsWith("/")) url else "$url/"
}
