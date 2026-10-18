package com.fft.routerkeepalive.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.fft.routerkeepalive.domain.RuntimePresentation.dataTransferActivityProgress

/**
 * Foreground service, the Android counterpart of the HarmonyOS `dataTransfer` continuous task.
 *
 * Android 14+ (API 34) requires a declared foregroundServiceType; `dataSync` is the closest match
 * for periodic low-volume network work. On API 34+ the start reason is surfaced through
 * [KeepAliveController.onServiceStopped], matching the HarmonyOS cancel-reason reporting.
 */
class KeepAliveService : Service() {

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            // Mark the stop as intentional so onDestroy does not report a permission problem.
            userRequestedStop = true
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        userRequestedStop = false
        val notifications = NotificationService(applicationContext)
        notifications.ensureChannels()
        val notification = notifications.buildKeepAliveNotification(
            summary = "准备进行网络探测",
            progressValue = dataTransferActivityProgress(0)
        )
        try {
            ServiceCompat.startForeground(
                this,
                NotificationService.KEEP_ALIVE_NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                }
            )
        } catch (error: Exception) {
            // No system service is allowed to run; report the limitation instead of crashing.
            KeepAliveController.onServiceUnavailable(
                (error as? android.os.RemoteException)?.let { -1 } ?: -1,
                error.message ?: error.javaClass.simpleName
            )
            stopSelf()
            return
        }
        KeepAliveController.onServiceStarted()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The HarmonyOS build treats a removed continuous-task notification as a cancel reason 1.
        if (intent?.action == ACTION_USER_DISMISS) {
            KeepAliveController.onServiceStopped(1)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // USER_CANCEL is reason 1 in the HarmonyOS continuous-task contract; SYSTEM_CANCEL is 2.
        // When the user flipped the master switch off, the stop is intentional and must not be
        // reported as "后台权限受限" — the preference already records the intent.
        if (!userRequestedStop) {
            KeepAliveController.onServiceStopped(1)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

private const val ACTION_USER_DISMISS = "com.fft.routerkeepalive.action.DISMISS"

/** True between a user-initiated stop() and the matching onDestroy(), so the destroy is silent. */
@Volatile
private var userRequestedStop: Boolean = false
