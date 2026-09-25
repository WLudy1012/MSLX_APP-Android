package com.mslx.console.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mslx.console.data.AppSettings
import com.mslx.console.data.ServerRef
import com.mslx.console.ui.MainBottomNav
import com.mslx.console.ui.TopPage
import com.mslx.console.ui.connect.ConnectScreen
import com.mslx.console.ui.console.ConsoleScreen
import com.mslx.console.ui.console.ConsoleViewModel
import com.mslx.console.ui.console.LocalConsoleViewModel
import com.mslx.console.ui.create.CreateInstanceScreen
import com.mslx.console.ui.create.CreateResetBus
import com.mslx.console.ui.home.HomeScreen
import com.mslx.console.ui.instances.InstancesScreen
import com.mslx.console.ui.settings.InstanceSettingsScreen
import com.mslx.console.ui.settings.FileManagerScreen
import com.mslx.console.ui.settings.LocalInstancePendingScreen
import com.mslx.console.ui.settings.LocalInstanceSettingsScreen
import com.mslx.console.ui.settings.PluginsModsScreen
import com.mslx.console.ui.settings.ServerPropertiesScreen
import com.mslx.console.ui.settings.LocalServerSettingsScreen
import com.mslx.console.ui.settings.SettingsScreen
import com.mslx.console.ui.servers.ServersOverviewScreen
import com.mslx.console.ui.settings.AppearanceScreen
import com.mslx.console.ui.settings.AboutScreen
import com.mslx.console.ui.settings.LogViewerScreen
import com.mslx.console.ui.settings.LegalDocumentScreen
import com.mslx.console.ui.splash.SplashScreen
import com.mslx.console.ui.user.UserCenterScreen
import com.mslx.console.ui.welcome.WelcomeScreen

object Routes {
    const val SPLASH = "splash"
    const val WELCOME = "welcome"
    const val CONNECT = "connect?auto={autoConnect}&daemonId={daemonId}"
    const val HOME = "home"
    const val INSTANCES = "instances"
    const val SETTINGS = "settings"
    const val NEW_INSTANCE = "newInstance"

    // 二级页统一携带 daemonId：实例归属哪台服务端就操作哪台（去主连接）。
    // 本机实例复用同一套路由：daemonId = local、instanceId = 实例目录名。
    const val CONSOLE = "console/{daemonId}/{instanceId}"
    const val INSTANCE_SETTINGS = "instanceSettings/{daemonId}/{instanceId}"
    const val FILE_MANAGER = "fileManager/{daemonId}/{instanceId}"
    const val PLUGINS_MODS = "pluginsMods/{daemonId}/{instanceId}"
    const val SERVER_PROPS = "serverProps/{daemonId}/{instanceId}"
    const val USER_CENTER = "userCenter"
    const val APPEARANCE = "appearance"
    const val ABOUT = "about"
    const val LOGS = "logs"
    const val LOCAL_SERVER_SETTINGS = "localServerSettings"
    const val SERVERS = "servers"
    /** 合规文档（第三方许可 / 免责声明），docKey 取 [com.mslx.console.ui.settings.LegalDoc.key]。 */
    const val LEGAL = "legal/{docKey}"

    fun console(ref: ServerRef): String = ref.routePath("console")
    fun instanceSettings(ref: ServerRef): String = ref.routePath("instanceSettings")
    fun fileManager(ref: ServerRef): String = ref.routePath("fileManager")
    fun pluginsMods(ref: ServerRef): String = ref.routePath("pluginsMods")
    fun serverProps(ref: ServerRef): String = ref.routePath("serverProps")

    /** 本机实例的语义化入口（等价于 console(ServerRef.local(dir))）。 */
    fun localConsole(dir: String): String = console(ServerRef.local(dir))
    fun localInstanceSettings(dir: String): String = instanceSettings(ServerRef.local(dir))

    fun connect(auto: Boolean, daemonId: String? = null): String =
        "connect?auto=$auto&daemonId=${daemonId.orEmpty()}"

    fun legal(docKey: String): String = "legal/$docKey"
}

