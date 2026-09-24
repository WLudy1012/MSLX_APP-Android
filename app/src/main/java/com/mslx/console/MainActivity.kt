package com.mslx.console

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mslx.console.data.AppSettings
import com.mslx.console.data.ServerRef
import com.mslx.console.ui.ConnectivityHost
import com.mslx.console.ui.ServerNotificationHelper
import com.mslx.console.ui.navigation.AppNavHost
import com.mslx.console.ui.navigation.Routes
import com.mslx.console.ui.theme.GlassBackground
import com.mslx.console.ui.theme.LocalGlassAlpha
import com.mslx.console.ui.theme.MSLXConsoleTheme
import com.mslx.console.ui.theme.ThemeConfig
import com.mslx.console.ui.update.CrashReportDialog
import com.mslx.console.ui.update.DisclaimerDialog
import com.mslx.console.ui.update.UpdateHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** 通知点击待打开的实例（去主连接：daemonId + instanceId 才能定位）；null 表示无。 */
    private val pendingRef = mutableStateOf<ServerRef?>(null)

    /** 从 Intent 取通知携带的实例定位符（本机实例 instanceId = 目录名，因此按 String 读取）。 */
    private fun intentServerRef(intent: Intent?): ServerRef? {
        val instanceId = intent?.getStringExtra(ServerNotificationHelper.EXTRA_INSTANCE_ID)
        val daemonId = intent?.getStringExtra(ServerNotificationHelper.EXTRA_DAEMON_ID)
        if (instanceId.isNullOrBlank() || daemonId.isNullOrBlank()) return null
        return ServerRef(daemonId, instanceId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        enableEdgeToEdge()
        pendingRef.value = intentServerRef(intent)

        val app = application as MSLXApplication
        setContent {
            // 用 nullable 初始值区分"尚未加载"与"已加载"：免责协议必须等 DataStore
            // 首次真实值落盘后再渲染，避免非初次打开时 disclaimerAccepted 一闪而过。
            val settingsState by app.container.settingsStore.settingsFlow
                .collectAsStateWithLifecycle(initialValue = null as AppSettings?)
            val settings = settingsState ?: AppSettings()
            val scope = rememberCoroutineScope()
            val context = LocalContext.current
            val navController = rememberNavController()

            // Android 13+ 请求通知权限（用于服务器启停状态通知）
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { }
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            // 启动完成后（onboarded 且 SPLASH 结束）跳转到通知对应的实例控制台
            LaunchedEffect(settings.onboarded, pendingRef.value) {
                val ref = pendingRef.value
                if (ref != null && settings.onboarded) {
                    delay(2400) // 等待 SPLASH(1.6s) + 导航动画完成
                    navController.navigate(Routes.console(ref)) {
                        launchSingleTop = true
                    }
                    pendingRef.value = null
                }
            }

            val themeConfig = ThemeConfig(
                mode = settings.themeMode,
                seedColor = settings.seedColor,
                glassAlpha = settings.glassAlpha,
                lightBackground = settings.lightBackgroundPath,
                darkBackground = settings.darkBackgroundPath,
            )
            // 当前路由：毛玻璃背景的转场增强按页面变化触发
            val routeEntry by navController.currentBackStackEntryAsState()

            MSLXConsoleTheme(themeConfig = themeConfig) {
                CompositionLocalProvider(LocalGlassAlpha provides themeConfig.glassAlpha) {
                    Box(Modifier.fillMaxSize()) {
                        // 毛玻璃背景层：所有页面共用（页面 Scaffold 保持透明以透出背景）
                        GlassBackground(
                            config = themeConfig,
                            transitionKey = routeEntry?.destination?.route,
                        )
                        AppNavHost(settings = settings, navController = navController)
                        // 全局更新弹窗：启动自动检查 + 手动检查结果都走这里
                        UpdateHost()
                        // 连接连通性监视：5 秒一轮，在线→离线弹窗提醒
                        ConnectivityHost()
                        // 崩溃报告弹窗：上次会话发生未捕获异常时展示
                        CrashReportDialog()
                        // 首次开屏免责协议：5 秒后可确认，同意后持久化（仅真实加载后渲染）
                        if (settingsState != null) {
                            DisclaimerDialog(
                                settings = settingsState!!,
                                onAccept = {
                                    scope.launch { app.container.settingsStore.acceptDisclaimer() }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingRef.value = intentServerRef(intent)
    }
}
