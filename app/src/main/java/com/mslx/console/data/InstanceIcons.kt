package com.mslx.console.data

import android.graphics.Bitmap
import com.mslx.console.data.localengine.LocalInstanceStore
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 统一图标加载入口：为聚合列表批量取图标（主页与实例页共用）。
 *
 * 本机实例直接读实例目录下的 `server-icon.png`；远程实例走
 * [InstanceRepository.fetchInstanceIcon]（Daemon 内置端点 → 图标插件 → 第三方 API 三级回退）。
 * 并发上限 4，滚动/轮询期间全部命中 [InstanceIconStore] 缓存不发请求；
 * 单条失败不影响其它条目，结果以 [ManagedServer.key] 为键供 UI 直接查询。
 */
object InstanceIcons {

    private const val MAX_CONCURRENT = 4
    private const val ICON_FILE_NAME = "server-icon.png"

    suspend fun load(container: AppContainer, servers: List<ManagedServer>): Map<String, Bitmap> =
        coroutineScope {
            val gate = Semaphore(MAX_CONCURRENT)
            servers.map { server ->
                async {
                    val bitmap = gate.withPermit {
                        InstanceIconStore.load(server.key) { fetch(container, server) }
                    }
                    bitmap?.let { server.key to it }
                }
            }.awaitAll().filterNotNull().toMap()
        }

    private suspend fun fetch(container: AppContainer, server: ManagedServer): ByteArray? {
        if (server.isLocal) {
            val dirName = server.localDirName ?: return null
            val file = File(LocalInstanceStore.dir(container.appContext, dirName), ICON_FILE_NAME)
            return runCatching { if (file.isFile) file.readBytes() else null }.getOrNull()
        }
        val ref = server.ref ?: return null
        val id = ref.remoteIdOrNull ?: return null
        val repository = container.ensureRepository(ref.daemonId) ?: return null
        return repository.fetchInstanceIcon(id)
    }
}
