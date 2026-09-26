package com.mslx.console.data.remote

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.mslx.console.BuildConfig
import com.mslx.console.data.AppLogger
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object ApiClient {

    /**
     * 客户端 User-Agent：直接读 BuildConfig.VERSION_NAME（CI Actions 渠道为 x.x.x.x 形态），
     * 发版时不再需要手工同步版本号。
     */
    private val USER_AGENT = "MSLX-Android/${BuildConfig.VERSION_NAME}"

    fun build(baseUrl: String, apiKey: String): MslxApi {
        val builder = OkHttpClient.Builder()
            .addInterceptor(httpLoggingInterceptor())
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("User-Agent", USER_AGENT)
                    .apply { if (apiKey.isNotBlank()) addHeader("x-api-key", apiKey) }
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
     * 给 OkHttpClient.Builder 配置信任所有证书的 SSL。
     *
     * 适用边界（必须严格限定）：仅用于连接**用户自己部署的 Daemon**——其自签证书无法被
     * Android 系统信任链校验，放开校验是可用性前置条件；不用于任何第三方公开服务
     * （公开 API 客户端在本类中单独构建，不经过本方法）。
     *
     * 风险知情：本方法等价于“已关闭证书校验”——连接存在被中间人窃听/篡改的可能；
     * 因此连接向导的 HTTP 明文警告弹窗会同时向用户明示这一点。
     * 不使用证书固定（证书固定会在用户更换自签证书后直接断连，维护成本大于收益）。
     *
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
     * 构建第三方服务器状态查询客户端（mcsrvstat.us / mcstatus.io，公开接口无需认证）。
     *
     * 与其它公开 API 客户端一致：不启用「信任所有证书」（那只用于用户自部署的 Daemon）。
     */
    private fun <T> buildServerStatusApi(baseUrl: String, service: Class<T>): T {
        val client = OkHttpClient.Builder()
            .addInterceptor(httpLoggingInterceptor())
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader("Accept", "application/json")
                    .addHeader("User-Agent", USER_AGENT)
                    .build()
                chain.proceed(request)
            }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(service)
    }

    /** 第三方服务器状态 API：mcsrvstat.us（图标来源首选）。 */
    fun buildMcsrvstatApi(): McsrvstatApi =
        buildServerStatusApi("https://api.mcsrvstat.us/", McsrvstatApi::class.java)

    /** 第三方服务器状态 API：mcstatus.io（mcsrvstat.us 无结果时回退）。 */
    fun buildMcstatusApi(): McstatusApi =
        buildServerStatusApi("https://api.mcstatus.io/", McstatusApi::class.java)

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

    /**
     * 尽力从请求异常中提取 Daemon 统一响应体的 message 文案。
     *
     * Retrofit 对 HTTP 非 2xx 抛 HttpException，业务 message 在 errorBody 里
     * （插件端点以 HTTP 状态码承载业务码，如 410 配对码过期）；提取失败返回 null，
     * 由调用方回退到异常自身文案。
     */
    fun errorMessageFrom(throwable: Throwable): String? {
        val http = generateSequence(throwable) { it.cause }
            .filterIsInstance<retrofit2.HttpException>()
            .firstOrNull()
            ?: return null
        val body = runCatching { http.response()?.errorBody()?.string() }.getOrNull()
        if (body.isNullOrBlank()) return null
        val message = runCatching {
            com.google.gson.Gson().fromJson(body, com.mslx.console.data.model.ApiResponse::class.java)?.message
        }.getOrNull()
        return message?.takeIf { it.isNotBlank() }
    }

    private fun ensureTrailingSlash(url: String): String =
        if (url.endsWith("/")) url else "$url/"
}
