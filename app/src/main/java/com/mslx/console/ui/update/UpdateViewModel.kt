package com.mslx.console.ui.update

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.MSLXApplication
import com.mslx.console.data.AppUpdateInfo
import com.mslx.console.data.UpdateChannel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UpdateUiState(
    val checking: Boolean = false,
    /** 检测到的新版本（非空时 UI 弹窗展示）。 */
    val update: AppUpdateInfo? = null,
    /** 当前应用版本号，如 "1.2.7"。 */
    val currentVersion: String = "",
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

    /** 当前应用版本号，如 "1.2.6"。 */
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
                    _state.update {
                        it.copy(checking = false, update = update)
                    }
                    retryJob?.cancel()
                    retryJob = null
                    if (manual && update == null) {
                        _message.tryEmit("当前已是最新版本")
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

    /** 用户选择"跳过"（关闭弹窗，本次启动不再提示；同时停止补偿重试）。 */
    fun skip() {
        retryJob?.cancel()
        retryJob = null
        _state.update { it.copy(update = null) }
    }

    /** 用户选择"更新"：跳转浏览器下载 APK。仅放行 https 且 host 为 GitHub 下载域，防任意 scheme 拉起。 */
    fun openUpdate() {
        val url = _state.value.update?.downloadUrl ?: return
        val uri = Uri.parse(url)
        val allowedHosts = setOf("github.com", "www.github.com", "objects.githubusercontent.com")
        val host = uri.host?.lowercase() ?: return
        if (uri.scheme != "https" || host !in allowedHosts) return
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { getApplication<Application>().startActivity(intent) }
    }
}
