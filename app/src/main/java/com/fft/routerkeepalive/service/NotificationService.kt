package com.fft.routerkeepalive.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.fft.routerkeepalive.MainActivity
import com.fft.routerkeepalive.R
import com.fft.routerkeepalive.domain.RuntimePresentation.dataTransferActivityProgress

/**
 * Notifications, ported 1:1 from the HarmonyOS `NotificationService`.
 *
 * The HarmonyOS build uses a system LIVE_VIEW / downloadTemplate slot to publish the continuous
 * task progress. Android has no equivalent third-party live-view slot, so the same information is
 * published as a foreground-service notification on a low-importance channel plus a separate
 * failure alert on a high-importance channel. The notification ids, text templates and update
 * cadence are identical.
 */
class NotificationService(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Channels only exist from API 26; below that the default behaviour is used.
            return
        }
        val keepAlive = NotificationChannel(
            CHANNEL_KEEP_ALIVE,
            context.getString(R.string.channel_keep_alive_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.channel_keep_alive_desc)
            setShowBadge(false)
        }
        val failure = NotificationChannel(
            CHANNEL_FAILURE,
            context.getString(R.string.channel_failure_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.channel_failure_desc)
        }
        notificationManager.createNotificationChannel(keepAlive)
        notificationManager.createNotificationChannel(failure)
    }

    /**
     * Replaces the HarmonyOS `updateKeepAliveActivity`: same id, same summary wording, same
     * progress value, delivered as an updatable foreground-service notification.
     */
    fun updateKeepAliveActivity(
        completedProbeCount: Int,
        succeeded: Boolean? = null,
        durationMs: Double? = null
    ): Boolean {
        try {
            var summary = when (completedProbeCount) {
                0 -> "准备进行网络探测"
                else -> "已完成 $completedProbeCount 次网络探测"
            }
            if (succeeded != null && durationMs != null) {
                val duration = kotlin.math.floor(durationMs + 0.5).toLong()
                summary += if (succeeded) "，最近成功 $duration ms" else "，最近探测失败"
            }
            val progressValue = dataTransferActivityProgress(completedProbeCount)
            val notification = buildKeepAliveNotification(summary, progressValue)
            notificationManager.notify(KEEP_ALIVE_NOTIFICATION_ID, notification)
            return true
        } catch (_: Exception) {
            return false
        }
    }

    fun buildKeepAliveNotification(summary: String, progressValue: Int): Notification {
        val launch = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pending = PendingIntent.getActivity(
            context, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_KEEP_ALIVE)
            .setSmallIcon(R.drawable.ic_stat_keepalive)
            .setContentTitle(context.getString(R.string.notification_keep_alive_title))
            .setContentText(summary)
            .setProgress(100, progressValue, false)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pending)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun showFailure(consecutiveFailures: Int) {
        try {
            val launch = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pending = PendingIntent.getActivity(
                context, 1, launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_FAILURE)
                .setSmallIcon(R.drawable.ic_stat_warning)
                .setContentTitle("路由不闲：网络探测异常")
                .setContentText("已连续 $consecutiveFailures 轮主备地址均失败，请检查 Wi-Fi 认证。")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build()
            notificationManager.notify(FAILURE_NOTIFICATION_ID, notification)
        } catch (_: Exception) {
        }
    }

    fun clearFailure() {
        try {
            notificationManager.cancel(FAILURE_NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }

    fun cancelKeepAlive() {
        try {
            notificationManager.cancel(KEEP_ALIVE_NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }

    companion object {
        const val CHANNEL_KEEP_ALIVE = "router_keepalive_running"
        const val CHANNEL_FAILURE = "router_keepalive_failure"
        const val KEEP_ALIVE_NOTIFICATION_ID = 2001
        const val FAILURE_NOTIFICATION_ID = 2002
    }
}
