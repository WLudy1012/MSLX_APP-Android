package com.mslx.console.ui.user

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.MSLXApplication
import com.mslx.console.data.model.AdminCreateUserRequest
import com.mslx.console.data.model.AdminUpdateUserRequest
import com.mslx.console.data.model.UserInfo
import com.mslx.console.data.model.UpdateSelfRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

 data class ResourceOption(
    val value: String,
    val label: String,
)

data class UserCenterUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val user: UserInfo? = null,
    val users: List<UserInfo> = emptyList(),
    val resources: List<ResourceOption> = emptyList(),
    val saving: Boolean = false,
)

class UserCenterViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = getApplication<MSLXApplication>().container.instanceRepository

    private val _state = MutableStateFlow(UserCenterUiState())
    val state = _state.asStateFlow()

    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val message = _message.asSharedFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            repository.userMe().fold(
                onSuccess = { user ->
                    _state.update { it.copy(loading = false, user = user) }
                    if (user.role.equals("admin", ignoreCase = true) || user.isSystemUser) loadAdminData()
                },
                onFailure = { e ->
                    _state.update { it.copy(loading = false, error = e.message ?: "获取失败") }
                },
            )
        }
    }

    fun updateSelf(
        username: String,
        name: String,
        avatar: String,
        password: String,
        resetApiKey: Boolean,
    ) {
        if (_state.value.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            repository.updateSelf(
                UpdateSelfRequest(
                    username = username.trim().ifBlank { null },
                    name = name.trim().ifBlank { null },
                    avatar = avatar.trim().ifBlank { null },
                    password = password.ifBlank { null },
                    resetApiKey = resetApiKey,
                ),
            ).fold(
                onSuccess = { msg ->
                    _message.emit(msg)
                    _state.update { it.copy(saving = false) }
                    load()
                },
                onFailure = { e ->
                    _message.emit("更新失败：${e.message ?: "未知错误"}")
                    _state.update { it.copy(saving = false) }
                },
            )
        }
    }

    fun createUser(username: String, password: String, name: String, role: String, resources: List<String>) {
        if (_state.value.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            repository.adminCreateUser(
                AdminCreateUserRequest(username.trim(), password, name.trim().ifBlank { null }, role, resources),
            ).fold(
                onSuccess = { msg ->
                    _message.emit(msg)
                    _state.update { it.copy(saving = false) }
                    loadAdminData()
                },
                onFailure = { e ->
                    _message.emit("创建失败：${e.message ?: "未知错误"}")
                    _state.update { it.copy(saving = false) }
                },
            )
        }
    }

    fun updateUser(
        id: String,
        name: String,
        password: String,
        role: String,
        resetApiKey: Boolean,
        resources: List<String>,
    ) {
        if (_state.value.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            repository.adminUpdateUser(
                id,
                AdminUpdateUserRequest(
                    name = name.trim().ifBlank { null },
                    password = password.ifBlank { null },
                    role = role,
                    resetApiKey = resetApiKey,
                    resources = resources,
                ),
            ).fold(
                onSuccess = { msg ->
                    _message.emit(msg)
                    _state.update { it.copy(saving = false) }
                    loadAdminData()
                },
                onFailure = { e ->
                    _message.emit("更新失败：${e.message ?: "未知错误"}")
                    _state.update { it.copy(saving = false) }
                },
            )
        }
    }

    fun deleteUser(id: String) {
        viewModelScope.launch {
            repository.adminDeleteUser(id).fold(
                onSuccess = { msg ->
                    _message.emit(msg)
                    loadAdminData()
                },
                onFailure = { e -> _message.emit("删除失败：${e.message ?: "未知错误"}") },
            )
        }
    }

    private fun loadAdminData() {
        viewModelScope.launch {
            val users = repository.adminUserList()
            val instances = repository.listInstances().getOrDefault(emptyList())
            val frps = repository.frpList().getOrDefault(emptyList())
            val options = instances.map { ResourceOption("server:${it.id}", "实例：${it.name ?: it.id}") } +
                frps.map { ResourceOption("frp:${it.id}", "FRP：${it.name ?: it.id}") }
            users.onSuccess { list -> _state.update { it.copy(users = list, resources = options) } }
                .onFailure { e -> _message.emit("获取用户列表失败：${e.message ?: "未知错误"}") }
        }
    }
}
