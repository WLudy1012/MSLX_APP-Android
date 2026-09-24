package com.mslx.console.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.MSLXApplication
import com.mslx.console.data.InstanceRepository
import com.mslx.console.data.ServerRef
import com.mslx.console.data.ensureRepository
import com.mslx.console.data.model.SERVER_PROPERTIES_SCHEMA
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServerPropertiesUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val missing: Boolean = false,
    val path: String = "server.properties",
    val values: Map<String, String> = emptyMap(),
    val saving: Boolean = false,
)

class ServerPropertiesViewModel(
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

    private val _state = MutableStateFlow(ServerPropertiesUiState())
    val state = _state.asStateFlow()

    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val message = _message.asSharedFlow()

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
            // 1. 从实例设置拿 server.properties 路径
            val path = repository.getSettings(instanceId).getOrNull()
                ?.serverPropertiesPath
                ?.takeIf { it.isNotBlank() }
                ?: "server.properties"

            // 2. 读文件内容
            val contentResult = repository.fileContent(instanceId, path)
            contentResult.fold(
                onSuccess = { content ->
                    _state.update {
                        it.copy(loading = false, path = path, values = parse(content), missing = false)
                    }
                },
                onFailure = {
                    // 文件不存在等：路径已知，可编辑（空表单），保存时会创建
                    _state.update {
                        it.copy(loading = false, path = path, values = emptyMap(), missing = true)
                    }
                },
            )
        }
    }

    fun setValue(key: String, value: String) {
        _state.update { s -> s.copy(values = s.values + (key to value)) }
    }

    fun save() {
        val s = _state.value
        if (s.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            repository.saveFileContent(instanceId, s.path, stringify(s.values)).fold(
                onSuccess = { msg ->
                    _message.tryEmit(msg)
                    _state.update { it.copy(saving = false, missing = false) }
                },
                onFailure = { e ->
                    _message.tryEmit("保存失败：${e.message ?: "未知错误"}")
                    _state.update { it.copy(saving = false) }
                },
            )
        }
    }

    private fun parse(content: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        content.lineSequence().forEach { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@forEach
            val idx = t.indexOf('=')
            if (idx != -1) {
                map[t.substring(0, idx).trim()] = t.substring(idx + 1).trim()
            }
        }
        return map
    }

    private fun stringify(map: Map<String, String>): String {
        val definedKeys = SERVER_PROPERTIES_SCHEMA.map { it.key }
        val sb = StringBuilder()
        sb.append("#Minecraft server properties\n")
        definedKeys.forEach { k -> if (map.containsKey(k)) sb.append("$k=${map[k]}\n") }
        map.keys.forEach { k -> if (k !in definedKeys) sb.append("$k=${map[k]}\n") }
        return sb.toString()
    }
}
