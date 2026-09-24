package com.mslx.console.ui.home

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mslx.console.MSLXApplication
import com.mslx.console.data.AppSettings
import com.mslx.console.data.AppLogger
import com.mslx.console.data.DaemonState
import com.mslx.console.data.DaemonStatus
import com.mslx.console.data.InstanceIcons
import com.mslx.console.data.InstanceRepository
import com.mslx.console.data.ManagedServer
import com.mslx.console.data.ServerRef
import com.mslx.console.data.model.NodeStatsPayload
import com.mslx.console.data.remote.ApiClient
import com.mslx.console.data.remote.SystemMonitorClient
import com.mslx.console.ui.ServerNotificationHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 开服/关服通知条目（去主连接：以 [ServerRef] 定位，本机与远程走同一条通路）。 */
data class ServerNotification(
    val ref: ServerRef,
    val instanceName: String,
    val sourceLabel: String,
    val isOpened: Boolean, // true=开服(变为运行中), false=关服(离开运行中)
    val time: Long,
)

/**
 * 主页 Daemon 卡片的一页数据：一台服务端一张卡，由 [HorizontalPager] 左右滑动切换。
 * 各 Daemon 完全对等，[isDefault] 只表示「新建实例的默认落点」，不代表连接优先级。
 */
data class DaemonPage(
    val daemonId: String,
    val name: String,
    val baseUrl: String,
    val protocol: String,
    val isDefault: Boolean,
    /** null = 尚未探测。 */
    val online: Boolean?,
    val latencyMs: Long?,
    val version: String,
    val osText: String,
    val instanceTotal: Int,
    val instanceRunning: Int,
    val onlinePlayers: Int,
    val cpu: Double?,
    val memoryPercent: Double?,
    val memoryUsedGb: Double?,
    val memoryTotalGb: Double?,
    val message: String?,
    val checkedAt: Long,
)

data class HomeUiState(
    /** 首轮加载（尚未拿到任何 Daemon 状态）。 */
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val pages: List<DaemonPage> = emptyList(),
    /** 当前滑到的那台 Daemon。 */
    val selectedDaemonId: String = "",
    /** true = 实例列表只显示当前卡片那台服务端的实例（滑动联动的显式版）。 */
    val onlySelected: Boolean = false,
    /** 聚合实例列表：本机实例 + 所有 Daemon 的实例。 */
    val servers: List<ManagedServer> = emptyList(),
    /** 实例图标（key = [ManagedServer.key]）；未加载到的不显示（占位状态点）。 */
    val icons: Map<String, Bitmap> = emptyMap(),
    val notifications: List<ServerNotification> = emptyList(),
    // 一言金句
    val quote: String = "",
    val quoteSource: String = "",
    val quoteFailed: Boolean = false,
    val error: String? = null,
) {
    val hasDaemons: Boolean get() = pages.isNotEmpty()
    val selectedDaemonName: String get() = pages.firstOrNull { it.daemonId == selectedDaemonId }?.name.orEmpty()

    /** 当前页码（选中项失效时回落到 0）。 */
    val selectedIndex: Int
        get() = pages.indexOfFirst { it.daemonId == selectedDaemonId }.coerceAtLeast(0)

    /**
     * 列表展示顺序与卡片联动：默认展示全部聚合实例，但把当前卡片那台服务端的实例排到最前，
     * 本机实例始终保留在首位（不属于任何 Daemon，不参与过滤歧义）。
     */
    val visibleServers: List<ManagedServer>
        get() {
            if (onlySelected && selectedDaemonId.isNotBlank()) {
                return servers.filter { it.ref?.daemonId == selectedDaemonId }
            }
            val local = servers.filter { it.isLocal }
            val selected = servers.filter { it.ref?.daemonId == selectedDaemonId }
            val rest = servers - local.toSet() - selected.toSet()
            return local + selected + rest
        }

    val runningServers: Int get() = servers.count { it.running }
}

