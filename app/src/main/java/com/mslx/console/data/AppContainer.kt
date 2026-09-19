package com.mslx.console.data

import android.content.Context

/** 简易手动依赖注入容器。 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsStore = SettingsStore(appContext)
    val instanceRepository = InstanceRepository()
    val updateRepository = UpdateRepository()

    /** 多 Daemon 同时连接管理：主连接复用 [instanceRepository]，其余 Daemon 各持独立连接。 */
    val daemonRegistry = DaemonRegistry(instanceRepository)

    /** 统一服务端目录：本机实例 + 全部已连接 Daemon 的实例。 */
    val serverCatalog = ServerCatalog(daemonRegistry) { appContext }
}
