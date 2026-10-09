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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
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
            Log.d(TAG, "Starting monitorLoop...")
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
                    Log.d(TAG, "Foreground changed to: $foreground (previous: $lastForegroundPackage)")
                    lastForegroundPackage = foreground
                    handleForegroundChange(foreground)
                    updateNotification("Active app: ${appLabel(foreground)}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in monitor loop", e)
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

        Log.d(TAG, "Active: $activePackage | Targets to stop: $targets")

        if (targets.isEmpty()) return

        if (!Shizuku.pingBinder()) {
            Log.e(TAG, "Shizuku pingBinder failed! Service not connected.")
            return
        }

        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Shizuku permission NOT granted!")
            return
        }

        for (pkg in targets) {
            val cleanPkg = pkg.replace("'", "'\\''")
            executeShizukuCommand("am force-stop '$cleanPkg'")
        }
    }

    private fun executeShizukuCommand(command: String) {
        try {
            Log.d(TAG, "Executing command: $command")
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
                val inputStream = process.javaClass.getMethod("getInputStream").invoke(process) as? java.io.InputStream
                val errorStream = process.javaClass.getMethod("getErrorStream").invoke(process) as? java.io.InputStream

                val output = inputStream?.let { BufferedReader(InputStreamReader(it)).readText() } ?: ""
                val err = errorStream?.let { BufferedReader(InputStreamReader(it)).readText() } ?: ""

                val exitCode = process.javaClass.getMethod("waitFor").invoke(process) as? Int
                Log.d(TAG, "Finished command: '$command' with exit code: $exitCode | out: $output | err: $err")
            } else {
                Log.e(TAG, "newProcess returned null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute Shizuku command: $command", e)
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
        private const val TAG = "SmartRAMOptimizer"
        const val ACTION_START = "com.example.smartramoptimizer.START"
        const val ACTION_STOP = "com.example.smartramoptimizer.STOP"
        private const val CHANNEL_ID = "focus_monitor"
        private const val NOTIFICATION_ID = 73
    }
}
