package com.mslx.console.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.data.AppLogger
import com.mslx.console.data.localengine.LocalInstanceMeta
import com.mslx.console.data.localengine.LocalInstanceStore
import com.mslx.console.data.localengine.LocalJreManager
import com.mslx.console.data.localengine.ServerFiles
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** 运行时版本选项（本机实例设置里的 Java 版本下拉）。 */
data class LocalRuntimeOption(
    val id: String,
    val label: String,
    val installed: Boolean,
    val supported: Boolean = true,
)

data class LocalInstanceSettingsState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    val dirName: String = "",
    val meta: LocalInstanceMeta? = null,
    val runtimeOptions: List<LocalRuntimeOption> = emptyList(),
    val jreAbi: String = "",
)

/**
 * 本机实例设置：直接编辑私有目录下的 `instance.json`（[LocalInstanceMeta]）与服务端 `server.properties`，
 * 不走 Daemon / SignalR。核心/世界文件通过「文件管理（本机）」查看编辑。
 */
class LocalInstanceSettingsViewModel(
    application: Application,
    private val dirName: String,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(LocalInstanceSettingsState(dirName = dirName))
    val state = _state.asStateFlow()

    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val message = _message.asSharedFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val context = getApplication<Application>()
            val meta = LocalInstanceStore.load(context, dirName)
            if (meta == null) {
                _state.update { it.copy(loading = false, error = "实例不存在：$dirName") }
                return@launch
            }
            val abi = LocalJreManager.currentAbi()
            val installable = LocalJreManager.installableRuntimes(abi)
            // 实例已保存但当前不可安装的运行时（如 Java 8）仍列入选项，
            // 否则下拉会默默显示成默认值，看不出实例原本配的是哪个
            val saved = LocalJreManager.runtimeById(meta.runtimeId.ifBlank { null })
            val runtimes = if (saved in installable) installable else installable + saved
            val options = runtimes.map { rt ->
                LocalRuntimeOption(
                    id = rt.id,
                    label = rt.label,
                    installed = LocalJreManager.isInstalled(context, rt),
                    supported = rt.supported,
                )
            }
            _state.update {
                it.copy(loading = false, meta = meta, runtimeOptions = options, jreAbi = abi)
            }
        }
    }

    fun update(transform: (LocalInstanceMeta) -> LocalInstanceMeta) {
        _state.update { s -> s.copy(meta = s.meta?.let(transform)) }
    }

    fun selectRuntime(id: String) {
        val rt = LocalJreManager.runtimeById(id)
        // 暂无 Android 构建的运行时（如 Java 8）不受理，避免存下一个永远装不上的运行时
        if (!rt.supported) {
            _message.tryEmit("${rt.label} 暂无 Android 构建，无法选用")
            return
        }
        _state.update { s ->
            s.copy(meta = s.meta?.copy(runtimeId = rt.id, javaMajor = rt.javaMajor, abi = LocalJreManager.currentAbi()))
        }
    }

    fun save() {
        val s = _state.value
        val meta = s.meta ?: return
        if (s.saving) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            runCatching {
                val context = getApplication<Application>()
                val dir = LocalInstanceStore.dir(context, dirName)
                if (!dir.isDirectory) error("实例目录不存在")
                // 写 instance.json（complete 幂等补齐缺失文件、更新元数据时间戳）
                ServerFiles.complete(dir, meta).getOrThrow()
                // 同步 server.properties 的关键可见项
                ServerFiles.patchServerProperties(dir, meta).getOrThrow()
            }.onSuccess {
                AppLogger.i("LocalInstanceSettings", "已保存本机实例设置：$dirName")
                _state.update { it.copy(saving = false) }
                _message.tryEmit("已保存")
            }.onFailure { e ->
                AppLogger.w("LocalInstanceSettings", "保存本机实例设置失败", e)
                _state.update { it.copy(saving = false, error = "保存失败：${e.message}") }
                _message.tryEmit("保存失败：${e.message}")
            }
        }
    }

    /** 实例目录（供文件管理入口使用）。 */
    fun instanceDir(): File = LocalInstanceStore.dir(getApplication(), dirName)

    /** 读取实例目录下的文本文件（不存在返回空串；带路径穿越防护）。 */
    fun readFile(name: String): String {
        val dir = instanceDir()
        val file = File(dir, name)
        if (!file.canonicalPath.startsWith(dir.canonicalPath + File.separator)) return ""
        return runCatching { if (file.isFile) file.readText() else "" }.getOrDefault("")
    }

    /** 写入实例目录下的文本文件（带路径穿越防护）。 */
    fun writeFile(name: String, content: String): Boolean {
        val dir = instanceDir()
        val file = File(dir, name)
        if (!file.canonicalPath.startsWith(dir.canonicalPath + File.separator)) return false
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(content)
            true
        }.getOrElse {
            AppLogger.w("LocalInstanceSettings", "写文件失败：$name", it)
            false
        }
    }
}
