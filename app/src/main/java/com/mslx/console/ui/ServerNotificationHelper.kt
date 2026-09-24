package com.mslx.console.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.mslx.console.MainActivity
import com.mslx.console.R

/**
 * 服务器启停状态的原生通知。
 * 点击通知打开应用并跳转到对应实例控制台（通过 MainActivity 的 EXTRA_DAEMON_ID + EXTRA_INSTANCE_ID）。
 */
object ServerNotificationHelper {

    private const val CHANNEL_ID = "server_status"
    private const val CHANNEL_NAME = "服务器状态"
    private const val NOTIFICATION_ID_PREFIX = 1000

    const val EXTRA_INSTANCE_ID = "extra_instance_id"
    const val EXTRA_DAEMON_ID = "extra_daemon_id"

    /** 多 Daemon 下 instanceId 可能重复，用 daemonId 高位偏移避开碰撞。 */
    private fun notificationId(daemonId: String, instanceId: Long): Int =
        NOTIFICATION_ID_PREFIX + instanceId.toInt() + (daemonId.hashCode().and(0xFF) shl 20)

    /** 确保通知渠道存在（需在发通知前调用）。 */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "服务器实例启动/停止状态变化"
        }
        manager.createNotificationChannel(channel)
    }

    /** 发送一条实例开服/关服通知。 */
    fun notifyServerStatus(
        context: Context,
        daemonId: String,
        instanceId: Long,
        instanceName: String,
        isOpened: Boolean,
    ) {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_DAEMON_ID, daemonId)
            putExtra(EXTRA_INSTANCE_ID, instanceId)
        }
        val pending = PendingIntent.getActivity(
            context,
            notificationId(daemonId, instanceId),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = if (isOpened) "服务器已开服" else "服务器已关服"
        val body = if (isOpened) "$instanceName 已进入运行状态" else "$instanceName 已停止运行"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.mslx_logo)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        try {
            manager.notify(notificationId(daemonId, instanceId), notification)
        } catch (_: SecurityException) {
            // 用户未授予通知权限，静默忽略
        }
    }
}
