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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.mslx.console.data.AppSettings
import com.mslx.console.ui.ConnectivityHost
import com.mslx.console.ui.ServerNotificationHelper
import com.mslx.console.ui.navigation.AppNavHost
import com.mslx.console.ui.navigation.Routes
import com.mslx.console.ui.theme.MSLXConsoleTheme
import com.mslx.console.ui.theme.ThemeConfig
import com.mslx.console.ui.update.CrashReportDialog
import com.mslx.console.ui.update.DisclaimerDialog
import com.mslx.console.ui.update.UpdateHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** 通知点击待打开的实例 id（-1 表示无）。 */
    private val pendingInstanceId = mutableLongStateOf(-1L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        enableEdgeToEdge()
        pendingInstanceId.longValue =
            intent?.getLongExtra(ServerNotificationHelper.EXTRA_INSTANCE_ID, -1L) ?: -1L

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
            LaunchedEffect(settings.onboarded, pendingInstanceId.longValue) {
                if (pendingInstanceId.longValue > 0 && settings.onboarded) {
                    delay(2400) // 等待 SPLASH(1.6s) + 导航动画完成
                    navController.navigate(Routes.console(pendingInstanceId.longValue)) {
                        launchSingleTop = true
                    }
                    pendingInstanceId.longValue = -1
                }
            }

            MSLXConsoleTheme(
                themeConfig = ThemeConfig(
                    mode = settings.themeMode,
                    seedColor = settings.seedColor,
                ),
            ) {
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingInstanceId.longValue =
            intent.getLongExtra(ServerNotificationHelper.EXTRA_INSTANCE_ID, -1L)
    }
}
