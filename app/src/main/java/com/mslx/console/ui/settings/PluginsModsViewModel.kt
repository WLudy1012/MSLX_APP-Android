package com.mslx.console.ui.settings

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.MSLXApplication
import com.mslx.console.data.InstanceRepository
import com.mslx.console.data.ServerRef
import com.mslx.console.data.ensureRepository
import com.mslx.console.data.model.PmListData
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PluginsModsUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val mode: String = "plugins",
    val data: PmListData? = null,
    val busy: Boolean = false,
)

class PluginsModsViewModel(
    application: Application,
    /** 目标实例：[ServerRef.daemonId] 决定操作哪台服务端（去主连接）。 */
    private val ref: ServerRef,
) : AndroidViewModel(application) {

    private val container = getApplication<MSLXApplication>().container

    /**
     * 目标 Daemon 的连接：首次访问发生在 init 里的 ensureRepository 之后；
     * 服务端已被删除时退化为未配置仓储（返回错误而不是崩溃）。
     */
    private var repository: InstanceRepository =
        container.repositoryFor(ref.daemonId) ?: InstanceRepository()

    private val instanceId: Long get() = ref.remoteIdOrNull ?: -1L

    private val _state = MutableStateFlow(PluginsModsUiState())
    val state = _state.asStateFlow()

    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val message = _message.asSharedFlow()

    init {
        viewModelScope.launch {
            // 进程重建后直接恢复本页时，连接表可能尚未同步：先补齐再加载
            container.ensureRepository(ref.daemonId)?.let { repository = it }
            load()
        }
    }

    fun setMode(mode: String) {
        if (_state.value.mode == mode) return
        _state.update { it.copy(mode = mode) }
        load()
    }

    fun load() {
        // 加载前清空 data，避免切换模式时残留上一模式的旧列表
        _state.update { it.copy(loading = true, error = null, data = null) }
        viewModelScope.launch {
            repository.pmList(instanceId, _state.value.mode).fold(
                onSuccess = { data -> _state.update { it.copy(loading = false, data = data) } },
                onFailure = { e -> _state.update { it.copy(loading = false, error = e.message ?: "加载失败") } },
            )
        }
    }

    /** 启用 ↔ 禁用切换。 */
    fun toggle(fileName: String, currentlyDisabled: Boolean) {
        val action = if (currentlyDisabled) "enable" else "disable"
        runAction(action, listOf(fileName))
    }

    fun batch(action: String) {
        val data = _state.value.data ?: return
        val targets = when (action) {
            "enable" -> data.disableJarFiles
            "disable" -> data.jarFiles + data.clientJarFiles
            else -> emptyList()
        }
        if (targets.isNotEmpty()) runAction(action, targets)
    }

    fun delete(fileName: String) = runAction("delete", listOf(fileName))

    /** 流式分块上传插件/模组文件（避免 readBytes 整文件读入内存）。 */
    fun upload(uri: Uri) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val app = getApplication<MSLXApplication>()
            val result = runCatching {
                val fileName = queryFileName(app, uri) ?: "upload.jar"
                val uploadId = repository.uploadFileStream(
                    input = { app.contentResolver.openInputStream(uri) ?: error("无法读取文件") },
                    totalBytes = queryContentLength(app, uri),
                    onProgress = {},
                ).getOrThrow()
                repository.saveUpload(instanceId, uploadId, fileName, _state.value.mode).getOrThrow()
            }
            result.fold(
                onSuccess = { msg -> _message.tryEmit(msg) },
                onFailure = { e -> _message.tryEmit("上传失败：${e.message ?: "未知错误"}") },
            )
            _state.update { it.copy(busy = false) }
            load()
        }
    }

    /** 查询 Content URI 的文件大小；查询不到返回 0。 */
    private fun queryContentLength(app: Application, uri: Uri): Long =
        runCatching {
            app.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else 0L
                } else 0L
            } ?: 0L
        }.getOrDefault(0L)

    private fun queryFileName(app: Application, uri: Uri): String? =
        app.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }

    private fun runAction(action: String, targets: List<String>) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            repository.pmSet(instanceId, _state.value.mode, action, targets).fold(
                onSuccess = { msg ->
                    _message.tryEmit(msg)
                    load()
                },
                onFailure = { e -> _message.tryEmit("操作失败：${e.message ?: "未知错误"}") },
            )
            _state.update { it.copy(busy = false) }
        }
    }
}
