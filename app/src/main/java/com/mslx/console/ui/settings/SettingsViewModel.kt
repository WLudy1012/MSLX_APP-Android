package com.mslx.console.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.MSLXApplication
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
        viewModelScope.launch { store.setTheme(mode, seedColor) }
    }

    fun setUpdateChannel(channel: UpdateChannel) {
        viewModelScope.launch { store.setUpdateChannel(channel) }
    }

    fun setActiveDaemon(id: String) {
        viewModelScope.launch {
            // 立即重新配置 repository，使后续请求指向新 Daemon（无需重启）
            val daemon = store.settingsFlow.first().daemons.firstOrNull { it.id == id }
            if (daemon != null) {
                repository.configure(daemon.baseUrl, daemon.apiKey, daemon.allowHttp)
            }
            store.setActiveDaemon(id)
        }
    }

    fun removeDaemon(id: String) {
        viewModelScope.launch { store.removeDaemon(id) }
    }
}