/** 二级页公共路径参数（instanceId 为 String：远程是数值 id，本机是目录名）。 */
private val REF_ARGUMENTS = listOf(
    navArgument("daemonId") { type = NavType.StringType },
    navArgument("instanceId") { type = NavType.StringType },
)

/** 从返回栈条目解析实例定位符。 */
private fun androidx.navigation.NavBackStackEntry.serverRef(): ServerRef = ServerRef(
    daemonId = arguments?.getString("daemonId").orEmpty(),
    instanceId = arguments?.getString("instanceId").orEmpty(),
)

@Composable
fun AppNavHost(
    settings: AppSettings,
    navController: NavHostController = rememberNavController(),
) {
    fun topLevelRoute(page: TopPage): String = when (page) {
        TopPage.HOME -> Routes.HOME
        TopPage.INSTANCES -> Routes.INSTANCES
        TopPage.NEW_INSTANCE -> Routes.NEW_INSTANCE
        TopPage.SETTINGS -> Routes.SETTINGS
    }

    fun navigateTopLevel(route: String) {
        navController.navigate(route) {
            // 底部 tab 必须直达其根页面：popUpTo(HOME) 清掉栈里其他 tab 与二级页。
            // 不启用 saveState/restoreState：否则 popUpTo 保存的是“整段栈”
            //（例：设置→服务端总览时保存段为 [SETTINGS, SERVERS]，栈顶是 SERVERS），
            // 切回该 tab 时 restoreState 恢复整段栈，会落到二级页或看似“无反应”。
            popUpTo(Routes.HOME)
            launchSingleTop = true
        }
    }

    // 当前顶层页：底部 Dock 提升到 NavHost 外层，页面切换时 Dock 不再随页面淡出淡入重建，
    // 消除切换闪烁与 Dock 动画被转场截断的问题（Dock 仅顶层四页显示）
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentTopPage = when (backStackEntry?.destination?.route) {
        Routes.HOME -> TopPage.HOME
        Routes.INSTANCES -> TopPage.INSTANCES
        Routes.NEW_INSTANCE -> TopPage.NEW_INSTANCE
        Routes.SETTINGS -> TopPage.SETTINGS
        else -> null
    }

    // 根容器保持透明：底色由 MainActivity 的 GlassBackground 提供（毛玻璃背景层）
    Column(Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Routes.SPLASH,
            modifier = Modifier.weight(1f),
            // 页面切换瞬时完成（不用淡入淡出）：交叉淡出在部分设备上会把目标页停留在透明态，
            // 导致"整页空白只剩 Dock"；Dock 自身的选中动画保留在 MainBottomNav
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { ExitTransition.None },
        ) {

            composable(Routes.SPLASH) {
                // 用 rememberUpdatedState 保证动画结束后拿到最新的 onboarded 状态
                val latest by rememberUpdatedState(settings)
                SplashScreen(
                    onFinished = {
                        val dest = if (latest.onboarded) Routes.HOME else Routes.WELCOME
                        navController.navigate(dest) {
                            popUpTo(Routes.SPLASH) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }

            composable(Routes.WELCOME) {
                WelcomeScreen(
                    onStart = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.WELCOME) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }

            composable(Routes.HOME) {
                HomeScreen(
                    onOpenInstances = { navigateTopLevel(Routes.INSTANCES) },
                    onOpenConnect = {
                        navController.navigate(Routes.connect(false)) { launchSingleTop = true }
                    },
                    onEditDaemon = { daemonId ->
                        navController.navigate(Routes.connect(false, daemonId)) { launchSingleTop = true }
                    },
                    onOpenServer = { ref ->
                        navController.navigate(Routes.console(ref)) { launchSingleTop = true }
                    },
                )
            }

            composable(
                route = Routes.CONNECT,
                arguments = listOf(
                    navArgument("autoConnect") {
                        type = NavType.BoolType
                        defaultValue = true
                    },
                    navArgument("daemonId") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                val autoConnect = entry.arguments?.getBoolean("autoConnect") ?: true
                val daemonId = entry.arguments?.getString("daemonId")?.takeIf { it.isNotBlank() }
                ConnectScreen(
                    onConnected = {
                        // 连接成功：回到主页，由主页加载负载与实例
                        navController.navigate(Routes.HOME) {
                            popUpTo(navController.graph.id) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    onBack = { navController.popBackStack() },
                    // 自动连接失败：回主页显示"Daemon 未连接"
                    onAutoConnectFailed = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(navController.graph.id) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    autoConnect = autoConnect,
                    editingDaemonId = daemonId,
                )
            }

            composable(Routes.INSTANCES) {
                InstancesScreen(
                    onOpenHome = { navigateTopLevel(Routes.HOME) },
                    onOpenSettings = {
                        navigateTopLevel(Routes.SETTINGS)
                    },
                    onOpenNewInstance = {
                        navigateTopLevel(Routes.NEW_INSTANCE)
                    },
                    onOpenServer = { ref ->
                        navController.navigate(Routes.console(ref)) { launchSingleTop = true }
                    },
                )
            }

            composable(Routes.NEW_INSTANCE) {
                CreateInstanceScreen(
                    onOpenHome = { navigateTopLevel(Routes.HOME) },
                    onOpenInstances = { navigateTopLevel(Routes.INSTANCES) },
                    onOpenSettings = { navigateTopLevel(Routes.SETTINGS) },
                    onOpenServer = { ref ->
                        navController.navigate(Routes.console(ref)) { launchSingleTop = true }
                    },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onAddDaemon = {
                        navController.navigate(Routes.connect(false)) { launchSingleTop = true }
                    },
                    onEditDaemon = { daemonId ->
                        navController.navigate(Routes.connect(false, daemonId)) { launchSingleTop = true }
                    },
                    onOpenUserCenter = {
                        navController.navigate(Routes.USER_CENTER) { launchSingleTop = true }
                    },
                    onOpenAppearance = {
                        navController.navigate(Routes.APPEARANCE) { launchSingleTop = true }
                    },
                    onOpenLogs = {
                        navController.navigate(Routes.LOGS) { launchSingleTop = true }
                    },
                    onOpenAbout = {
                        navController.navigate(Routes.ABOUT) { launchSingleTop = true }
                    },
                    onOpenLocalServerSettings = {
                        navController.navigate(Routes.LOCAL_SERVER_SETTINGS) { launchSingleTop = true }
                    },
                    onOpenServers = {
                        navController.navigate(Routes.SERVERS) { launchSingleTop = true }
                    },
                )
            }

            // 服务端总览：多 Daemon 状态 + 本机/各 Daemon 实例统一列表
            composable(Routes.SERVERS) {
                ServersOverviewScreen(
                    onBack = { navController.popBackStack() },
                    onOpenServer = { ref ->
                        navController.navigate(Routes.console(ref)) { launchSingleTop = true }
                    },
                    onOpenCreate = {
                        navigateTopLevel(Routes.NEW_INSTANCE)
                    },
                )
            }

            composable(Routes.LOCAL_SERVER_SETTINGS) {
                LocalServerSettingsScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.APPEARANCE) {
                AppearanceScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.ABOUT) {
                AboutScreen(
                    onBack = { navController.popBackStack() },
                    onOpenLegal = { key ->
                        navController.navigate(Routes.legal(key)) { launchSingleTop = true }
                    },
                )
            }

            // 合规文档：第三方组件与许可 / 第三方免责声明（文案与首次开屏同源）
            composable(
                route = Routes.LEGAL,
                arguments = listOf(navArgument("docKey") { type = NavType.StringType }),
            ) { entry ->
                LegalDocumentScreen(
                    docKey = entry.arguments?.getString("docKey").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }

            composable(Routes.LOGS) {
                LogViewerScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.USER_CENTER) {
                UserCenterScreen(onBack = { navController.popBackStack() })
            }

            // 统一控制台：本机实例与远程实例同一套界面，仅控制器实现不同
            composable(
                route = Routes.CONSOLE,
                arguments = REF_ARGUMENTS,
            ) { backStackEntry ->
                val ref = backStackEntry.serverRef()
                val controller: com.mslx.console.ui.console.ConsoleController = if (ref.isLocal) {
                    viewModel<LocalConsoleViewModel>(
                        key = "localconsole_${ref.instanceId}",
                        factory = viewModelFactory {
                            initializer {
                                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                                LocalConsoleViewModel(app, ref.instanceId)
                            }
                        },
                    )
                } else {
                    viewModel<ConsoleViewModel>(
                        key = "console_${ref.catalogKey}",
                        factory = viewModelFactory {
                            initializer {
                                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                                ConsoleViewModel(app, ref)
                            }
                        },
                    )
                }
                ConsoleScreen(
                    controller = controller,
                    onBack = { navController.popBackStack() },
                    onOpenSettings = {
                        navController.navigate(Routes.instanceSettings(ref)) {
                            launchSingleTop = true
                        }
                    },
                )
            }

            // 实例设置：本机走实例目录（公共/私有）直编页，远程走 Daemon API 页
            composable(
                route = Routes.INSTANCE_SETTINGS,
                arguments = REF_ARGUMENTS,
            ) { backStackEntry ->
                val ref = backStackEntry.serverRef()
                if (ref.isLocal) {
                    LocalInstanceSettingsScreen(
                        dirName = ref.instanceId,
                        onBack = { navController.popBackStack() },
                    )
                } else {
                    InstanceSettingsScreen(
                        ref = ref,
                        onBack = { navController.popBackStack() },
                        onOpenPluginsMods = {
                            navController.navigate(Routes.pluginsMods(ref)) { launchSingleTop = true }
                        },
                        onOpenServerProps = {
                            navController.navigate(Routes.serverProps(ref)) { launchSingleTop = true }
                        },
                        onOpenFileManager = {
                            navController.navigate(Routes.fileManager(ref)) { launchSingleTop = true }
                        },
                    )
                }
            }

            composable(
                route = Routes.FILE_MANAGER,
                arguments = REF_ARGUMENTS,
            ) { backStackEntry ->
                val ref = backStackEntry.serverRef()
                if (ref.isLocal) {
                    LocalInstancePendingScreen(
                        dirName = ref.instanceId,
                        onOpenSettings = {
                            navController.navigate(Routes.instanceSettings(ref)) { launchSingleTop = true }
                        },
                        onBack = { navController.popBackStack() },
                    )
                } else {
                    FileManagerScreen(
                        ref = ref,
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            composable(
                route = Routes.PLUGINS_MODS,
                arguments = REF_ARGUMENTS,
            ) { backStackEntry ->
                val ref = backStackEntry.serverRef()
                if (ref.isLocal) {
                    LocalInstancePendingScreen(
                        dirName = ref.instanceId,
                        onOpenSettings = {
                            navController.navigate(Routes.instanceSettings(ref)) { launchSingleTop = true }
                        },
                        onBack = { navController.popBackStack() },
                    )
                } else {
                    PluginsModsScreen(
                        ref = ref,
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            composable(
                route = Routes.SERVER_PROPS,
                arguments = REF_ARGUMENTS,
            ) { backStackEntry ->
                val ref = backStackEntry.serverRef()
                if (ref.isLocal) {
                    LocalInstancePendingScreen(
                        dirName = ref.instanceId,
                        onOpenSettings = {
                            navController.navigate(Routes.instanceSettings(ref)) { launchSingleTop = true }
                        },
                        onBack = { navController.popBackStack() },
                    )
                } else {
                    ServerPropertiesScreen(
                        ref = ref,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }

        // 底部 Dock：与页面解耦，切换时保持稳定（不重建、动画不被截断）
        currentTopPage?.let { topPage ->
            MainBottomNav(
                current = topPage,
                onNavigate = { page ->
                    if (page == TopPage.NEW_INSTANCE && topPage == TopPage.NEW_INSTANCE) {
                        // 重按"新建"tab：重置新建实例表单（Dock 与页面 ViewModel 经 CreateResetBus 桥接）
                        CreateResetBus.request()
                    } else {
                        navigateTopLevel(topLevelRoute(page))
                    }
                },
            )
        }
    }
}
