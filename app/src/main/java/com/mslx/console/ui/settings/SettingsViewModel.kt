package com.mslx.console.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.MSLXApplication
import com.mslx.console.data.AppLogger
import com.mslx.console.data.AppSettings
import com.mslx.console.data.ThemeMode
import com.mslx.console.data.UpdateChannel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val container = getApplication<MSLXApplication>().container
    private val store = container.settingsStore

    val settings = store.settingsFlow.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppSettings(),
    )

    fun setTheme(mode: ThemeMode, seedColor: Long) {
        viewModelScope.launch {
            runCatching { store.setTheme(mode, seedColor) }
                .onFailure { AppLogger.w("Settings", "保存主题失败", it) }
        }
    }

    /** 调整毛玻璃面板不透明度（保存后全应用实时生效）。 */
    fun setGlassAlpha(alpha: Float) {
        viewModelScope.launch {
            runCatching { store.setGlassAlpha(alpha) }
                .onFailure { AppLogger.w("Settings", "保存毛玻璃透明度失败", it) }
        }
    }

    /**
     * 导入自定义背景图：拷贝进应用私有目录（filesDir/theme）后保存路径。
     * [onDone] 回传保存路径，读取/写入失败时回传 null（调用方给 Snackbar 提示）。
     */
    fun importThemeBackground(dark: Boolean, uri: Uri, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val path = runCatching {
                withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    val dir = File(app.filesDir, "theme").apply { mkdirs() }
                    val target = File(dir, if (dark) "background_dark" else "background_light")
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: error("无法读取所选图片")
                    target.absolutePath
                }
            }.onFailure { AppLogger.w("Settings", "导入背景图失败", it) }.getOrNull()
            if (path != null) {
                runCatching { store.setThemeBackground(dark, path) }
                    .onFailure { AppLogger.w("Settings", "保存背景图路径失败", it) }
            }
            onDone(path)
        }
    }

    /** 一键恢复默认外观（清空深浅两套背景图 + 恢复默认透明度）。 */
    fun resetGlassAppearance() {
        viewModelScope.launch {
            runCatching { store.resetGlassAppearance() }
                .onFailure { AppLogger.w("Settings", "重置外观失败", it) }
        }
    }

    /** 清除指定模式的背景图（该模式回退到主题色渐变底）。 */
    fun clearThemeBackground(dark: Boolean) {
        viewModelScope.launch {
            runCatching { store.setThemeBackground(dark, "") }
                .onFailure { AppLogger.w("Settings", "清除背景图失败", it) }
        }
    }

    fun setUpdateChannel(channel: UpdateChannel) {
        viewModelScope.launch {
            runCatching { store.setUpdateChannel(channel) }
                .onFailure { AppLogger.w("Settings", "保存更新渠道失败", it) }
        }
    }

    /** 切换「默认 Daemon」（仅影响新建实例落点与主页首屏，不改变任何连接的优先级）。 */
    fun setActiveDaemon(id: String) {
        viewModelScope.launch {
            runCatching { store.setActiveDaemon(id) }
                .onFailure { AppLogger.w("Settings", "保存默认 Daemon 失败", it) }
            syncRegistry()
        }
    }

    fun removeDaemon(id: String) {
        viewModelScope.launch {
            runCatching { store.removeDaemon(id) }
                .onFailure { AppLogger.w("Settings", "删除 Daemon 失败", it) }
            syncRegistry()
        }
    }

    /** 把最新设置同步到连接注册表（新增/删除/改地址都在此生效，无需重启）。 */
    private suspend fun syncRegistry() {
        val settings = runCatching { store.settingsFlow.first() }.getOrNull() ?: return
        runCatching { container.daemonRegistry.sync(settings) }
            .onFailure { AppLogger.w("Settings", "同步 Daemon 连接失败", it) }
    }
}
