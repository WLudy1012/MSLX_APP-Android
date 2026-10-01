package com.wludy.rolithax.launcher.ui.servers

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import com.wludy.rolithax.launcher.RolithaxApplication
import com.wludy.rolithax.launcher.data.AppSettings
import com.wludy.rolithax.launcher.data.ManagedServer
import com.wludy.rolithax.launcher.data.ServerRef
import com.wludy.rolithax.launcher.data.ensureRepository
import com.wludy.rolithax.launcher.data.localengine.LocalInstanceStore
import com.wludy.rolithax.launcher.data.model.ResourceInstallBatchRequest
import com.wludy.rolithax.launcher.data.model.ResourceInstallItemRequest
import com.wludy.rolithax.launcher.data.resources.LocalResourceInstaller
import com.wludy.rolithax.launcher.data.resources.ResourceCartEntry
import com.wludy.rolithax.launcher.data.resources.ResourceFilter
import com.wludy.rolithax.launcher.data.resources.ResourceIssue
import com.wludy.rolithax.launcher.data.resources.ResourceProject
import com.wludy.rolithax.launcher.data.resources.ResourceRepository
import com.wludy.rolithax.launcher.data.resources.ResourceVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ResourceCenterUiState(
    val loadingTargets: Boolean = true,
    val servers: List<ManagedServer> = emptyList(),
    val selectedServerKey: String? = null,
    val gameVersion: String = "",
    val loader: String = "",
    val category: String = "mod",
    val providerId: String = "modrinth",
    val query: String = "",
    val serverOnly: Boolean = true,
    val cores: List<ResourceProject> = emptyList(),
    val cart: List<ResourceCartEntry> = emptyList(),
    val selectedProject: ResourceProject? = null,
    val versions: List<ResourceVersion> = emptyList(),
    val loadingVersions: Boolean = false,
    val showTargetPicker: Boolean = false,
    val showCart: Boolean = false,
    val issues: List<ResourceIssue> = emptyList(),
    val cartValidated: Boolean = false,
    val validatingCart: Boolean = false,
    val installing: Boolean = false,
    val installProgress: Float = 0f,
    val installMessage: String = "",
    val message: String? = null,
)

class ResourceCenterViewModel(application: Application) : AndroidViewModel(application) {
    private val container = getApplication<RolithaxApplication>().container
    private val repository: ResourceRepository = container.resourceRepository
    private val installer = LocalResourceInstaller()
    private val filter = MutableStateFlow(ResourceFilter(type = "mod", serverOnly = true))
    private var searchJob: Job? = null
    private var installJob: Job? = null
    private var remoteInstall: Pair<com.wludy.rolithax.launcher.data.InstanceRepository, String>? = null

    private val _state = MutableStateFlow(ResourceCenterUiState())
    val state = _state.asStateFlow()
    @OptIn(ExperimentalCoroutinesApi::class)
    val projects = filter.flatMapLatest(repository::projects).cachedIn(viewModelScope)

    init {
        loadTargets()
        loadMslCores()
    }

    fun setCategory(type: String) {
        _state.update { it.copy(category = type) }
        updateFilter()
    }

    fun setServerOnly(enabled: Boolean) {
        _state.update { it.copy(serverOnly = enabled) }
        updateFilter()
    }

    fun setSearch(value: String) {
        _state.update { it.copy(query = value) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(320)
            updateFilter()
        }
    }

    fun showTargetPicker() = _state.update { it.copy(showTargetPicker = true) }
    fun hideTargetPicker() = _state.update { it.copy(showTargetPicker = false) }
    fun openCart() = _state.update { it.copy(showCart = true, message = null) }
    fun closeCart() = _state.update { it.copy(showCart = false) }

