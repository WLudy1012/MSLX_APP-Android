package com.mslx.console.data

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
import retrofit2.HttpException

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

    /** 配置连接地址并重建 API 客户端(每次连接/切换连接时调用)。 */
    fun configure(baseUrl: String, apiKey: String, allowHttp: Boolean = false) {
        val normalized = ApiClient.normalizeDaemonUrl(baseUrl, allowHttp)
        this.baseUrl = normalized
        this.apiKey = apiKey.trim()
        this.api = ApiClient.build(normalized, this.apiKey)
        AppLogger.i("Repository", "configure: ${normalized.trimEnd('/')} allowHttp=$allowHttp")
    }

    private fun requireApi(): MslxApi =
        api ?: throw IllegalStateException("尚未配置连接信息")

    suspend fun verify(): Result<Unit> = runCatching {
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

    suspend fun getStatus(): Result<StatusData> = runCatching {
        val resp = requireApi().status()
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "获取状态失败")
        resp.data ?: throw IllegalStateException("返回数据为空")
    }

    suspend fun javaList(refresh: Boolean = false): Result<List<LocalJava>> = runCatching {
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

    suspend fun listInstances(): Result<List<InstanceSummary>> = runCatching {
        val resp = requireApi().instanceList()
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "获取实例列表失败")
        resp.data ?: emptyList()
    }

    suspend fun instanceInfo(id: Long): Result<InstanceInfo> = runCatching {
        val resp = requireApi().instanceInfo(id)
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "获取实例信息失败")
        resp.data ?: throw IllegalStateException("返回数据为空")
    }

    suspend fun createInstance(request: CreateServerRequest): Result<String> = runCatching {
        val resp = requireApi().createServer(request)
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "创建失败")
        resp.data?.serverId?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("服务器未返回 ServerId")
    }

    suspend fun deleteInstance(id: Long, deleteFiles: Boolean): Result<String> = runCatching {
        AppLogger.i("Repository", "删除实例 id=$id deleteFiles=$deleteFiles")
        val resp = requireApi().deleteInstance(DeleteServerRequest(id, deleteFiles))
        if (resp.code != 200) {
            // 400 等业务失败（如实例仍在运行）也走异常分支，message 直接透传给 UI
            throw IllegalStateException(resp.message ?: "删除实例失败")
        }
        AppLogger.i("Repository", "删除实例成功 id=$id")
        resp.message ?: "实例已删除"
    }

    suspend fun cancelCreation(serverId: String): Result<String> = runCatching {
        val resp = requireApi().cancelCreation(CancelCreationRequest(serverId))
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "取消失败")
        resp.message ?: "取消信号已发送"
    }

    suspend fun deleteUpload(uploadId: String): Result<Unit> = runCatching {
        requireApi().deleteUpload(uploadId)
    }

    /**
     * 流式分块上传一个文件，返回可用于 createServer 的 fileKey(uploadId)。
     * 从 input 流逐块读取(每块 10MB)上传，避免 readBytes() 把整个文件读入内存；
     * 流读取在 IO 线程执行，避免阻塞主线程。
     */
    suspend fun uploadFileStream(
        input: () -> java.io.InputStream,
        totalBytes: Long,
        onProgress: (Int) -> Unit,
    ): Result<String> = runCatching {
        val uploadId = uploadInit().getOrThrow()
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

    suspend fun sendAction(id: Long, action: String): Result<String> = runCatching {
        val resp = requireApi().action(ActionRequest(id, action))
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "操作失败")
        resp.message ?: "操作成功"
    }

    suspend fun getSettings(id: Long): Result<ServerSettings> = runCatching {
        val resp = requireApi().instanceSettings(id)
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "获取设置失败")
        resp.data ?: throw IllegalStateException("返回数据为空")
    }

    suspend fun updateSettings(id: Long, settings: ServerSettings): Result<Pair<String, Boolean>> = runCatching {
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

    suspend fun updateSelf(body: UpdateSelfRequest): Result<String> = runCatching {
        val resp = requireApi().updateSelf(body)
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "更新用户信息失败")
        resp.message ?: "更新成功"
    }

    suspend fun adminUserList(): Result<List<UserInfo>> = runCatching {
        val resp = requireApi().adminUserList()
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "获取用户列表失败")
        resp.data.orEmpty()
    }

    suspend fun adminCreateUser(body: AdminCreateUserRequest): Result<String> = runCatching {
        val resp = requireApi().adminCreateUser(body)
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "创建用户失败")
        resp.message ?: "创建成功"
    }

    suspend fun adminUpdateUser(id: String, body: AdminUpdateUserRequest): Result<String> = runCatching {
        val resp = requireApi().adminUpdateUser(id, body)
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "更新用户失败")
        resp.message ?: "更新成功"
    }

    suspend fun adminDeleteUser(id: String): Result<String> = runCatching {
        val resp = requireApi().adminDeleteUser(id)
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "删除用户失败")
        resp.message ?: "删除成功"
    }

    suspend fun frpList(): Result<List<FrpSummary>> = runCatching {
        val resp = requireApi().frpList()
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "获取 FRP 列表失败")
        resp.data.orEmpty()
    }

    suspend fun pmList(id: Long, mode: String): Result<PmListData> = runCatching {
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

    suspend fun pmSet(id: Long, mode: String, action: String, targets: List<String>): Result<String> = runCatching {
        val resp = requireApi().pmSet(id, PmSetRequest(mode, action, targets))
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "操作失败")
        resp.message ?: "操作成功"
    }

    suspend fun fileContent(id: Long, path: String): Result<String> = runCatching {
        val resp = requireApi().fileContent(id, path)
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "读取失败")
        resp.data ?: throw IllegalStateException("返回数据为空")
    }

    /** 列出实例目录下的文件/子目录（path 为空表示实例根目录）。 */
    suspend fun fileList(id: Long, path: String = ""): Result<List<FileItem>> = runCatching {
        val resp = requireApi().fileList(id, path)
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "获取文件列表失败")
        resp.data.orEmpty()
    }

    suspend fun saveFileContent(id: Long, path: String, content: String): Result<String> = runCatching {
        val resp = requireApi().saveFileContent(id, SaveFileRequest(path, content))
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "保存失败")
        resp.message ?: "保存成功"
    }

    suspend fun userMe(): Result<UserInfo> = runCatching {
        val resp = requireApi().userMe()
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "获取用户信息失败")
        resp.data ?: throw IllegalStateException("返回数据为空")
    }

    suspend fun uploadInit(): Result<String> = runCatching {
        val resp = requireApi().uploadInit()
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "初始化上传失败")
        resp.data?.uploadId ?: throw IllegalStateException("未返回 uploadId")
    }

    suspend fun uploadChunk(uploadId: String, index: Int, bytes: ByteArray): Result<Unit> = runCatching {
        val part = MultipartBody.Part.createFormData(
            "file",
            "chunk_$index",
            bytes.toRequestBody("application/octet-stream".toMediaType()),
        )
        val resp = requireApi().uploadChunk(uploadId, index, part)
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "上传分片失败")
    }

    suspend fun uploadFinish(uploadId: String, totalChunks: Int): Result<String> = runCatching {
        val resp = requireApi().uploadFinish(uploadId, UploadFinishRequest(totalChunks))
        if (resp.code != 200) throw IllegalStateException(resp.message ?: "合并分片失败")
        resp.message ?: "上传成功"
    }

    suspend fun saveUpload(id: Long, uploadId: String, fileName: String, currentPath: String): Result<String> = runCatching {
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
}
