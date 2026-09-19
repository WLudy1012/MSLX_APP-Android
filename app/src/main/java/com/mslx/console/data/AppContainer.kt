package com.mslx.console.data

import android.content.Context
import com.mslx.console.data.localengine.LocalServerManager

/** 简易手动依赖注入容器。 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsStore = SettingsStore(appContext)
    val instanceRepository = InstanceRepository()
    val updateRepository = UpdateRepository()

    /** 本机开服协调器：按目录名启停本机实例、发送控制台命令（委托进程级 [LocalServerRuntime]）。 */
    val localServerManager = LocalServerManager(appContext, settingsStore)

    /** 多 Daemon 同时连接管理：主连接复用 [instanceRepository]，其余 Daemon 各持独立连接。 */
    val daemonRegistry = DaemonRegistry(instanceRepository)

    /** 统一服务端目录：本机实例 + 全部已连接 Daemon 的实例。 */
    val serverCatalog = ServerCatalog(daemonRegistry) { appContext }
}
