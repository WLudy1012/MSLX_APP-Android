package com.mslx.console.data.localengine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mslx.console.MainActivity
import com.mslx.console.R
import com.mslx.console.data.AppLogger
import com.mslx.console.data.ServerRef
import com.mslx.console.ui.ServerNotificationHelper

/**
 * 本机开服的前台服务：只负责「保活 + 常驻通知 + 通知栏停止」。
 *
 * 服务端 JVM 跑在 App 进程内（见 LocalJvmLauncher），所以进程活着服务端就活着；
 * Android 8+ 后台进程会被随时回收，前台服务（带常驻通知）是唯一不需要 root、
 * 又能稳定保活的做法 —— Zalith Launcher / PojavLauncher 也是同一个思路。
 * （adb 只能临时 `am start-foreground-service` 触发一次，拔线/重启即失效，不是保活手段。）
 *
 * 注意：本服务**不持有**服务端实例，所有状态都在 [LocalServerRuntime]，
 * 因此 Activity 重建/通知点按回 App 都不会影响运行中的服务端。
 */
class LocalServerService : Service() {

    companion object {
        private const val TAG = "LocalServerService"
        private const val CHANNEL_ID = "local_server"
        private const val NOTIFICATION_ID = 4711
        private const val ACTION_STOP = "com.mslx.console.action.LOCAL_SERVER_STOP"

        fun start(context: Context) {
            val intent = Intent(context, LocalServerService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LocalServerService::class.java)) }
                .onFailure { AppLogger.w(TAG, "停止前台服务失败", it) }
        }

        /** 创建通知渠道（幂等），Application 启动时调用一次。 */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "本机开服",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "本机服务端运行时的常驻通知"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppLogger.i(TAG, "收到通知栏停止请求")
            LocalServerRuntime.append("> stop（来自通知栏）")
            LocalServerRuntime.stop()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        AppLogger.i(TAG, "前台服务已启动，服务端开始保活")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        AppLogger.i(TAG, "前台服务已停止")
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val runningDir = LocalServerRuntime.currentDirName
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                // 带上当前运行实例的定位符：点按常驻通知直达该实例控制台（本机实例 daemonId=local）
                if (runningDir.isNotBlank()) {
                    putExtra(ServerNotificationHelper.EXTRA_DAEMON_ID, ServerRef.LOCAL_DAEMON_ID)
                    putExtra(ServerNotificationHelper.EXTRA_INSTANCE_ID, runningDir)
                }
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, LocalServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val name = LocalServerRuntime.currentServerName.ifBlank { "本地服务端" }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("服务端运行中：$name")
            .setContentText("本机开服 · 点按返回控制台")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openApp)
            .addAction(0, "停止服务端", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
