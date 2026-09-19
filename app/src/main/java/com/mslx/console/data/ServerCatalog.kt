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
 * 统一的「服务端」条目：把**本机开服的实例**与**各 Daemon 上的实例**归一成同一模型，
 * 供总览页/实例页统一展示与操作（本机实例纳入通用服务端管理体系）。
 */
data class ManagedServer(
    val key: String,
    val name: String,
    val source: ServerSource,
    val detail: String,
    val running: Boolean,
    val localDirName: String? = null,
    val remoteId: Long? = null,
) {
    val isLocal: Boolean get() = source is ServerSource.Local

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
        val activeName = LocalServerRuntime.currentServerName
        return LocalInstanceStore.list(context).map { summary ->
            ManagedServer(
                key = "local:${summary.dirName}",
                name = summary.name,
                source = ServerSource.Local,
                detail = summary.subtitle,
                running = runtimeRunning && summary.name == activeName,
                localDirName = summary.dirName,
            )
        }
    }

    private suspend fun remoteServers(daemon: DaemonConfig): List<ManagedServer> {
        val repository = registry.repositoryFor(daemon.id) ?: return emptyList()
        val instances = repository.listInstances().getOrElse { return emptyList() }
        return instances.map { instance ->
            ManagedServer(
                key = "daemon:${daemon.id}:${instance.id}",
                name = instance.name?.takeIf { it.isNotBlank() } ?: "实例 ${instance.id}",
                source = ServerSource.Daemon(daemon.id, daemon.name),
                detail = listOfNotNull(
                    instance.core?.takeIf { it.isNotBlank() },
                    instance.statusText?.takeIf { it.isNotBlank() },
                ).joinToString(" · "),
                running = instance.status == 1 || instance.statusText?.contains("运行") == true,
                remoteId = instance.id,
            )
        }
    }
}
