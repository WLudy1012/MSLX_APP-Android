package com.mslx.console.data

import com.mslx.console.data.remote.ApiClient
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

/** 单个 Daemon 的运行时状态（并行探测结果）。 */
data class DaemonStatus(
    val id: String,
    val name: String = "",
    val state: DaemonState = DaemonState.UNKNOWN,
    val message: String = "",
    val version: String? = null,
    val instanceCount: Int? = null,
    val latencyMs: Long? = null,
    val isPrimary: Boolean = false,
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
 * **多 Daemon 同时连接管理**：每台 Daemon 各自持有一个 [InstanceRepository]（独立的 REST 客户端
 * 与 SignalR 客户端），状态探测并行执行，互不影响。
 *
 * 兼容策略：主 Daemon（`activeDaemonId`）复用全局 [InstanceRepository]（configure 到它），
 * 因此既有的主页/实例/创建/控制台页面**无需改动**就继续指向主连接；其余 Daemon 用额外实例，
 * 供总览页并行管理（列表/状态/切换为主）。
 */
class DaemonRegistry(
    private val primaryRepository: InstanceRepository,
) {

    /** 非主 Daemon 的独立连接（主 Daemon 走 primaryRepository）。 */
    private val extraRepositories = ConcurrentHashMap<String, InstanceRepository>()

    private val _statuses = MutableStateFlow<Map<String, DaemonStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, DaemonStatus>> = _statuses.asStateFlow()

    private val _primaryId = MutableStateFlow<String?>(null)
    val primaryId: StateFlow<String?> = _primaryId.asStateFlow()

    /** 当前已建立连接的 Daemon 数量（含主连接）。 */
    val connectedCount: Int get() = 1 + extraRepositories.size

    /**
     * 按设置同步连接：主 Daemon 配置到全局 repository，其余各建一个 repository；
     * 设置里已删除的 Daemon 会释放其连接与状态。
     */
    fun sync(settings: AppSettings) {
        _primaryId.value = settings.activeDaemonId
        val ids = settings.daemons.map { it.id }.toSet()
        extraRepositories.keys.retainAll(ids)

        settings.daemons.forEach { daemon ->
            if (daemon.id == settings.activeDaemonId) {
                primaryRepository.configure(daemon.baseUrl, daemon.apiKey, daemon.allowHttp)
            } else {
                val repo = extraRepositories.getOrPut(daemon.id) { InstanceRepository() }
                val normalized = ApiClient.normalizeDaemonUrl(daemon.baseUrl, daemon.allowHttp)
                if (repo.baseUrl != normalized || repo.apiKey != daemon.apiKey.trim()) {
                    repo.configure(daemon.baseUrl, daemon.apiKey, daemon.allowHttp)
                }
            }
        }

        _statuses.update { map ->
            map.filterKeys { it in ids }.mapValues { (id, status) ->
                val daemon = settings.daemons.firstOrNull { it.id == id }
                status.copy(
                    name = daemon?.name.orEmpty(),
                    isPrimary = id == settings.activeDaemonId,
                )
            }
        }
    }

    /** 取得某 Daemon 的连接（未同步或不存在时返回 null）。 */
    fun repositoryFor(daemonId: String): InstanceRepository? =
        if (daemonId == _primaryId.value) primaryRepository else extraRepositories[daemonId]

    /** 主连接（既有页面默认使用的连接）。 */
    fun primaryRepository(): InstanceRepository = primaryRepository

    fun statusOf(daemonId: String): DaemonStatus =
        _statuses.value[daemonId] ?: DaemonStatus(daemonId)

    /** 并行刷新所有 Daemon 的状态（在线/版本/实例数/延迟）。 */
    suspend fun refreshAll(settings: AppSettings) {
        // 先补齐条目，保证 UI 立刻能看到所有已配置 Daemon
        _statuses.update { map ->
            map + settings.daemons.associate { daemon ->
                daemon.id to (map[daemon.id] ?: DaemonStatus(
                    id = daemon.id,
                    name = daemon.name,
                    isPrimary = daemon.id == settings.activeDaemonId,
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
    suspend fun refreshOne(config: DaemonConfig, isPrimary: Boolean = false) {
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
                    isPrimary = isPrimary,
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
                isPrimary = isPrimary,
                checkedAt = checkedAt,
            ))
        }
        AppLogger.i("DaemonRegistry", "Daemon 在线：${config.name.ifBlank { config.id }} v${status?.version ?: "?"} ${latency}ms")
    }
}