    fun selectServer(server: ManagedServer) {
        val ref = server.ref ?: return
        val (version, loader) = if (ref.isLocal) {
            val meta = LocalInstanceStore.load(getApplication(), ref.instanceId)
            meta?.coreVersion.orEmpty() to loaderFor(meta?.core.orEmpty())
        } else {
            parseVersion(server.detail) to loaderFor(server.detail)
        }
        _state.update {
            it.copy(
                selectedServerKey = server.key,
                gameVersion = version,
                loader = loader,
                showTargetPicker = false,
                cart = emptyList(),
                issues = emptyList(),
                cartValidated = false,
            )
        }
        updateFilter()
        refreshCart(server.key)
    }

    fun openVersions(project: ResourceProject) {
        _state.update { it.copy(selectedProject = project, versions = emptyList(), loadingVersions = true, message = null) }
        viewModelScope.launch {
            val current = _state.value
            val result = repository.versions(
                project.id,
                ResourceFilter(project.type, gameVersion = current.gameVersion, loader = current.loader, providerId = current.providerId),
            )
            _state.update { it.copy(versions = result, loadingVersions = false) }
        }
    }

    fun closeVersions() = _state.update { it.copy(selectedProject = null, versions = emptyList()) }

    fun addVersion(version: ResourceVersion) {
        val current = _state.value
        if (current.validatingCart) return
        val project = current.selectedProject ?: return
        val targetKey = current.selectedServerKey
        if (targetKey == null) {
            _state.update { it.copy(message = "先选择要安装资源的实例") }
            return
        }
        _state.update { it.copy(cartValidated = false, issues = emptyList()) }
        viewModelScope.launch {
            repository.addToCart(targetKey, project, version, current.providerId)
            refreshCart(targetKey)
            _state.update { it.copy(selectedProject = null, versions = emptyList(), message = "已加入购物清单") }
        }
    }

    fun removeFromCart(providerId: String, projectId: String) {
        if (_state.value.validatingCart) return
        val key = _state.value.selectedServerKey ?: return
        _state.update { it.copy(cartValidated = false, issues = emptyList()) }
        viewModelScope.launch {
            repository.removeFromCart(key, providerId, projectId)
            refreshCart(key)
        }
    }

