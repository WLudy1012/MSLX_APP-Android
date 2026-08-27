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
    private val repository = container.instanceRepository

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

    fun setActiveDaemon(id: String) {
        viewModelScope.launch {
            // 立即重新配置 repository，使后续请求指向新 Daemon（无需重启）
            val settings = runCatching { store.settingsFlow.first() }
                .onFailure { AppLogger.w("Settings", "读取设置失败", it) }
                .getOrNull()
            val daemon = settings?.daemons?.firstOrNull { it.id == id }
            if (daemon != null) {
                repository.configure(daemon.baseUrl, daemon.apiKey, daemon.allowHttp)
            }
            runCatching { store.setActiveDaemon(id) }
                .onFailure { AppLogger.w("Settings", "保存激活 Daemon 失败", it) }
        }
    }

    fun removeDaemon(id: String) {
        viewModelScope.launch {
            runCatching { store.removeDaemon(id) }
                .onFailure { AppLogger.w("Settings", "删除 Daemon 失败", it) }
        }
    }
}
