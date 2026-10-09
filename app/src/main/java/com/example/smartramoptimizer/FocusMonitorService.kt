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
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Method
import java.util.LinkedList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class FocusMonitorService : Service() {
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private var lastForegroundPackage: String? = null
    private val activeAppQueue = LinkedList<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        createNotificationChannel()
        val notif = buildNotification("Monitoring active app limits")

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
                    enforceAppLimit(foreground)
                    updateNotification("Current App: ${appLabel(foreground)}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in monitor loop", e)
            }
            try {
                Thread.sleep(1500)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun findForegroundPackage(now: Long): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usm.queryEvents(now - 10_000, now)
        val event = UsageEvents.Event()
        var latestPackage: String? = null
        var latestTime = 0L

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val isForeground = event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                (Build.VERSION.SDK_INT >= 29 && event.eventType == UsageEvents.Event.ACTIVITY_RESUMED)
            if (isForeground && event.timeStamp >= latestTime) {
                latestTime = event.timeStamp
                latestPackage = event.packageName
            }
        }
        return latestPackage
    }

    private fun enforceAppLimit(activePackage: String) {
        val prefs = getSharedPreferences("optimizer", Context.MODE_PRIVATE)
        val maxAllowed = prefs.getInt("max_allowed_apps", 1)
        val selected = prefs.getStringSet("selected", emptySet()) ?: emptySet()
        val protected = prefs.getStringSet("protected", emptySet()) ?: emptySet()

        synchronized(activeAppQueue) {
            activeAppQueue.remove(activePackage)
            activeAppQueue.addFirst(activePackage)

            val toKill = mutableListOf<String>()

            // If an app is in selected list and exceeds the allowed concurrency window, queue to stop
            val managedActive = activeAppQueue.filter { it in selected && it !in protected }
            if (managedActive.size > maxAllowed) {
                val excess = managedActive.subList(maxAllowed, managedActive.size)
                toKill.addAll(excess)
                activeAppQueue.removeAll(excess.toSet())
            }

            // Also terminate any selected app that is not in the active foreground queue at all
            for (pkg in selected) {
                if (pkg !in protected && pkg !in activeAppQueue && pkg != activePackage) {
                    toKill.add(pkg)
                }
            }

            if (toKill.isNotEmpty()) {
                killApps(toKill.distinct())
            }
        }
    }

    private fun killApps(targets: List<String>) {
        if (!Shizuku.pingBinder()) return
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) return

        for (pkg in targets) {
            if (pkg == "com.android.systemui" || pkg == "com.android.settings" || pkg == packageName) continue
            val cleanPkg = pkg.replace("'", "'\\''")
            executeShizukuCommand("am force-stop '$cleanPkg'")
        }
    }

    private fun executeShizukuCommand(command: String) {
        try {
            val cmdArray = arrayOf("sh", "-c", command)
            val method: Method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, cmdArray, null, null)

            if (process != null) {
                val outputStream = process.javaClass.getMethod("getOutputStream").invoke(process) as? OutputStream
                val inputStream = process.javaClass.getMethod("getInputStream").invoke(process) as? InputStream
                val errorStream = process.javaClass.getMethod("getErrorStream").invoke(process) as? InputStream

                outputStream?.close()
                inputStream?.close()
                errorStream?.close()
                process.javaClass.getMethod("waitFor").invoke(process)
            }
        } catch (_: Exception) {}
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
        private const val TAG = "SmartRAMOptimizer"
        const val ACTION_START = "com.example.smartramoptimizer.START"
        const val ACTION_STOP = "com.example.smartramoptimizer.STOP"
        private const val CHANNEL_ID = "focus_monitor"
        private const val NOTIFICATION_ID = 73
    }
}
