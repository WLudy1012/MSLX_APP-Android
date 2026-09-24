package com.mslx.console.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.MSLXApplication
import com.mslx.console.data.AppLogger
import com.mslx.console.data.AppSettings
import com.mslx.console.data.ThemeMode
import com.mslx.console.data.UpdateChannel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
