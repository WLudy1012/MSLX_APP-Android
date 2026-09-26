package com.mslx.console.data

import android.util.Base64
import com.mslx.console.data.model.ActionRequest
import com.mslx.console.data.model.CancelCreationRequest
import com.mslx.console.data.model.CommandResultPayload
import com.mslx.console.data.model.CreateServerRequest
import com.mslx.console.data.model.DeleteServerRequest
import com.mslx.console.data.model.ServerCoreClassify
import com.mslx.console.data.model.ServerCoreDownloadInfo
import com.mslx.console.data.model.ServerCoreGameVersion
import com.mslx.console.data.model.AdminCreateUserRequest
import com.mslx.console.data.model.AdminUpdateUserRequest
import com.mslx.console.data.model.FrpSummary
import com.mslx.console.data.model.FileItem
import com.mslx.console.data.model.InstanceInfo
import com.mslx.console.data.model.InstanceSummary
import com.mslx.console.data.model.LocalJava
import com.mslx.console.data.model.PmListData
import com.mslx.console.data.model.PmSetRequest
import com.mslx.console.data.model.SaveFileRequest
import com.mslx.console.data.model.SaveUploadRequest
import com.mslx.console.data.model.ServerSettings
import com.mslx.console.data.model.StatusData
import com.mslx.console.data.model.UploadFinishRequest
import com.mslx.console.data.model.UpdateSelfRequest
import com.mslx.console.data.model.UpdateSettingsData
import com.mslx.console.data.model.UserInfo
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.mslx.console.data.remote.ApiClient
import com.mslx.console.data.remote.ConsoleHubClient
import com.mslx.console.data.remote.MslxApi
import com.mslx.console.data.remote.SystemMonitorClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.Response

/** 实例相关的数据入口，封装 REST 调用与 SignalR 控制台客户端创建。 */
class InstanceRepository {

    @Volatile
    private var api: MslxApi? = null

    @Volatile
    var baseUrl: String = ""
        private set

    @Volatile
    var apiKey: String = ""
        private set

    val isConfigured: Boolean get() = api != null && baseUrl.isNotBlank()

    /**
     * 配置连接地址并重建 API 客户端(每次连接/切换连接时调用)。
     *
     * 配置未变化时直接早退：心跳（每 5 秒）等调用方会重复调用本方法，
     * 无条件重建 Retrofit/OkHttpClient 会丢弃连接池与线程资源，造成周期性卡顿。
     */
    fun configure(baseUrl: String, apiKey: String, allowHttp: Boolean = false) {
        val normalized = ApiClient.normalizeDaemonUrl(baseUrl, allowHttp)
        val trimmedKey = apiKey.trim()
        if (api != null && normalized == this.baseUrl && trimmedKey == this.apiKey) return
        this.baseUrl = normalized
        this.apiKey = trimmedKey
        this.api = ApiClient.build(normalized, this.apiKey)
        AppLogger.i("Repository", "configure: ${normalized.trimEnd('/')} allowHttp=$allowHttp")
    }

    private fun requireApi(): MslxApi =
        api ?: throw IllegalStateException("尚未配置连接信息")

    private suspend fun <T> daemonResult(block: suspend () -> T): Result<T> = runCatching {
        try {
            block()
        } catch (e: HttpException) {
            throw IllegalStateException(
                ApiClient.errorMessageFrom(e) ?: "HTTP ${e.code()} 请求失败",
                e,
            )
        }
    }

    suspend fun verify(): Result<Unit> = daemonResult {
        try {
            val resp = requireApi().status()
            if (resp.code != 200) {
                throw IllegalStateException(resp.message ?: "Daemon 鉴权失败，请检查 API Key")
            }
            if (resp.data == null) {
                throw IllegalStateException("Daemon 鉴权失败：未返回有效状态")
            }
            AppLogger.i("Repository", "verify 成功")
        } catch (e: HttpException) {
            if (e.code() == 401 || e.code() == 403) {
                throw IllegalStateException("Daemon 鉴权失败，请检查 API Key", e)
            }
            throw e
        }
    }

