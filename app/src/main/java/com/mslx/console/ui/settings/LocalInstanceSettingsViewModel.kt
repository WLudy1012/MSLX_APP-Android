package com.mslx.console.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.MSLXApplication
import com.mslx.console.data.AppLogger
import com.mslx.console.data.localengine.InstanceStorage
import com.mslx.console.data.localengine.LocalInstanceMeta
import com.mslx.console.data.localengine.LocalInstanceStore
import com.mslx.console.data.localengine.LocalJreManager
import com.mslx.console.data.localengine.LocalStorage
import com.mslx.console.data.localengine.ServerFiles
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

/**
 * 全局默认（设置 → 本机运行时与开服设置）里影响启动的性能项。
 *
 * 实例标记为「跟随全局」时（[LocalInstanceMeta.inheritGlobal]），这些值才是真正生效的，
 * 页面据此把输入框显示成全局值并在保存时回写一份，避免 instance.json 里留着过期数字。
 */
data class LocalGlobalDefaults(
    val minMemMb: Int = 1024,
    val maxMemMb: Int = 2048,
    val jvmArgs: String = "",
    val keepAlive: Boolean = true,
    val useSerialGc: Boolean = true,
)

data class LocalInstanceSettingsState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    val dirName: String = "",
    val meta: LocalInstanceMeta? = null,
    val runtimeOptions: List<LocalRuntimeOption> = emptyList(),
    val jreAbi: String = "",
    /** 全局默认值（「跟随全局」时的真实生效值）。 */
    val globals: LocalGlobalDefaults = LocalGlobalDefaults(),
    /** 实例实际存放位置（公共目录 / 应用私有）。 */
    val storage: InstanceStorage = InstanceStorage.PRIVATE,
    /** 实例目录展示路径：公共给绝对路径，私有给相对标识。 */
    val dirPath: String = "",
    /** 当前是否已拿到「所有文件访问」：公共目录实例未授权时启动会被拦下，页面需提前告警。 */
    val publicStorageGranted: Boolean = false,
) {
    /**
     * 实际生效的启动参数：[LocalInstanceMeta.inheritGlobal] 时取全局默认，
     * 页面上的内存/JVM 参数/GC/保活输入框按这份显示，避免“写了不生效”的误导。
     */
    val effective: LocalInstanceMeta?
        get() = meta?.let { m ->
            if (!m.inheritGlobal) m else m.copy(
                minMemMb = globals.minMemMb,
                maxMemMb = globals.maxMemMb,
                jvmArgs = globals.jvmArgs,
                keepAlive = globals.keepAlive,
                useSerialGc = globals.useSerialGc,
            )
        }
}

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

    /** 全局默认值的来源（设置 → 本机运行时与开服设置）。 */
    private val store = getApplication<MSLXApplication>().container.settingsStore

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
            val storage = LocalInstanceStore.storageOf(context, dirName)
            val globals = runCatching {
                store.settingsFlow.first().let {
                    LocalGlobalDefaults(
                        minMemMb = it.localMinMemMb,
                        maxMemMb = it.localMaxMemMb,
                        jvmArgs = it.localJvmArgs,
                        keepAlive = it.localKeepAlive,
                        useSerialGc = it.localUseSerialGc,
                    )
                }
            }.getOrDefault(LocalGlobalDefaults())
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
                it.copy(
                    loading = false,
                    // meta 里可能没回写过 storage（旧实例），以目录实际所在根为准
                    meta = meta.copy(storage = storage.key),
                    runtimeOptions = options,
                    jreAbi = abi,
                    globals = globals,
                    storage = storage,
                    dirPath = LocalStorage.previewPath(storage, dirName),
                    publicStorageGranted = LocalStorage.publicStorageGranted(),
                )
            }
        }
    }

    fun update(transform: (LocalInstanceMeta) -> LocalInstanceMeta) {
        _state.update { s -> s.copy(meta = s.meta?.let(transform)) }
    }

    /**
     * 切换「跟随全局默认 / 独立覆盖」。
     * 关闭跟随时先把当前显示的全局值快照进实例，避免开关一拨参数就跳回旧数据。
     */
    fun setInheritGlobal(enabled: Boolean) {
        _state.update { s ->
            val m = s.meta ?: return@update s
            val next = if (enabled) {
                m.copy(inheritGlobal = true)
            } else {
                (s.effective ?: m).copy(inheritGlobal = false)
            }
            s.copy(meta = next)
        }
    }

    /** 从系统授权页回到前台时重读「所有文件访问」状态。 */
    fun refreshStorageAccess() {
        _state.update { it.copy(publicStorageGranted = LocalStorage.publicStorageGranted()) }
    }

    /** 打开系统「所有文件访问」授权页；按包跳转不可用时退回总页面，都失败返回 false。 */
    fun openPublicStorageSettings(): Boolean {
        val context = getApplication<Application>()
        return runCatching { context.startActivity(LocalStorage.publicStorageSettingsIntent(context)) }.isSuccess ||
            runCatching { context.startActivity(LocalStorage.publicStorageSettingsFallbackIntent()) }.isSuccess
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
                // 跟随全局时把生效中的全局值镜像进 instance.json（[LocalServerManager] 启动仍以全局为准），
                // 否则文件里会留着创建时的过期数字，用文件管理器看时容易误判
                val toSave = s.effective ?: meta
                // 写 instance.json（complete 幂等补齐缺失文件、更新元数据时间戳）
                ServerFiles.complete(dir, toSave).getOrThrow()
                // 同步 server.properties 的关键可见项
                ServerFiles.patchServerProperties(dir, toSave).getOrThrow()
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
