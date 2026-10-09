package com.example.smartramoptimizer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class FocusMonitorService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private var lastForegroundPackage: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        createNotificationChannel()
        val notif = buildNotification("Monitoring foreground app")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notif,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    0
                }
            )
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }

        if (running.compareAndSet(false, true)) {
            executor.execute { monitorLoop() }
        }
        return START_STICKY
    }

    private fun monitorLoop() {
        while (running.get()) {
            try {
                val now = System.currentTimeMillis()
                val foreground = findForegroundPackage(now)
                if (!foreground.isNullOrBlank() && foreground != packageName && foreground != lastForegroundPackage) {
                    lastForegroundPackage = foreground
                    handleForegroundChange(foreground)
                    updateNotification("Active app: ${appLabel(foreground)}")
                }
            } catch (_: Exception) {
                // UsageStats can vary across OEM configurations
            }
            try {
                Thread.sleep(2500)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun findForegroundPackage(now: Long): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(now - 15_000, now)
        val event = UsageEvents.Event()
        var latestPackage: String? = null
        var latestTime = 0L

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val foregroundEvent = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                (Build.VERSION.SDK_INT >= 29 && event.eventType == UsageEvents.Event.ACTIVITY_RESUMED)
            if (foregroundEvent && event.timeStamp >= latestTime) {
                latestTime = event.timeStamp
                latestPackage = event.packageName
            }
        }
        return latestPackage
    }

    private fun handleForegroundChange(activePackage: String) {
        val prefs = getSharedPreferences("optimizer", Context.MODE_PRIVATE)
        val selected = prefs.getStringSet("selected", emptySet()) ?: emptySet()
        val protected = prefs.getStringSet("protected", emptySet()) ?: emptySet()
        val targets = selected.filter {
            it != activePackage &&
            it != packageName &&
            it !in protected &&
            it != "com.android.systemui" &&
            it != "com.android.settings"
        }

        if (targets.isEmpty() || !Shizuku.pingBinder()) return
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) return

        for (pkg in targets) {
            try {
                val cleanPkg = pkg.replace("'", "'\\''")
                val process = Shizuku.newProcess(arrayOf("sh", "-c", "am force-stop '$cleanPkg'"), null, null)
                process.outputStream.close()
                process.inputStream.close()
                process.errorStream.close()
                process.waitFor()
            } catch (_: Exception) {
                // Command availability depends on Shizuku state and device security policy
            }
        }
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Focus Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Smart RAM Optimizer")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        running.set(false)
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.smartramoptimizer.START"
        const val ACTION_STOP = "com.example.smartramoptimizer.STOP"
        private const val CHANNEL_ID = "focus_monitor"
        private const val NOTIFICATION_ID = 73
    }
}
