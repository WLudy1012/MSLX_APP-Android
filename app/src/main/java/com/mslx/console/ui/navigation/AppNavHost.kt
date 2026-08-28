package com.mslx.console.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
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
import com.mslx.console.data.AppSettings
import com.mslx.console.ui.MainBottomNav
import com.mslx.console.ui.TopPage
import com.mslx.console.ui.connect.ConnectScreen
import com.mslx.console.ui.console.ConsoleScreen
import com.mslx.console.ui.create.CreateInstanceScreen
import com.mslx.console.ui.create.CreateResetBus
import com.mslx.console.ui.home.HomeScreen
import com.mslx.console.ui.instances.InstancesScreen
import com.mslx.console.ui.settings.InstanceSettingsScreen
import com.mslx.console.ui.settings.FileManagerScreen
import com.mslx.console.ui.settings.PluginsModsScreen
import com.mslx.console.ui.settings.ServerPropertiesScreen
import com.mslx.console.ui.settings.SettingsScreen
import com.mslx.console.ui.settings.AppearanceScreen
import com.mslx.console.ui.settings.AboutScreen
import com.mslx.console.ui.settings.LogViewerScreen
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
    const val CONSOLE = "console/{instanceId}"
    const val INSTANCE_SETTINGS = "instanceSettings/{instanceId}"
    const val FILE_MANAGER = "fileManager/{instanceId}"
    const val PLUGINS_MODS = "pluginsMods/{instanceId}"
    const val SERVER_PROPS = "serverProps/{instanceId}"
    const val USER_CENTER = "userCenter"
    const val APPEARANCE = "appearance"
    const val ABOUT = "about"
    const val LOGS = "logs"

    fun console(instanceId: Long): String = "console/$instanceId"
    fun connect(auto: Boolean, daemonId: String? = null): String =
        "connect?auto=$auto&daemonId=${daemonId.orEmpty()}"
    fun instanceSettings(instanceId: Long): String = "instanceSettings/$instanceId"
    fun fileManager(instanceId: Long): String = "fileManager/$instanceId"
    fun pluginsMods(instanceId: Long): String = "pluginsMods/$instanceId"
    fun serverProps(instanceId: Long): String = "serverProps/$instanceId"
}

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
            popUpTo(Routes.HOME) { saveState = true }
            launchSingleTop = true
            restoreState = true
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

    Column(
        Modifier
            .fillMaxSize()
            // 主题背景兜底：页面切换瞬间不露出窗口白底
            .background(MaterialTheme.colorScheme.background),
    ) {
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
                    onOpenNewInstance = { navigateTopLevel(Routes.NEW_INSTANCE) },
                    onOpenSettings = { navigateTopLevel(Routes.SETTINGS) },
                    onOpenConnect = {
                        navController.navigate(Routes.connect(false)) { launchSingleTop = true }
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
                    onOpenInstance = { id ->
                        navController.navigate(Routes.console(id)) { launchSingleTop = true }
                    },
                )
            }

            composable(Routes.NEW_INSTANCE) {
                CreateInstanceScreen(
                    onOpenHome = { navigateTopLevel(Routes.HOME) },
                    onOpenInstances = { navigateTopLevel(Routes.INSTANCES) },
                    onOpenSettings = { navigateTopLevel(Routes.SETTINGS) },
                    onOpenConsole = { id ->
                        navController.navigate(Routes.console(id)) { launchSingleTop = true }
                    },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onOpenHome = { navigateTopLevel(Routes.HOME) },
                    onOpenInstances = {
                        navigateTopLevel(Routes.INSTANCES)
                    },
                    onOpenNewInstance = {
                        navigateTopLevel(Routes.NEW_INSTANCE)
                    },
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
                )
            }

            composable(Routes.APPEARANCE) {
                AppearanceScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.ABOUT) {
                AboutScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.LOGS) {
                LogViewerScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.USER_CENTER) {
                UserCenterScreen(onBack = { navController.popBackStack() })
            }

            composable(
                route = Routes.CONSOLE,
                arguments = listOf(navArgument("instanceId") { type = NavType.LongType }),
            ) { backStackEntry ->
                val instanceId = backStackEntry.arguments?.getLong("instanceId") ?: 0L
                ConsoleScreen(
                    instanceId = instanceId,
                    onBack = { navController.popBackStack() },
                    onOpenSettings = {
                        navController.navigate(Routes.instanceSettings(instanceId)) {
                            launchSingleTop = true
                        }
                    },
                )
            }

            composable(
                route = Routes.INSTANCE_SETTINGS,
                arguments = listOf(navArgument("instanceId") { type = NavType.LongType }),
            ) { backStackEntry ->
                val instanceId = backStackEntry.arguments?.getLong("instanceId") ?: 0L
                InstanceSettingsScreen(
                    instanceId = instanceId,
                    onBack = { navController.popBackStack() },
                    onOpenPluginsMods = {
                        navController.navigate(Routes.pluginsMods(instanceId)) { launchSingleTop = true }
                    },
                    onOpenServerProps = {
                        navController.navigate(Routes.serverProps(instanceId)) { launchSingleTop = true }
                    },
                    onOpenFileManager = {
                        navController.navigate(Routes.fileManager(instanceId)) { launchSingleTop = true }
                    },
                )
            }

            composable(
                route = Routes.FILE_MANAGER,
                arguments = listOf(navArgument("instanceId") { type = NavType.LongType }),
            ) { backStackEntry ->
                val instanceId = backStackEntry.arguments?.getLong("instanceId") ?: 0L
                FileManagerScreen(
                    instanceId = instanceId,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.PLUGINS_MODS,
                arguments = listOf(navArgument("instanceId") { type = NavType.LongType }),
            ) { backStackEntry ->
                val instanceId = backStackEntry.arguments?.getLong("instanceId") ?: 0L
                PluginsModsScreen(
                    instanceId = instanceId,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = Routes.SERVER_PROPS,
                arguments = listOf(navArgument("instanceId") { type = NavType.LongType }),
            ) { backStackEntry ->
                val instanceId = backStackEntry.arguments?.getLong("instanceId") ?: 0L
                ServerPropertiesScreen(
                    instanceId = instanceId,
                    onBack = { navController.popBackStack() },
                )
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