    suspend fun getStatus(): Result<StatusData> = daemonResult {
        val resp = requireApi().status()
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "获取状态失败")
        resp.data ?: throw IllegalStateException("返回数据为空")
    }

    suspend fun javaList(refresh: Boolean = false): Result<List<LocalJava>> = daemonResult {
        val resp = requireApi().javaList(refresh)
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "获取 Java 列表失败")
        resp.data ?: emptyList()
    }

    /** 从 MSLAPI 获取在线 Java 版本，失败时回退到 Microsoft OpenJDK。 */
    suspend fun onlineJavaVersions(os: String, arch: String): Result<List<String>> = runCatching {
        val mslVersions = runCatching {
            val response = ApiClient.buildMslJavaApi().jdkVersions(os, arch)
            if (response.code == 200) response.data.orEmpty() else emptyList()
        }.getOrDefault(emptyList())

        if (mslVersions.isNotEmpty()) {
            return@runCatching normalizeJavaVersions(mslVersions)
        }

        val microsoftVersions = ApiClient.buildMicrosoftJavaApi()
            .releases()
            .asSequence()
            .mapNotNull { release ->
                Regex("(?:jdk|java|microsoft)[-_]?(\\d+)", RegexOption.IGNORE_CASE)
                    .find(release.tag_name.orEmpty())
                    ?.groupValues
                    ?.getOrNull(1)
            }
            .toList()
        normalizeJavaVersions(microsoftVersions).ifEmpty {
            throw IllegalStateException("MSLAPI 和微软官方均未返回 Java 版本")
        }
    }

    private fun normalizeJavaVersions(versions: List<String>): List<String> = versions
        .filter { it.isNotBlank() }
        .distinct()
        .sortedWith(compareByDescending { it.toIntOrNull() ?: 0 })

    suspend fun listInstances(): Result<List<InstanceSummary>> = daemonResult {
        val resp = requireApi().instanceList()
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "获取实例列表失败")
        resp.data ?: emptyList()
    }

    suspend fun instanceInfo(id: Long): Result<InstanceInfo> = daemonResult {
        val resp = requireApi().instanceInfo(id)
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "获取实例信息失败")
        resp.data ?: throw IllegalStateException("返回数据为空")
    }

    suspend fun createInstance(request: CreateServerRequest): Result<String> = daemonResult {
        val resp = requireApi().createServer(request)
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "创建失败")
        resp.data?.serverId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("服务器未返回 ServerId")
    }

    suspend fun deleteInstance(id: Long, deleteFiles: Boolean): Result<String> = daemonResult {
        AppLogger.i("Repository", "删除实例 id=$id deleteFiles=$deleteFiles")
        val resp = requireApi().deleteInstance(DeleteServerRequest(id, deleteFiles))
        if (resp.code != 200) {
            // 400 等业务失败（如实例仍在运行）也走异常分支，message 直接透传给 UI
            throw IllegalStateException(resp.message ?: "删除实例失败")
        }
        AppLogger.i("Repository", "删除实例成功 id=$id")
        resp.message ?: "实例已删除"
    }

    suspend fun cancelCreation(serverId: String, cleanupFiles: Boolean = false): Result<String> = daemonResult {
        val resp = requireApi().cancelCreation(CancelCreationRequest(serverId, cleanupFiles))
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "取消失败")
        resp.message ?: "取消信号已发送"
    }

    suspend fun deleteUpload(uploadId: String): Result<Unit> = daemonResult {
        requireApi().deleteUpload(uploadId)
    }

    /**
     * 流式分块上传一个文件，返回可用于 createServer 的 fileKey(uploadId)。
     * 从 input 流逐块读取(每块 10MB)上传，避免 readBytes() 把整个文件读入内存；
     * 流读取在 IO 线程执行，避免阻塞主线程。
     *
     * 任一分片或合并失败都会调用 [deleteUpload] 清理服务端已落盘的临时分片，
     * 避免失败重试在 Daemon 上不断堆积孤儿碎片占用磁盘。
     */
    suspend fun uploadFileStream(
        input: () -> java.io.InputStream,
        totalBytes: Long,
        onProgress: (Int) -> Unit,
    ): Result<String> = runCatching {
        val uploadId = uploadInit().getOrThrow()
        try {
            val chunkSize = 10 * 1024 * 1024
            val buffer = ByteArray(chunkSize)
            var index = 0
            var uploaded = 0L
            withContext(Dispatchers.IO) {
                input().use { stream ->
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        val chunk = buffer.copyOf(read)
                        uploadChunk(uploadId, index, chunk).getOrThrow()
                        index++
                        uploaded += read
                        onProgress(((uploaded * 100) / maxOf(totalBytes, 1L)).toInt().coerceIn(0, 100))
                    }
                }
            }
            if (index == 0) {
                // 空文件也至少传一个空块，保证 finish 时总块数 >= 1
                uploadChunk(uploadId, 0, byteArrayOf()).getOrThrow()
                index = 1
            }
            uploadFinish(uploadId, index).getOrThrow()
            uploadId
        } catch (e: Exception) {
            AppLogger.w("Repository", "上传失败，清理服务端临时分片 uploadId=$uploadId", e)
            runCatching { deleteUpload(uploadId) }
                .onFailure { AppLogger.w("Repository", "清理服务端临时分片失败 uploadId=$uploadId", it) }
            throw e
        }
    }

    suspend fun serverCoreClassify(): Result<ServerCoreClassify> = runCatching {
        val response = ApiClient.buildMslServerCoreApi().classify()
        if (response.code != 200) throw IllegalStateException(response.message ?: "获取核心分类失败")
        val element = response.data ?: throw IllegalStateException("返回分类为空")
        val obj: JsonObject = when {
            element.isJsonObject -> element.asJsonObject
            element.isJsonArray -> {
                val arr = element.asJsonArray
                if (arr.size() == 0) throw IllegalStateException("返回分类为空")
                arr.get(0).asJsonObject
            }
            else -> throw IllegalStateException("返回分类格式错误")
        }
        Gson().fromJson(obj, ServerCoreClassify::class.java)
    }

    suspend fun serverCoreGameVersion(name: String): Result<ServerCoreGameVersion> = runCatching {
        val response = ApiClient.buildMslServerCoreApi().gameVersion(name)
        if (response.code != 200) throw IllegalStateException(response.message ?: "获取核心版本失败")
        val element = response.data ?: throw IllegalStateException("返回版本为空")
        val obj: JsonObject = when {
            element.isJsonObject -> element.asJsonObject
            element.isJsonArray -> {
                val arr = element.asJsonArray
                if (arr.size() == 0) throw IllegalStateException("返回版本为空")
                arr.get(0).asJsonObject
            }
            else -> throw IllegalStateException("返回版本格式错误")
        }
        Gson().fromJson(obj, ServerCoreGameVersion::class.java)
    }

    suspend fun serverCoreBuilds(name: String, version: String): Result<List<String>> = runCatching {
        val response = ApiClient.buildMslServerCoreApi().builds(name, version)
        if (response.code != 200) throw IllegalStateException(response.message ?: "获取构建版本失败")
        response.data.orEmpty()
    }

    suspend fun serverCoreDownloadInfo(name: String, version: String, build: String = "latest"): Result<ServerCoreDownloadInfo> = runCatching {
        val response = ApiClient.buildMslServerCoreApi().downloadInfo(name, version, build)
        if (response.code != 200) throw IllegalStateException(response.message ?: "获取下载信息失败")
        response.data ?: throw IllegalStateException("返回下载信息为空")
    }

    suspend fun sendAction(id: Long, action: String): Result<String> = daemonResult {
        val resp = requireApi().action(ActionRequest(id, action))
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "操作失败")
        resp.message ?: "操作成功"
    }

    suspend fun getSettings(id: Long): Result<ServerSettings> = daemonResult {
        val resp = requireApi().instanceSettings(id)
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "获取设置失败")
        resp.data ?: throw IllegalStateException("返回数据为空")
    }

    suspend fun updateSettings(id: Long, settings: ServerSettings): Result<Pair<String, Boolean>> = daemonResult {
        val normalized = settings.copy(java = normalizeJavaConfig(settings.java))
        val resp = requireApi().updateInstanceSettings(id, normalized)
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "保存失败")
        (resp.message ?: "保存成功") to (resp.data?.needListen == true)
    }

    private fun normalizeJavaConfig(java: String): String {
        val version = java.removePrefix("MSLX://Java/").trim()
        return if (version.isNotBlank() && java.startsWith("MSLX://Java/")) {
            "MSLX://Java/$version"
        } else {
            java.trim()
        }
    }

    suspend fun updateSelf(body: UpdateSelfRequest): Result<String> = daemonResult {
        val resp = requireApi().updateSelf(body)
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "更新用户信息失败")
        resp.message ?: "更新成功"
    }

    suspend fun adminUserList(): Result<List<UserInfo>> = daemonResult {
        val resp = requireApi().adminUserList()
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "获取用户列表失败")
        resp.data.orEmpty()
    }

    suspend fun adminCreateUser(body: AdminCreateUserRequest): Result<String> = daemonResult {
        val resp = requireApi().adminCreateUser(body)
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "创建用户失败")
        resp.message ?: "创建成功"
    }

    suspend fun adminUpdateUser(id: String, body: AdminUpdateUserRequest): Result<String> = daemonResult {
        val resp = requireApi().adminUpdateUser(id, body)
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "更新用户失败")
        resp.message ?: "更新成功"
    }

    suspend fun adminDeleteUser(id: String): Result<String> = daemonResult {
        val resp = requireApi().adminDeleteUser(id)
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "删除用户失败")
        resp.message ?: "删除成功"
    }

    suspend fun frpList(): Result<List<FrpSummary>> = daemonResult {
        val resp = requireApi().frpList()
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "获取 FRP 列表失败")
        resp.data.orEmpty()
    }

    suspend fun pmList(id: Long, mode: String): Result<PmListData> = daemonResult {
        val resp = requireApi().pmList(id, mode)
        val body = resp.body()
        when {
            // 目录不存在(如纯模组服没有插件目录)→ 当作空列表，避免报 404
            resp.code() == 404 -> PmListData()
            !resp.isSuccessful || body == null -> throw IllegalStateException(body?.message ?: "获取列表失败")
            body.code != 200 -> throw IllegalStateException(body.message ?: "获取列表失败")
            else -> body.data ?: PmListData()
        }
    }

    suspend fun pmSet(id: Long, mode: String, action: String, targets: List<String>): Result<String> = daemonResult {
        val resp = requireApi().pmSet(id, PmSetRequest(mode, action, targets))
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "操作失败")
        resp.message ?: "操作成功"
    }

    suspend fun fileContent(id: Long, path: String): Result<String> = daemonResult {
        val resp = requireApi().fileContent(id, path)
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "读取失败")
        resp.data ?: throw IllegalStateException("返回数据为空")
    }

    /** 列出实例目录下的文件/子目录（path 为空表示实例根目录）。 */
    suspend fun fileList(id: Long, path: String = ""): Result<List<FileItem>> = daemonResult {
        val resp = requireApi().fileList(id, path)
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "获取文件列表失败")
        resp.data.orEmpty()
    }

    suspend fun saveFileContent(id: Long, path: String, content: String): Result<String> = daemonResult {
        val resp = requireApi().saveFileContent(id, SaveFileRequest(path, content))
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "保存失败")
        resp.message ?: "保存成功"
    }

    suspend fun userMe(): Result<UserInfo> = daemonResult {
        val resp = requireApi().userMe()
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "获取用户信息失败")
        resp.data ?: throw IllegalStateException("返回数据为空")
    }

    suspend fun uploadInit(): Result<String> = daemonResult {
        val resp = requireApi().uploadInit()
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "初始化上传失败")
        resp.data?.uploadId ?: throw IllegalStateException("未返回 uploadId")
    }

    suspend fun uploadChunk(uploadId: String, index: Int, bytes: ByteArray): Result<Unit> = daemonResult {
        val part = MultipartBody.Part.createFormData(
            "file",
            "chunk_$index",
            bytes.toRequestBody("application/octet-stream".toMediaType()),
        )
        val resp = requireApi().uploadChunk(uploadId, index, part)
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "上传分片失败")
    }

    suspend fun uploadFinish(uploadId: String, totalChunks: Int): Result<String> = daemonResult {
        val resp = requireApi().uploadFinish(uploadId, UploadFinishRequest(totalChunks))
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "合并分片失败")
        resp.message ?: "上传成功"
    }

    suspend fun saveUpload(id: Long, uploadId: String, fileName: String, currentPath: String): Result<String> = daemonResult {
        val resp = requireApi().saveUpload(id, SaveUploadRequest(uploadId, fileName, currentPath))
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "保存文件失败")
        resp.message ?: "保存成功"
    }

    fun createConsoleClient(
        instanceId: Long,
        onLog: (String) -> Unit,
        onCommandResult: (CommandResultPayload) -> Unit,
        onEulaRequired: () -> Unit,
    ): ConsoleHubClient =
        ConsoleHubClient(baseUrl, apiKey, instanceId, onLog, onCommandResult, onEulaRequired)

    /** 创建系统负载监视客户端(订阅 /api/hubs/system 的 ReceiveSystemStats)。 */
    fun createSystemMonitorClient(
        onStats: (com.mslx.console.data.model.NodeStatsPayload) -> Unit,
    ): SystemMonitorClient =
        SystemMonitorClient(baseUrl, apiKey, onStats)

    // ---------------------------------------------------------------- 实例图标

    /** 第三方状态 API 客户端（懒建，避免每个实例重复创建 OkHttpClient）。 */
    private val mcsrvstatApi by lazy { ApiClient.buildMcsrvstatApi() }
    private val mcstatusApi by lazy { ApiClient.buildMcstatusApi() }

    /**
     * 取实例图标（PNG 字节），三级来源依次回退：
     * 1. Daemon 内置端点（实例目录 server-icon.png）；
     * 2. 图标插件（Daemon 侧拉取 + 磁盘缓存 + 第三方查询）；
     * 3. App 直连第三方状态 API（仅当实例在 server.properties 中显式配置了公网地址）。
     * 全部无果返回 null，由调用方展示占位图；缓存策略见 [InstanceIconStore]。
     */
    suspend fun fetchInstanceIcon(id: Long): ByteArray? {
        fetchIconBytes { requireApi().instanceIcon(id) }?.let { return it }
        fetchIconBytes { requireApi().pluginServerIcon(id) }?.let { return it }
        val address = resolvePublicAddress(id) ?: return null
        return fetchThirdPartyIcon(address)
    }

    /** 执行一次图标请求：仅接受 2xx 且 Content-Type 为图片的响应（无图标时端点以 JSON + 4xx 承载业务码）。 */
    private suspend fun fetchIconBytes(call: suspend () -> Response<ResponseBody>): ByteArray? =
        runCatching {
            val response = call()
            if (!response.isSuccessful) return@runCatching null
            if (!response.headers()["Content-Type"].orEmpty().startsWith("image/")) return@runCatching null
            response.body()?.bytes()
        }.getOrNull()

    /** 第三方状态 API：mcsrvstat.us → mcstatus.io 回退，仅取 online + icon 两个字段。 */
    private suspend fun fetchThirdPartyIcon(address: String): ByteArray? {
        val primary = runCatching {
            val resp = mcsrvstatApi.status(address)
            if (resp.online) decodeIconDataUri(resp.icon) else null
        }.getOrNull()
        if (primary != null) return primary
        return runCatching {
            val resp = mcstatusApi.status(address)
            if (resp.online) decodeIconDataUri(resp.icon) else null
        }.getOrNull()
    }

    /** `data:image/png;base64,xxxx` 形式解码为 PNG 字节（格式不符返回 null）。 */
    private fun decodeIconDataUri(dataUri: String?): ByteArray? {
        if (dataUri.isNullOrBlank()) return null
        val marker = dataUri.indexOf("base64,", ignoreCase = true)
        if (marker < 0) return null
        return runCatching { Base64.decode(dataUri.substring(marker + "base64,".length), Base64.DEFAULT) }
            .getOrNull()
    }

    /**
     * 从 server.properties 解析可外发查询的公网地址（`host` 或 `host:port`）。
     * 未配置 server-ip / 私网地址 / 属性读取失败时返回 null（不发外网请求）。
     */
    private suspend fun resolvePublicAddress(id: Long): String? {
        val text = fileContent(id, "server.properties").getOrNull() ?: return null
        var host: String? = null
        var port = 25565
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val split = line.indexOf('=')
            if (split <= 0) return@forEach
            when (line.substring(0, split).trim()) {
                "server-ip" -> host = line.substring(split + 1).trim()
                "server-port" -> line.substring(split + 1).trim().toIntOrNull()
                    ?.takeIf { it in 1..65535 }?.let { port = it }
            }
        }
        val target = host?.takeIf { it.isNotBlank() } ?: return null
        if (!isPublicHost(target)) return null
        return if (port == 25565) target else "$target:$port"
    }

    /** 仅公网地址允许外发：排除私网/回环/链路本地/组播 IPv4 与内网域名（与 Daemon 图标插件同口径）。 */
    private fun isPublicHost(host: String): Boolean {
        if (host.contains(':')) {
            val lower = host.lowercase()
            return !(lower == "::1" || lower.startsWith("fe80") || lower.startsWith("fc") || lower.startsWith("fd"))
        }
        if (host.any { it.isLetter() }) {
            val lower = host.lowercase()
            if (lower == "localhost" || lower.endsWith(".localhost")) return false
            return listOf(".local", ".lan", ".home", ".internal", ".intranet", ".localdomain")
                .none { lower.endsWith(it) }
        }
        val parts = host.split('.')
        if (parts.size != 4) return false
        val bytes = parts.map { it.toIntOrNull()?.takeIf { v -> v in 0..255 } ?: return false }
        return !(
            bytes[0] == 10 ||
                bytes[0] == 127 ||
                (bytes[0] == 172 && bytes[1] in 16..31) ||
                (bytes[0] == 192 && bytes[1] == 168) ||
                (bytes[0] == 169 && bytes[1] == 254) ||
                bytes[0] == 0 ||
                bytes[0] >= 224
            )
    }
}