    fun reviewCart() {
        val snapshot = _state.value
        if (snapshot.validatingCart || snapshot.installing) return
        val targetKey = snapshot.selectedServerKey ?: run {
            _state.update { it.copy(message = "先选择要安装资源的实例") }
            return
        }
        _state.update { it.copy(validatingCart = true, cartValidated = false, issues = emptyList(), message = null) }
        viewModelScope.launch {
            try {
                val issues = repository.validateCart(targetKey, snapshot.cart, snapshot.gameVersion, snapshot.loader)
                val validatedCart = repository.cart(targetKey)
                _state.update {
                    if (it.selectedServerKey == targetKey && it.cart == snapshot.cart) {
                        it.copy(
                            cart = validatedCart,
                            validatingCart = false,
                            cartValidated = true,
                            showCart = true,
                            issues = issues,
                        )
                    } else {
                        it.copy(validatingCart = false, cartValidated = false)
                    }
                }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(validatingCart = false, cartValidated = false) }
                throw cancelled
            } catch (error: Throwable) {
                _state.update {
                    it.copy(
                        validatingCart = false,
                        cartValidated = false,
                        message = "清单检查失败：${error.message ?: "未知错误"}",
                    )
                }
            }
        }
    }

    fun installCart() {
        if (installJob?.isActive == true) return
        val current = _state.value
        val target = current.selectedServerKey?.let(ServerRef::fromCatalogKey)
        if (target == null) {
            _state.update { it.copy(message = "先选择要安装资源的实例") }
            return
        }
        if (current.cart.isEmpty()) {
            _state.update { it.copy(message = "购物清单为空") }
            return
        }
        if (!current.cartValidated) {
            _state.update { it.copy(message = "请先检查清单中的依赖与冲突") }
            return
        }
        val blockers = current.issues.filter { it.severity == ResourceIssue.Severity.BLOCKING }
        if (blockers.isNotEmpty()) return
        installJob = viewModelScope.launch {
            _state.update { it.copy(installing = true, installProgress = 0f, installMessage = "准备安装") }
            try {
                val installed = if (target.isLocal) {
                    installer.install(getApplication(), target, current.cart) { progress, message ->
                        _state.update { it.copy(installProgress = progress, installMessage = message) }
                    }
                } else {
                    installRemote(target, current)
                }
                repository.clearCart(target.catalogKey)
                refreshCart(target.catalogKey)
                _state.update { it.copy(installing = false, showCart = false, issues = emptyList(), message = "已安装 ${installed.size} 个文件") }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(installing = false, installMessage = "正在取消安装") }
                throw cancelled
            } catch (error: Throwable) {
                _state.update { it.copy(installing = false, message = "安装失败：${error.message ?: "未知错误"}") }
            } finally {
                remoteInstall = null
            }
        }
    }

    fun cancelInstall() {
        installJob?.cancel()
        val activeRemote = remoteInstall
        if (activeRemote != null) {
            viewModelScope.launch { activeRemote.first.cancelResourceInstall(activeRemote.second) }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private suspend fun installRemote(target: ServerRef, current: ResourceCenterUiState): List<String> {
        val instanceId = target.remoteIdOrNull ?: error("Daemon 实例编号无效")
        val daemon = container.ensureRepository(target.daemonId) ?: error("Daemon 尚未连接")
        val request = ResourceInstallBatchRequest(
            instanceId = instanceId,
            gameVersion = current.gameVersion,
            loader = current.loader,
            items = current.cart.map { entry ->
                ResourceInstallItemRequest(entry.project.id, entry.version.id, entry.project.type, entry.providerId)
            },
        )
        val task = daemon.installResources(request).getOrThrow()
        remoteInstall = daemon to task.id
        var result = task
        var polls = 0
        while (result.status == "running" && polls++ < MAX_REMOTE_POLLS) {
            delay(900)
            result = daemon.resourceInstallTask(task.id).getOrThrow()
            _state.update {
                it.copy(
                    installProgress = result.progress / 100f,
                    installMessage = result.message.ifBlank { "Daemon 正在安装" },
                )
            }
        }
        check(result.status == "completed") { result.message.ifBlank { "Daemon 资源安装失败" } }
        return result.installedFiles
    }

    private fun loadTargets() {
        viewModelScope.launch {
            val settings = runCatching { container.settingsStore.settingsFlow.first() }.getOrDefault(AppSettings())
            container.daemonRegistry.sync(settings)
            val servers = runCatching { container.serverCatalog.load(settings) }.getOrDefault(emptyList())
            _state.update { it.copy(loadingTargets = false, servers = servers) }
            if (servers.size == 1) selectServer(servers.first())
        }
    }

    private fun loadMslCores() {
        viewModelScope.launch {
            val cores = repository.mslApiServerCores()
            _state.update { it.copy(cores = cores) }
        }
    }

    private fun refreshCart(key: String) {
        viewModelScope.launch {
            val cart = repository.cart(key)
            _state.update { if (it.selectedServerKey == key) it.copy(cart = cart) else it }
        }
    }

    private fun updateFilter() {
        val state = _state.value
        filter.value = ResourceFilter(
            type = state.category,
            query = state.query,
            gameVersion = state.gameVersion,
            loader = state.loader,
            serverOnly = state.serverOnly,
            providerId = state.providerId,
        )
    }

    private fun parseVersion(value: String): String = Regex("1\\.\\d+(?:\\.\\d+)?|26\\.\\d+(?:\\.\\d+)?")
        .find(value)?.value.orEmpty()

    private fun loaderFor(value: String): String {
        val text = value.lowercase()
        return when {
            "neoforge" in text -> "neoforge"
            "fabric" in text -> "fabric"
            "quilt" in text -> "quilt"
            "forge" in text -> "forge"
            "paper" in text -> "paper"
            "spigot" in text -> "spigot"
            "bukkit" in text -> "bukkit"
            else -> ""
        }
    }

    companion object {
        private const val MAX_REMOTE_POLLS = 6000
    }
}
