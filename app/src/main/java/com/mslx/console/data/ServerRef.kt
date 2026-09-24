package com.mslx.console.data

/**
 * **实例定位符**：去掉「主连接」概念后，所有页面路由、ViewModel 与仓储访问都统一用它寻址。
 *
 * 一台 Daemon 上的一个实例 = [daemonId] + [instanceId]，不再存在「隐式指向当前主连接」的实例 id。
 * [daemonId] 为 [LOCAL_DAEMON_ID] 时表示本机开服实例，此时 [instanceId] 是实例目录名
 * （因此 instanceId 用 String 承载，远程实例在访问 REST 时再转 Long）。
 *
 * [catalogKey] 与 [ManagedServer.key] 完全互通，便于总览列表点击后直接构造路由参数。
 */
data class ServerRef(val daemonId: String, val instanceId: String) {

    /** 是否本机开服实例。 */
    val isLocal: Boolean get() = daemonId == LOCAL_DAEMON_ID

    /** 远程实例的数值 id；本地实例或非法值返回 null。 */
    val remoteIdOrNull: Long? get() = if (isLocal) null else instanceId.toLongOrNull()

    /** 与 [ManagedServer.key] 互通的稳定标识。 */
    val catalogKey: String
        get() = if (isLocal) "$LOCAL_PREFIX$instanceId" else "$DAEMON_PREFIX$daemonId:$instanceId"

    /** 路由片段：实例 id 可能含中文/空格（本地目录名），必须转义。 */
    fun routePath(prefix: String): String =
        "$prefix/$daemonId/${android.net.Uri.encode(instanceId)}"

    companion object {
        /** 本机开服的保留 daemonId（与路由第一段一致）。 */
        const val LOCAL_DAEMON_ID = "local"
        private const val LOCAL_PREFIX = "local:"
        private const val DAEMON_PREFIX = "daemon:"

        fun remote(daemonId: String, instanceId: Long): ServerRef =
            ServerRef(daemonId, instanceId.toString())

        fun local(dirName: String): ServerRef = ServerRef(LOCAL_DAEMON_ID, dirName)

        /** 解析 [catalogKey]；无法识别时返回 null（调用方据此忽略该条目）。 */
        fun fromCatalogKey(key: String): ServerRef? = when {
            key.startsWith(LOCAL_PREFIX) -> local(key.removePrefix(LOCAL_PREFIX))
            key.startsWith(DAEMON_PREFIX) -> {
                val rest = key.removePrefix(DAEMON_PREFIX)
                val split = rest.indexOf(':')
                if (split <= 0 || split == rest.length - 1) null
                else ServerRef(rest.substring(0, split), rest.substring(split + 1))
            }
            else -> null
        }
    }
}
