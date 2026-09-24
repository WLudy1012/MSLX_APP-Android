package com.mslx.console.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/** 单个 Daemon 的连接状态。 */
enum class DaemonState { UNKNOWN, ONLINE, OFFLINE }

/** Daemon 选择项（新建实例页 / 用户中心等“先选一台服务端”的场景共用）。 */
data class DaemonOption(val id: String, val name: String)

/** 单个 Daemon 的运行时状态（并行探测结果）。 */
data class DaemonStatus(
    val id: String,
    val name: String = "",
    val state: DaemonState = DaemonState.UNKNOWN,
    val message: String = "",
    val version: String? = null,
    val instanceCount: Int? = null,
    val latencyMs: Long? = null,
    /** 宿主系统信息（来自 /api/status，供主页 Daemon 卡片展示）。 */
    val osType: String? = null,
    val osArchitecture: String? = null,
    /**
     * 是否为「默认 Daemon」——仅用作新建实例的默认落点与主页首屏页码，
     * **不代表任何连接优先级**（各 Daemon 地位完全对等）。
     */
    val isDefault: Boolean = false,
    val checkedAt: Long = 0L,
) {
    val stateText: String
        get() = when (state) {
            DaemonState.ONLINE -> "在线"
            DaemonState.OFFLINE -> "离线"
            DaemonState.UNKNOWN -> "未检测"
        }
}

/**
 * **多 Daemon 同时连接管理（无主连接）**：每台 Daemon 各自持有一个 [InstanceRepository]
 * （独立的 REST 客户端与 SignalR 客户端工厂），按 daemonId 索引，状态探测并行执行、互不影响。
 *
 * 页面一律通过 [repositoryFor] 取得目标连接，再以 [ServerRef] 定位实例；
 * 不存在「当前全局连接」，因此任一页面的操作不会影响其它 Daemon 的上下文。
 */
class DaemonRegistry {

    /** daemonId → 该 Daemon 的独立连接。 */
    private val repositories = ConcurrentHashMap<String, InstanceRepository>()

    private val _statuses = MutableStateFlow<Map<String, DaemonStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, DaemonStatus>> = _statuses.asStateFlow()

    /** 已建立连接的 Daemon 数量。 */
    val connectedCount: Int get() = repositories.size

    /**
     * 按设置同步连接：为每台已配置 Daemon 建立（或复用）连接并下发配置，
     * 设置里已删除的 Daemon 会移除其连接与状态。
     */
    fun sync(settings: AppSettings) {
        val ids = settings.daemons.map { it.id }.toSet()
        repositories.keys.retainAll(ids)

        settings.daemons.forEach { daemon ->
            // 配置变更判定已下沉到 InstanceRepository.configure（内部早退），此处直接调用
            repositories.getOrPut(daemon.id) { InstanceRepository() }
                .configure(daemon.baseUrl, daemon.apiKey, daemon.allowHttp)
        }

        _statuses.update { map ->
            map.filterKeys { it in ids }.mapValues { (id, status) ->
                status.copy(
                    name = settings.daemons.firstOrNull { it.id == id }?.name.orEmpty(),
                    isDefault = id == settings.activeDaemonId,
                )
            }
        }
    }

    /** 取得某 Daemon 的连接（未同步或不存在时返回 null）。 */
    fun repositoryFor(daemonId: String): InstanceRepository? = repositories[daemonId]

    fun statusOf(daemonId: String): DaemonStatus =
        _statuses.value[daemonId] ?: DaemonStatus(daemonId)

    /** 全部已配置 Daemon 的连接（供多路订阅/聚合场景使用）。 */
    fun allRepositories(): Map<String, InstanceRepository> = repositories.toMap()

    /** 并行刷新所有 Daemon 的状态（在线/版本/实例数/延迟）。 */
    suspend fun refreshAll(settings: AppSettings) {
        // 先补齐条目，保证 UI 立刻能看到所有已配置 Daemon
        _statuses.update { map ->
            map + settings.daemons.associate { daemon ->
                daemon.id to (map[daemon.id] ?: DaemonStatus(
                    id = daemon.id,
                    name = daemon.name,
                    isDefault = daemon.id == settings.activeDaemonId,
                ))
            }
        }
        coroutineScope {
            settings.daemons
                .filter { it.baseUrl.isNotBlank() }
                .map { daemon -> async { refreshOne(daemon, daemon.id == settings.activeDaemonId) } }
                .awaitAll()
        }
    }

    /** 刷新单个 Daemon 状态。 */
    suspend fun refreshOne(config: DaemonConfig, isDefault: Boolean = false) {
        val repository = repositoryFor(config.id) ?: return
        val startedAt = System.currentTimeMillis()
        val verify = repository.verify()
        val latency = System.currentTimeMillis() - startedAt
        val checkedAt = System.currentTimeMillis()

        if (verify.isFailure) {
            _statuses.update { map ->
                map + (config.id to DaemonStatus(
                    id = config.id,
                    name = config.name,
                    state = DaemonState.OFFLINE,
                    message = verify.exceptionOrNull()?.message ?: "连接失败",
                    latencyMs = latency,
                    isDefault = isDefault,
                    checkedAt = checkedAt,
                ))
            }
            return
        }

        val status = repository.getStatus().getOrNull()
        val instanceCount = repository.listInstances().getOrNull()?.size
        _statuses.update { map ->
            map + (config.id to DaemonStatus(
                id = config.id,
                name = config.name,
                state = DaemonState.ONLINE,
                version = status?.version,
                instanceCount = instanceCount,
                latencyMs = latency,
                osType = status?.systemInfo?.osType,
                osArchitecture = status?.systemInfo?.osArchitecture,
                isDefault = isDefault,
                checkedAt = checkedAt,
            ))
        }
        AppLogger.i("DaemonRegistry", "Daemon 在线：${config.name.ifBlank { config.id }} v${status?.version ?: "?"} ${latency}ms")
    }
}
