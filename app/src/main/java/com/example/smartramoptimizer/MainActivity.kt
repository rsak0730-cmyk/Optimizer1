package com.example.smartramoptimizer

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.provider.Settings
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("optimizer", Context.MODE_PRIVATE) }
    private lateinit var root: LinearLayout
    private lateinit var ramText: TextView
    private lateinit var storageText: TextView
    private lateinit var shizukuText: TextView
    private lateinit var usageText: TextView
    private lateinit var focusText: TextView
    private lateinit var quotaText: TextView
    private val selected = linkedSetOf<String>()
    private val protected = linkedSetOf(
        "com.android.systemui",
        "com.android.settings",
        "com.sec.android.app.launcher",
        "com.sec.android.app.samsungapps",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard"
    )
    private var maxAllowedApps = 1

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission handled */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected.addAll(prefs.getStringSet("selected", emptySet()) ?: emptySet())
        protected.addAll(prefs.getStringSet("protected", emptySet()) ?: emptySet())
        maxAllowedApps = prefs.getInt("max_allowed_apps", 1)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        buildUi()
        refreshStats()
        refreshStatuses()
    }

    override fun onResume() {
        super.onResume()
        if (::root.isInitialized) refreshStatuses()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
            setBackgroundColor(Color.rgb(16, 16, 25))
        }
        scroll.addView(root)
        setContentView(scroll)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val logo = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54)).apply {
                marginEnd = dp(14)
            }
            setImageResource(R.drawable.ic_ram_chip)
        }

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("SMART RAM OPTIMIZER", 20, Color.WHITE, true))
            addView(label("Strict Concurrency & Memory Focus", 13, 0xFFBEB9D2.toInt(), false))
        }

        header.addView(logo)
        header.addView(titleBox)
        root.addView(header)
        root.addView(space(18))

        // Device Stats Panel
        val stats = panel()
        ramText = label("RAM: loading…", 16, Color.WHITE, true)
        storageText = label("Storage: loading…", 15, Color.WHITE, false)
        stats.addView(ramText)
        stats.addView(space(6))
        stats.addView(storageText)
        stats.addView(space(8))
        stats.addView(button("Refresh Stats") { refreshStats() })
        root.addView(stats)

        root.addView(space(12))

        // App Limit Controls
        val limitPanel = panel()
        limitPanel.addView(label("CONCURRENT APP LIMIT", 17, Color.WHITE, true))
        quotaText = label("Max running apps allowed: $maxAllowedApps", 15, 0xFF00CEC9.toInt(), true)
        limitPanel.addView(quotaText)
        limitPanel.addView(space(6))

        val buttonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttonRow.addView(button("1 App (Strict)") { setLimit(1) })
        buttonRow.addView(spaceH(8))
        buttonRow.addView(button("2 Apps") { setLimit(2) })
        buttonRow.addView(spaceH(8))
        buttonRow.addView(button("3 Apps") { setLimit(3) })
        limitPanel.addView(buttonRow)
        root.addView(limitPanel)

        root.addView(space(12))

        // Permissions & Shizuku
        val access = panel()
        access.addView(label("PERMISSIONS & SERVICES", 17, Color.WHITE, true))
        shizukuText = label("Shizuku: checking…", 14, Color.WHITE, false)
        usageText = label("Usage Access: checking…", 14, Color.WHITE, false)
        access.addView(shizukuText)
        access.addView(usageText)
        access.addView(space(6))
        access.addView(button("Open Usage Access settings") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })
        access.addView(button("Refresh permission status") { refreshStatuses() })
        root.addView(access)

        root.addView(space(12))

        // Monitor Controls
        val focus = panel()
        focus.addView(label("FOCUS ENFORCER", 17, Color.WHITE, true))
        focusText = label("", 14, Color.WHITE, false)
        focus.addView(focusText)
        focus.addView(space(6))
        focus.addView(button("Start Focus Enforcer") { startFocusMonitor() })
        focus.addView(button("Stop Focus Enforcer") { stopFocusMonitor() })
        root.addView(focus)

        root.addView(space(16))
        root.addView(label("MANAGE APPS", 19, Color.WHITE, true))
        root.addView(label(
            "Checked apps are kept under strict concurrency limit. Uncheck or 'Protect' apps you never want killed.",
            13, 0xFFBEB9D2.toInt(), false
        ))
        root.addView(space(8))

        val selectAllRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        selectAllRow.addView(button("Select All Apps") { selectAllApps(true) })
        selectAllRow.addView(spaceH(8))
        selectAllRow.addView(button("Unselect All") { selectAllApps(false) })
        root.addView(selectAllRow)
        root.addView(space(8))

        loadApps()
    }

    private fun setLimit(limit: Int) {
        maxAllowedApps = limit
        prefs.edit().putInt("max_allowed_apps", limit).apply()
        quotaText.text = "Max running apps allowed: $maxAllowedApps"
        Toast.makeText(this, "Concurrency limit set to $limit app(s)", Toast.LENGTH_SHORT).show()
    }

    private fun selectAllApps(select: Boolean) {
        val apps = packageManager.getInstalledApplications(0)
            .filter { it.packageName != packageName && (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }

        if (select) {
            for (app in apps) {
                if (app.packageName !in protected) selected.add(app.packageName)
            }
        } else {
            selected.clear()
        }
        saveSets()
        loadApps()
    }

    private fun loadApps() {
        root.findViewWithTag<View>("app-list")?.let { root.removeView(it) }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "app-list"
        }
        val apps = packageManager.getInstalledApplications(0)
            .filter { it.packageName != packageName && (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .sortedBy { packageManager.getApplicationLabel(it).toString().lowercase() }

        for (info in apps) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setBackgroundColor(0xFF1B1B29.toInt())
            }
            val name = packageManager.getApplicationLabel(info).toString()
            val manage = CheckBox(this).apply {
                text = "$name\n${info.packageName}"
                textSize = 14f
                setTextColor(Color.WHITE)
                isChecked = selected.contains(info.packageName)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(info.packageName) else selected.remove(info.packageName)
                    saveSets()
                }
            }
            val protectBox = CheckBox(this).apply {
                text = "Protect this app (Never kill)"
                textSize = 12f
                setTextColor(0xFF00CEC9.toInt())
                isChecked = protected.contains(info.packageName)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        protected.add(info.packageName)
                        selected.remove(info.packageName)
                    } else protected.remove(info.packageName)
                    saveSets()
                    manage.isChecked = selected.contains(info.packageName)
                }
            }
            row.addView(manage)
            row.addView(protectBox)
            list.addView(row)
            list.addView(space(5))
        }
        root.addView(list)
    }

    private fun saveSets() {
        prefs.edit()
            .putStringSet("selected", selected.toSet())
            .putStringSet("protected", protected.toSet())
            .apply()
    }

    private fun startFocusMonitor() {
        if (!hasUsageAccess()) {
            Toast.makeText(this, "Grant Usage Access first.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, "Start Shizuku first, then refresh status.", Toast.LENGTH_LONG).show()
            refreshStatuses()
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            try {
                Shizuku.requestPermission(1001)
            } catch (_: Exception) {
                Toast.makeText(this, "Grant this app permission in Shizuku.", Toast.LENGTH_LONG).show()
            }
            return
        }
        val intent = Intent(this, FocusMonitorService::class.java).setAction(FocusMonitorService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
        focusText.text = "Focus Enforcer running (Quota: $maxAllowedApps app)."
    }

    private fun stopFocusMonitor() {
        stopService(Intent(this, FocusMonitorService::class.java))
        focusText.text = "Focus Enforcer stopped."
    }

    private fun refreshStatuses() {
        val running = try { Shizuku.pingBinder() } catch (_: Exception) { false }
        val granted = running && try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }
        shizukuText.text = when {
            !running -> "Shizuku: NOT RUNNING"
            granted -> "Shizuku: CONNECTED • permission granted"
            else -> "Shizuku: CONNECTED • permission required"
        }
        usageText.text = if (hasUsageAccess()) "Usage Access: GRANTED" else "Usage Access: NOT GRANTED"
    }

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun refreshStats() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memory = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memory)
            val used = (memory.totalMem - memory.availMem).coerceAtLeast(0)
            ramText.text = "RAM used: ${Formatter.formatFileSize(this, used)} / ${Formatter.formatFileSize(this, memory.totalMem)}\nAvailable: ${Formatter.formatFileSize(this, memory.availMem)}"
        } catch (_: Exception) {
            ramText.text = "RAM information unavailable"
        }
        try {
            val stat = StatFs(android.os.Environment.getDataDirectory().path)
            storageText.text = "Internal storage used: ${Formatter.formatFileSize(this, stat.totalBytes - stat.availableBytes)} / ${Formatter.formatFileSize(this, stat.totalBytes)}\nAvailable: ${Formatter.formatFileSize(this, stat.availableBytes)}"
        } catch (_: Exception) {
            storageText.text = "Storage information unavailable"
        }
    }

    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        setBackgroundColor(0xFF1B1B29.toInt())
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean) =
        TextView(this).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun space(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun spaceH(width: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(width), 1)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
