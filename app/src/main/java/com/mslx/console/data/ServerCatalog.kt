package com.mslx.console.data

import android.content.Context
import com.mslx.console.data.localengine.LocalInstanceStore
import com.mslx.console.data.localengine.LocalServerRuntime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** 服务端来源：本机（App 内进程 JVM）或某台 Daemon。 */
sealed interface ServerSource {
    data object Local : ServerSource
    data class Daemon(val id: String, val name: String) : ServerSource
}

/**
 * 实例是否「运行中」的统一判定。
 *
 * 状态码约定（与 [com.mslx.console.ui.statusColor] 一致）：
 * 0 未启动 / 1 启动中 / 2 运行中 / 3 停止中 / 4 重启中。
 * 以前 ServerCatalog 按 1 判、控制台按 2 判，两处口径不一致；此处收敛为唯一实现。
 * statusText 只做整串相等比较——「未运行」包含子串「运行」，用 contains 会误判。
 */
fun isRunningStatus(status: Int, statusText: String? = null): Boolean =
    status == 2 || statusText?.trim() == "运行中"

/** 是否需要停止：运行中与启/停/重启过渡态都算（供「一键停止全部」筛选目标）。 */
fun isStoppableStatus(status: Int): Boolean = status in 1..4

/**
 * 统一的「服务端」条目：把**本机开服的实例**与**各 Daemon 上的实例**归一成同一模型，
 * 供总览页/实例页统一展示与操作（本机实例纳入通用服务端管理体系）。
 */
data class ManagedServer(
    val key: String,
    val name: String,
    val source: ServerSource,
    val detail: String,
    val running: Boolean,
    /** 状态码：0 未启动 / 1 启动中 / 2 运行中 / 3 停止中 / 4 重启中（本机实例只有 0/2）。 */
    val status: Int = if (running) 2 else 0,
    /** 在线玩家数（仅远程实例由 Daemon 提供，本机实例恒为 0）。 */
    val onlinePlayers: Int = 0,
    val localDirName: String? = null,
    val remoteId: Long? = null,
) {
    val isLocal: Boolean get() = source is ServerSource.Local

    /** 统一寻址：由 [key] 反解，供路由参数与仓储分派使用（去主连接后的唯一定位方式）。 */
    val ref: ServerRef? get() = ServerRef.fromCatalogKey(key)

    val sourceLabel: String
        get() = when (val s = source) {
            is ServerSource.Local -> "本机"
            is ServerSource.Daemon -> s.name.ifBlank { "Daemon" }
        }
}

/**
 * 服务端目录：并行聚合本机实例与所有已连接 Daemon 的实例列表，
 * 同时顺带刷新各 Daemon 的在线状态（[DaemonRegistry.refreshAll]）。
 */
class ServerCatalog(
    private val registry: DaemonRegistry,
    private val contextProvider: () -> Context,
) {

    suspend fun load(settings: AppSettings): List<ManagedServer> = coroutineScope {
        registry.refreshAll(settings)
        val localDeferred = async { localServers() }
        val remoteDeferred = settings.daemons.map { daemon -> async { remoteServers(daemon) } }
        (listOf(localDeferred) + remoteDeferred).awaitAll().flatten()
    }

    /** 仅本机实例（不触发 Daemon 探测）。 */
    fun localServers(): List<ManagedServer> {
        val context = contextProvider()
        val runtimeRunning = LocalServerRuntime.running.value
        val activeDir = LocalServerRuntime.currentDirName
        return LocalInstanceStore.list(context).map { summary ->
            ManagedServer(
                key = ServerRef.local(summary.dirName).catalogKey,
                name = summary.name,
                source = ServerSource.Local,
                detail = summary.subtitle,
                // 按**目录名**比对而非展示名：两个实例可以同名，而目录名全局唯一
                running = runtimeRunning && summary.dirName == activeDir,
                localDirName = summary.dirName,
            )
        }
    }

    private suspend fun remoteServers(daemon: DaemonConfig): List<ManagedServer> {
        val repository = registry.repositoryFor(daemon.id) ?: return emptyList()
        val instances = repository.listInstances().getOrElse { return emptyList() }
        return instances.map { instance ->
            ManagedServer(
                key = ServerRef.remote(daemon.id, instance.id).catalogKey,
                name = instance.name?.takeIf { it.isNotBlank() } ?: "实例 ${instance.id}",
                source = ServerSource.Daemon(daemon.id, daemon.name),
                detail = listOfNotNull(
                    instance.core?.takeIf { it.isNotBlank() },
                    instance.statusText?.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                running = isRunningStatus(instance.status, instance.statusText),
                status = instance.status,
                onlinePlayers = instance.extra?.onlinePlayers ?: 0,
                remoteId = instance.id,
            )
        }
    }
}