/**
 * 主页视图模型：**多 Daemon 平权**——不再自动连接某一台「主连接」，而是
 * 为设置里每台 Daemon 各维护一条连接与一路负载订阅，并把本机实例一并聚合进同一列表。
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val container = getApplication<MSLXApplication>().container
    private val store = container.settingsStore
    private val registry = container.daemonRegistry
    private val catalog = container.serverCatalog

    private val _state = MutableStateFlow(HomeUiState())
    val state = _state.asStateFlow()

    /** 最近一次生效的设置（页面数据都由它派生）。 */
    private var settings: AppSettings = AppSettings()

    /** 每台 Daemon 的实时负载（各自一路 SignalR 推送）。 */
    private val loads = mutableMapOf<String, NodeStatsPayload>()
    private val monitorClients = mutableMapOf<String, SystemMonitorClient>()

    /** 上一轮实例运行状态（catalogKey → running），用于检测开服/关服。 */
    private val lastRunning = mutableMapOf<String, Boolean>()

    /** Daemon 配置指纹：只有增删改连接才重做一轮全量刷新，避免改主题等写入触发网络风暴。 */
    private var daemonSignature: String? = null

    init {
        loadQuote()
        // Daemon 状态是异步并行探测出来的，任何一次刷新都重算页面数据
        viewModelScope.launch { registry.statuses.collect { publish() } }
        // 设置变化：同步注册表（configure 内部对未变化的连接早退，代价极低），
        // 但只有连接配置真的增删改才重做一轮网络刷新，避免改主题等写入触发风暴。
        viewModelScope.launch {
            store.settingsFlow.collect { latest ->
                val signature = latest.daemons.joinToString("|") { "${it.id}:${it.baseUrl}:${it.apiKey}" }
                val first = daemonSignature == null
                val daemonsChanged = first || signature != daemonSignature
                daemonSignature = signature
                settings = latest
                registry.sync(latest)
                if (first) {
                    _state.update { it.copy(selectedDaemonId = preferredDaemonId(latest)) }
                }
                publish()
                if (daemonsChanged) reload()
            }
        }
        // 周期轮询：实例状态 + 负载订阅自愈（守护进程重启后连接会被动失效）
        viewModelScope.launch {
            while (isActive) {
                delay(15_000)
                try {
                    reload(silent = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLogger.w("Home", "轮询实例状态异常", e)
                }
            }
        }
    }

    /** 默认落点：activeDaemonId 仅用于「主页首屏与新建实例的默认目标」。 */
    private fun preferredDaemonId(settings: AppSettings): String =
        settings.activeDaemonId.takeIf { id -> settings.daemons.any { it.id == id } }
            ?: settings.daemons.firstOrNull()?.id.orEmpty()

    /** 重算页面数据（由状态流、负载与实例列表派生，不发网络请求）。 */
    private fun publish() {
        // 设置首次真实值落盘前不发布：避免首帧闪一下「还没有配置任何服务端」
        if (daemonSignature == null) return
        val statuses: Map<String, DaemonStatus> = registry.statuses.value
        val servers = _state.value.servers
        val pages = settings.daemons.map { daemon ->
            val status = statuses[daemon.id] ?: DaemonStatus(id = daemon.id, name = daemon.name)
            val load = loads[daemon.id]
            val owned = servers.filter { it.ref?.daemonId == daemon.id }
            val memoryPercent = load?.memUsage ?: load.memoryPercentFallback()
            DaemonPage(
                daemonId = daemon.id,
                name = daemon.name.ifBlank { daemon.baseUrl },
                baseUrl = repositoryOf(daemon.id)?.baseUrl?.takeIf { it.isNotBlank() } ?: daemon.baseUrl,
                protocol = protocolLabel(repositoryOf(daemon.id)?.baseUrl ?: daemon.baseUrl),
                isDefault = status.isDefault,
                online = when (status.state) {
                    DaemonState.ONLINE -> true
                    DaemonState.OFFLINE -> false
                    DaemonState.UNKNOWN -> null
                },
                latencyMs = status.latencyMs,
                version = status.version.orEmpty(),
                osText = listOfNotNull(status.osType, status.osArchitecture).joinToString(" ").trim(),
                instanceTotal = owned.size,
                instanceRunning = owned.count { it.running },
                onlinePlayers = owned.sumOf { it.onlinePlayers },
                cpu = load?.cpu,
                memoryPercent = memoryPercent,
                memoryUsedGb = load?.memUsed?.let { it / 1024.0 },
                memoryTotalGb = load?.memTotal?.let { it / 1024.0 },
                message = status.message.takeIf { status.state == DaemonState.OFFLINE && it.isNotBlank() },
                checkedAt = status.checkedAt,
            )
        }
        _state.update { current ->
            val selected = current.selectedDaemonId.takeIf { id -> pages.any { it.daemonId == id } }
                ?: preferredDaemonId(settings)
            current.copy(
                loading = false,
                pages = pages,
                selectedDaemonId = selected,
            )
        }
    }

    private fun repositoryOf(daemonId: String): InstanceRepository? = registry.repositoryFor(daemonId)

    /**
     * 重新拉取一轮：并行探测所有 Daemon → 聚合实例列表 → 补齐/回收负载订阅。
     * [silent] 为 true 时不显示下拉刷新态（后台轮询用）。
     */
    private suspend fun reload(silent: Boolean = false) {
        if (!silent) _state.update { it.copy(refreshing = true) }
        runCatching { catalog.load(settings) }
            .onSuccess { servers ->
                _state.update { it.copy(refreshing = false, error = null, servers = servers) }
                detectStatusChanges(servers)
                loadIcons(servers)
            }
            .onFailure { e ->
                AppLogger.w("Home", "聚合服务端列表失败", e)
                _state.update { it.copy(refreshing = false, error = e.message ?: "加载失败") }
            }
        // catalog.load 内部已并行刷新各 Daemon 状态；此处据最新状态维护负载订阅并刷新页码
        ensureMonitors()
        publish()
    }

    /** 手动刷新（下拉刷新 / 卡片上的重连按钮共用的入口）。 */
    fun refresh() {
        viewModelScope.launch { reload() }
    }

    /**
     * 批量补齐实例图标（缓存命中不发请求），并入已有图标：
     * 单条失败不影响其它条目，已加载的图标不会因一次网络抖动而丢失。
     */
    private suspend fun loadIcons(servers: List<ManagedServer>) {
        val icons = InstanceIcons.load(container, servers)
        if (icons.isEmpty()) return
        _state.update { it.copy(icons = it.icons + icons) }
    }

    /** 卡片滑动切页：只改选中的 Daemon，不触发网络请求。 */
    fun selectDaemon(daemonId: String) {
        if (_state.value.selectedDaemonId == daemonId) return
        _state.update { it.copy(selectedDaemonId = daemonId) }
        publish()
    }

    /** 实例列表是否只显示当前卡片那台服务端的实例。 */
    fun setOnlySelected(only: Boolean) {
        _state.update { it.copy(onlySelected = only) }
    }

    /** 单台 Daemon 重连（卡片「重连」按钮）。 */
    fun reconnect(daemonId: String) {
        viewModelScope.launch {
            val daemon = settings.daemons.firstOrNull { it.id == daemonId } ?: return@launch
            registry.repositoryFor(daemonId)?.let { repo ->
                // 地址可能被规范化（http→https），回写避免下次仍用旧地址
                runCatching {
                    if (repo.baseUrl.isNotBlank() && repo.baseUrl != daemon.baseUrl) {
                        store.upsertDaemon(daemon.copy(baseUrl = repo.baseUrl))
                    }
                }.onFailure { AppLogger.w("Home", "回写 Daemon 地址失败", it) }
            }
            registry.refreshOne(daemon, isDefault = daemonId == settings.activeDaemonId)
            reload()
        }
    }

    /** 删除某台 Daemon 后由设置页写入新配置，主页经 settingsFlow 自动跟进，此处无需额外处理。 */
    fun clearNotifications() {
        _state.update { it.copy(notifications = emptyList()) }
    }

    /** 加载一言（Hitokoto）金句；失败时置 quoteFailed=true 由 UI 展示兜底文案，绝不崩溃。 */
    fun loadQuote() {
        viewModelScope.launch {
            runCatching {
                val q = ApiClient.buildHitokotoApi().quote()
                val text = q.hitokoto?.trim().orEmpty()
                if (text.isBlank()) throw IllegalStateException("一言返回为空")
                text to (q.fromWho?.takeIf { it.isNotBlank() } ?: q.from.orEmpty())
            }.onSuccess { (text, source) ->
                _state.update { it.copy(quote = text, quoteSource = source, quoteFailed = false) }
            }.onFailure { e ->
                AppLogger.w("Home", "一言加载失败", e)
                _state.update { it.copy(quote = "", quoteSource = "", quoteFailed = true) }
            }
        }
    }

    // ---------------------------------------------------------------- 负载订阅

    /**
     * 按当前在线情况为每台 Daemon 建立/回收一路 SystemMonitor 订阅：
     * 离线或被删除的 Daemon 立即断开，避免留着空转的重连循环。
     */
    private fun ensureMonitors() {
        val onlineIds = settings.daemons
            .map { it.id }
            .filter { registry.statusOf(it).state == DaemonState.ONLINE }
            .toSet()

        val stale = monitorClients.keys.filterNot { it in onlineIds }
        stale.forEach { id ->
            val client = monitorClients.remove(id)
            if (client != null) viewModelScope.launch(Dispatchers.IO) { runCatching { client.disconnect() } }
        }

        onlineIds.filterNot { it in monitorClients }.forEach { id -> startMonitor(id) }
    }

    /**
     * 启动某台 Daemon 的负载监视（/api/hubs/system，2s 推送）。
     * 首连失败只丢这一路订阅，15s 轮询的下一轮会由 [ensureMonitors] 重建。
     */
    private fun startMonitor(daemonId: String) {
        val repository = registry.repositoryFor(daemonId) ?: return
        val client = repository.createSystemMonitorClient { stats -> onSystemStats(daemonId, stats) }
        monitorClients[daemonId] = client
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching { client.connect() }.isSuccess
            if (!ok && monitorClients[daemonId] === client) {
                monitorClients.remove(daemonId)
                runCatching { client.disconnect() }
            }
        }
    }

    /** 收到某台 Daemon 的负载推送（memTotal/memUsed 单位 MB，换算成 GB 展示）。 */
    private fun onSystemStats(daemonId: String, stats: NodeStatsPayload) {
        loads[daemonId] = stats
        // 高频推送：只重算受影响的那一页，且沿用当前页码与错误信息
        val pages = _state.value.pages.map { page ->
            if (page.daemonId != daemonId) return@map page
            page.copy(
                cpu = stats.cpu,
                memoryPercent = stats.memUsage ?: stats.memoryPercentFallback(),
                memoryUsedGb = stats.memUsed?.let { it / 1024.0 },
                memoryTotalGb = stats.memTotal?.let { it / 1024.0 },
            )
        }
        _state.update { it.copy(pages = pages) }
    }

    // ---------------------------------------------------------------- 开/关服检测

    /** 对比上一轮运行状态，生成开/关服通知（保留最近 50 条）并发送原生通知。 */
    private fun detectStatusChanges(current: List<ManagedServer>) {
        val now = System.currentTimeMillis()
        val snapshot = current.mapNotNull { server ->
            server.ref?.let { it.catalogKey to server.running }
        }.toMap()
        val fresh = mutableListOf<ServerNotification>()
        for (server in current) {
            val ref = server.ref ?: continue
            val previous = lastRunning[ref.catalogKey] ?: continue
            if (previous == server.running) continue
            val item = ServerNotification(
                ref = ref,
                instanceName = server.name,
                sourceLabel = server.sourceLabel,
                isOpened = server.running,
                time = now,
            )
            fresh += item
            runCatching {
                ServerNotificationHelper.notifyServerStatus(
                    context = getApplication(),
                    ref = ref,
                    instanceName = server.name,
                    isOpened = server.running,
                )
            }
        }
        // 已消失的实例不再保留状态，避免重建后的实例被误判为「状态变化」
        lastRunning.keys.retainAll(snapshot.keys)
        lastRunning.putAll(snapshot)
        if (fresh.isNotEmpty()) {
            _state.update { it.copy(notifications = (fresh + it.notifications).take(50)) }
        }
    }

    private fun protocolLabel(baseUrl: String): String = when {
        baseUrl.startsWith("https://", ignoreCase = true) -> "HTTPS / WSS"
        baseUrl.startsWith("http://", ignoreCase = true) -> "HTTP / WS"
        else -> "未知"
    }

    override fun onCleared() {
        val clients = monitorClients.values.toList()
        monitorClients.clear()
        viewModelScope.launch(Dispatchers.IO) {
            clients.forEach { runCatching { it.disconnect() } }
        }
        super.onCleared()
    }
}

/** memUsage 缺失时用 used/total 兜底算百分比（单位同为 MB，比值不受单位影响）。 */
private fun NodeStatsPayload?.memoryPercentFallback(): Double? {
    val payload = this ?: return null
    val used = payload.memUsed ?: return null
    val total = payload.memTotal?.takeIf { it > 0 } ?: return null
    return used / total * 100.0
}
