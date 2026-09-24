package com.mslx.console.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.MSLXApplication
import com.mslx.console.data.InstanceRepository
import com.mslx.console.data.ServerRef
import com.mslx.console.data.ensureRepository
import com.mslx.console.data.model.LocalJava
import com.mslx.console.data.model.ServerSettings
import com.mslx.console.data.remote.UpdateProgressClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class InstanceSettingsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val saving: Boolean = false,
    val updateProgress: Double? = null,
    val updateMessage: String? = null,
    val updateError: Boolean = false,
    val settings: ServerSettings? = null,
    val onlineJavaVersions: List<String> = emptyList(),
    val localJavas: List<LocalJava> = emptyList(),
)

class InstanceSettingsViewModel(
    application: Application,
    /** 目标实例：[ServerRef.daemonId] 决定操作哪台服务端（去主连接）。 */
    private val ref: ServerRef,
) : AndroidViewModel(application) {

    private val container = getApplication<MSLXApplication>().container

    /**
     * 目标 Daemon 的连接：首次访问发生在 init 里的 ensureRepository 之后；
     * 服务端已被删除时退化为未配置仓储（返回错误而不是崩溃）。
     * Java 选项、更新进度也均按此连接获取，不再固定取「当前主连接」。
     */
    private var repository: InstanceRepository =
        container.repositoryFor(ref.daemonId) ?: InstanceRepository()

    private val instanceId: Long get() = ref.remoteIdOrNull ?: -1L

    private val _state = MutableStateFlow(InstanceSettingsUiState())
    val state = _state.asStateFlow()

    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val message = _message.asSharedFlow()

    private var updateClient: UpdateProgressClient? = null

    init {
        viewModelScope.launch {
            // 进程重建后直接恢复本页时，连接表可能尚未同步：先补齐再加载
            container.ensureRepository(ref.daemonId)?.let { repository = it }
            load()
        }
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repository.getSettings(instanceId).fold(
                onSuccess = { settings ->
                    _state.update { it.copy(loading = false, settings = settings) }
                },
                onFailure = { e ->
                    _state.update {
                        it.copy(loading = false, error = e.message ?: "加载失败")
                    }
                },
            )
            loadJavaOptions()
        }
    }

    /** 拉取在线 Java 版本 + 本地 Java 列表。 */
    fun loadJavaOptions() {
        viewModelScope.launch {
            val locals = repository.javaList(refresh = false).getOrDefault(emptyList())
            _state.update { it.copy(localJavas = locals) }

            val status = repository.getStatus().getOrNull()
            val os = status?.systemInfo?.osType?.lowercase()?.replaceFirst("os", "") ?: ""
            val arch = when (status?.systemInfo?.osArchitecture?.lowercase()) {
                "amd64", "x86_64" -> "x64"
                "aarch64" -> "arm64"
                else -> status?.systemInfo?.osArchitecture?.lowercase().orEmpty()
            }
            if (os.isNotBlank() && arch.isNotBlank()) {
                val versions = repository.onlineJavaVersions(os, arch).getOrDefault(emptyList())
                _state.update { it.copy(onlineJavaVersions = versions) }
            }
        }
    }

    /** 修改单个字段（基于当前表单 copy）。 */
    fun update(transform: (ServerSettings) -> ServerSettings) {
        _state.update { s ->
            val cur = s.settings ?: return@update s
            s.copy(settings = transform(cur))
        }
    }

    fun save() {
        val settings = _state.value.settings ?: return
        if (_state.value.saving) return
        _state.update { it.copy(saving = true, updateProgress = null, updateMessage = null, updateError = false) }
        viewModelScope.launch {
            repository.updateSettings(instanceId, settings).fold(
                onSuccess = { result ->
                    val (msg, needListen) = result
                    if (needListen) {
                        startUpdateProgress()
                        _message.tryEmit(msg)
                    } else {
                        _message.tryEmit(msg)
                        _state.update { it.copy(saving = false) }
                    }
                },
                onFailure = { e ->
                    _message.tryEmit("保存失败：${e.message ?: "未知错误"}")
                    _state.update { it.copy(saving = false) }
                },
            )
        }
    }

    private fun startUpdateProgress() {
        updateClient?.disconnect()
        val client = UpdateProgressClient(
            baseUrl = repository.baseUrl,
            apiKey = repository.apiKey,
            instanceId = instanceId,
        ) { message, progress, isError ->
            _state.update {
                it.copy(
                    updateProgress = progress,
                    updateMessage = message,
                    updateError = isError,
                    saving = progress < 100.0 && !isError,
                )
            }
            if (progress >= 100.0 && !isError) {
                _message.tryEmit("配置更新完成")
                updateClient?.disconnect()
            }
        }
        updateClient = client
        try {
            client.connect()
        } catch (e: Exception) {
            _state.update { it.copy(saving = false, updateError = true, updateMessage = "连接更新进度失败：${e.message}") }
        }
    }

    override fun onCleared() {
        updateClient?.disconnect()
        super.onCleared()
    }
}
