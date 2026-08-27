package com.mslx.console.ui.update

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.MSLXApplication
import com.mslx.console.data.AppLogger
import com.mslx.console.data.AppUpdateInfo
import com.mslx.console.data.UpdateChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

data class UpdateUiState(
    val checking: Boolean = false,
    /** 检测到的新版本（非空时 UI 弹窗展示）。 */
    val update: AppUpdateInfo? = null,
    /** 当前应用版本号，如 "1.2.16"。 */
    val currentVersion: String = "",
    /** Actions 渠道：是否正在下载调试 APK。 */
    val downloadingActions: Boolean = false,
    /** Actions 渠道：下载进度 0..1。 */
    val downloadProgress: Float = 0f,
)

class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = getApplication<MSLXApplication>().container.updateRepository
    private val store = getApplication<MSLXApplication>().container.settingsStore

    private val _state = MutableStateFlow(UpdateUiState())
    val state = _state.asStateFlow()

    /** 一次性提示消息（如"已是最新版本"/检查失败）。 */
    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val message = _message.asSharedFlow()

    /** 是否已执行过启动自动检查（避免重复弹窗）。 */
    private var autoChecked = false

    /** 启动检查失败（如断网）后的补偿重试任务：防止"断网进主页后联网"绕过强制更新。 */
    private var retryJob: Job? = null

    init {
        _state.update { it.copy(currentVersion = currentVersion) }
    }

    /** 当前应用版本号，如 "1.2.16"。 */
    private val currentVersion: String
        get() = runCatching {
            getApplication<Application>().packageManager
                .getPackageInfo(getApplication<Application>().packageName, 0)
                .versionName.orEmpty()
        }.getOrDefault("")

    /** 启动时自动检查。仅首次生效；失败后自动进入补偿重试，联网恢复后仍会再次检查。 */
    fun checkOnLaunch() {
        if (autoChecked) return
        autoChecked = true
        check(manual = false)
    }

    /** 手动检查更新（设置页）。 */
    fun checkManually() = check(manual = true)

    private fun check(manual: Boolean) {
        if (_state.value.checking) return
        _state.update { it.copy(checking = true) }
        viewModelScope.launch {
            val channel: UpdateChannel = runCatching { store.settingsFlow.first().updateChannel }
                .getOrDefault(UpdateChannel.STABLE)
            repository.checkLatest(currentVersion, channel).fold(
                onSuccess = { update ->
                    if (update != null) {
                        AppLogger.i(
                            "Update", "发现新版本 ${update.version} beta=${update.beta} force=${update.forceUpdate} actions=${update.actions}"
                        )
                    }
                    _state.update {
                        it.copy(checking = false, update = update)
                    }
                    retryJob?.cancel()
                    retryJob = null
                    if (manual && update == null) {
                        _message.tryEmit(
                            if (channel == UpdateChannel.ACTIONS) {
                                "未找到 Actions 构建，请确认 CI 已成功运行并发布了 dev 版本"
                            } else {
                                "当前已是最新版本"
                            },
                        )
                    }
                },
                onFailure = { e ->
                    _state.update { it.copy(checking = false) }
                    if (manual) {
                        _message.tryEmit("检查更新失败：${e.message ?: "网络错误"}")
                    }
                    // 非手动（启动）检查失败时安排补偿重试，直到成功发现更新或用户跳过后停止
                    if (!manual && _state.value.update == null) {
                        scheduleRetry()
                    }
                },
            )
        }
    }

    /**
     * 补偿重试：每 30 秒重试一次检查，最长 20 分钟（40 次）。
     * 防止"启动时断网 → 进主页后联网"绕过强制更新：一旦联网恢复，重试会拉到新版本并弹窗。
     */
    private fun scheduleRetry() {
        if (retryJob?.isActive == true) return
        retryJob = viewModelScope.launch {
            repeat(40) {
                delay(30_000)
                if (_state.value.update != null) return@launch
                if (_state.value.checking) return@launch
                check(manual = false)
            }
        }
    }

    /** 用户选择"跳过"（关闭弹窗，本次启动不再提示；同时停止补偿重试与 Actions 下载）。 */
    fun skip() {
        retryJob?.cancel()
        retryJob = null
        _state.update { it.copy(update = null, downloadingActions = false, downloadProgress = 0f) }
    }

    /** 应用内下载 APK 并拉起系统安装器（稳定版/测试版/Actions 全部走此路径）。 */
    fun downloadAndInstall() {
        val update = _state.value.update ?: return
        if (_state.value.downloadingActions) return
        val uri = Uri.parse(update.downloadUrl)
        val allowedHosts = setOf("github.com", "www.github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
        val host = uri.host?.lowercase() ?: return
        if (uri.scheme != "https" || host !in allowedHosts) {
            _message.tryEmit("下载地址不合法，已取消")
            return
        }
        _state.update { it.copy(downloadingActions = true, downloadProgress = 0f) }
        viewModelScope.launch {
            val context = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    downloadToFile(context, update.downloadUrl)
                }
            }
            result.onSuccess { file ->
                AppLogger.i("Update", "APK 下载完成 ${file.length()} bytes")
                _state.update { it.copy(downloadingActions = false, update = null, downloadProgress = 0f) }
                installApk(context, file)
            }.onFailure { e ->
                AppLogger.w("Update", "APK 下载失败", e)
                _state.update { it.copy(downloadingActions = false, downloadProgress = 0f) }
                _message.tryEmit("APK 下载失败：${e.message ?: "未知错误"}")
            }
        }
    }

    /** 下载 APK 到 filesDir/apks/app-debug.apk（带进度回调）。 */
    private suspend fun downloadToFile(context: Context, url: String): File {
        val request = okhttp3.Request.Builder().url(url).build()
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("响应为空")
            val total = body.contentLength()
            val dir = File(context.filesDir, "apks").apply { mkdirs() }
            val target = File(dir, "app-debug.apk")
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            _state.update { it.copy(downloadProgress = downloaded.toFloat() / total) }
                        }
                    }
                }
            }
            return target
        }
    }

    /** 通过 FileProvider 拉起系统安装器。 */
    private fun installApk(context: Context, file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { e ->
                AppLogger.w("Update", "打开安装器失败", e)
                _message.tryEmit("无法打开安装器：${e.message ?: "未知错误"}")
            }
    }
}
