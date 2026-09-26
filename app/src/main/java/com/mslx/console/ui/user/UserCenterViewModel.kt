package com.mslx.console.ui.user

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.MSLXApplication
import com.mslx.console.data.DaemonOption
import com.mslx.console.data.InstanceRepository
import com.mslx.console.data.ensureRepository
import com.mslx.console.data.model.AdminCreateUserRequest
import com.mslx.console.data.model.AdminUpdateUserRequest
import com.mslx.console.data.model.UserInfo
import com.mslx.console.data.model.UpdateSelfRequest
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    /** 已配置的服务端（账号体系每台 Daemon 独立，需先选定一台）。 */
    val daemons: List<DaemonOption> = emptyList(),
    /** 当前查看账号信息的服务端。 */
    val selectedDaemonId: String = "",
)

class UserCenterViewModel(application: Application) : AndroidViewModel(application) {

    private val container = getApplication<MSLXApplication>().container

    /** 当前选中 Daemon 的仓储（未命中时为未配置仓储，调用只会得到错误提示而不崩）。 */
    private var repository: InstanceRepository = InstanceRepository()

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
            ensureSelection()
            if (_state.value.selectedDaemonId.isBlank()) {
                _state.update { it.copy(loading = false, error = "尚未配置服务端，请先到设置页添加连接") }
                return@launch
            }
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

    /** 首次进入：默认看「默认 Daemon」的账号体系（无默认则取第一台）。 */
    private suspend fun ensureSelection() {
        if (_state.value.selectedDaemonId.isNotBlank()) {
            repository = container.ensureRepository(_state.value.selectedDaemonId) ?: InstanceRepository()
            return
        }
        val settings = runCatching { container.settingsStore.settingsFlow.first() }.getOrNull()
        val daemons = settings?.daemons.orEmpty()
        val selected = settings?.activeDaemonId?.takeIf { id -> daemons.any { it.id == id } }
            ?: daemons.firstOrNull()?.id
            ?: ""
        repository = container.ensureRepository(selected) ?: InstanceRepository()
        _state.update {
            it.copy(
                daemons = daemons.map { d -> DaemonOption(d.id, d.name) },
                selectedDaemonId = selected,
            )
        }
    }

    /** 切换要查看/管理的服务端：账号与资源列表都属于那台 Daemon。 */
    fun selectDaemon(daemonId: String) {
        if (_state.value.selectedDaemonId == daemonId) return
        _state.update { it.copy(selectedDaemonId = daemonId, user = null, users = emptyList(), resources = emptyList()) }
        load()
    }

    fun updateSelf(
        username: String,
        name: String,
        avatar: String,
        oldPassword: String,
        password: String,
        resetApiKey: Boolean,
    ) {
        if (_state.value.saving) return
        if (password.isNotBlank() && oldPassword.isBlank()) {
            _message.tryEmit("修改密码时必须填写当前密码")
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            repository.updateSelf(
                UpdateSelfRequest(
                    username = username.trim().ifBlank { null },
                    name = name.trim().ifBlank { null },
                    avatar = avatar.trim().ifBlank { null },
                    oldPassword = oldPassword.ifBlank { null },
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
                    _message.emit("更新失败：${com.mslx.console.data.remote.ApiClient.errorMessageFrom(e) ?: e.message ?: "未知错误"}")
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
