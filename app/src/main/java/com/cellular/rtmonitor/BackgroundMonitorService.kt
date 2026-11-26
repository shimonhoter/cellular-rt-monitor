// Update to version 4.1.3
package com.cellular.rtmonitor

// [START: Imports]
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
// [END: Imports]

// [START: BackgroundMonitorServiceClass]
class BackgroundMonitorService : Service() {

    companion object {
        private const val TAG = "BackgroundMonitorService"
        private const val CHANNEL_ID = "cellular_rt_monitor_bg_channel"
        private const val CHANNEL_NAME = "Cellular RT Background Monitor"
        private const val NOTIFICATION_ID = 4101
        private const val POLL_INTERVAL_MS = 5000L

        const val ACTION_START = "com.cellular.rtmonitor.action.START_BG_MONITOR"
        const val ACTION_STOP = "com.cellular.rtmonitor.action.STOP_BG_MONITOR"

        fun startService(context: Context) {
            Log.i(TAG, "startService requested")
            val intent = Intent(context, BackgroundMonitorService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            Log.i(TAG, "stopService requested")
            val intent = Intent(context, BackgroundMonitorService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    // [START: ServiceStateAndHandler]
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isMonitoring = false
    private var tickCount = 0L

    private val telephonyManager: TelephonyManager by lazy {
        getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    }

    private val monitorRunnable = object : Runnable {
        override fun run() {
            if (!isMonitoring) return
            tickCount += 1
            recordTelemetryTick(tickCount)
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }
    // [END: ServiceStateAndHandler]

    // [START: LifecycleMethods]
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate: creating notification channel and initializing service")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Log.i(TAG, "onStartCommand: action=$action, startId=$startId, flags=$flags")

        if (action == ACTION_STOP) {
            stopMonitoringAndService()
            return START_NOT_STICKY
        }

        val notification = buildForegroundNotification("ניטור רשת סלולרית פעיל ברקע")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            // the "location" type is what lets the app keep reading cell info / GPS while in the background
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            try {
                startForeground(NOTIFICATION_ID, notification, type)
            } catch (e: Exception) {
                Log.e(TAG, "startForeground with type failed: ${e.message}")
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.i(TAG, "startForeground active with notificationId=$NOTIFICATION_ID")

        RecorderHub.start(this)   // recording runs here, independent of the activity

        if (!isMonitoring) {
            isMonitoring = true
            tickCount = 0L
            mainHandler.removeCallbacks(monitorRunnable)
            mainHandler.post(monitorRunnable)
            Log.i(TAG, "Background monitoring loop started (interval=${POLL_INTERVAL_MS}ms)")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: stopping monitoring loop and cleaning up")
        isMonitoring = false
        mainHandler.removeCallbacks(monitorRunnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    // [END: LifecycleMethods]

    // [START: TelemetryAndNotificationHelpers]
    private fun stopMonitoringAndService() {
        Log.i(TAG, "stopMonitoringAndService: halting loop")
        RecorderHub.stop()
        isMonitoring = false
        mainHandler.removeCallbacks(monitorRunnable)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun recordTelemetryTick(tick: Long) {
        try {
            val op = telephonyManager.networkOperatorName ?: ""
            val total = RecorderHub.store(this).total()
            updateNotificationText("מקליט ברקע • ${"%,d".format(total)} דגימות" + if (op.isNotBlank()) " • $op" else "")
        } catch (e: Exception) {
            Log.e(TAG, "tick#$tick | notification update failed: ${e.message}", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "התראה קבועה בזמן ניטור נתוני רשת סלולרית ברקע"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
            Log.i(TAG, "NotificationChannel created: id=$CHANNEL_ID")
        }
    }

    private fun buildForegroundNotification(contentText: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            pendingIntentFlags
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Cellular RT Monitor")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .build()
    }

    private fun updateNotificationText(contentText: String) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildForegroundNotification(contentText))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification text: ${e.message}")
        }
    }
    // [END: TelemetryAndNotificationHelpers]
}
// [END: BackgroundMonitorServiceClass]
