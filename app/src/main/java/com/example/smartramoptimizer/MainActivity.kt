package com.example.smartramoptimizer

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Bundle
import android.os.Process
import android.os.StatFs
import android.provider.Settings
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("optimizer", Context.MODE_PRIVATE) }
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var root: LinearLayout
    private lateinit var ramText: TextView
    private lateinit var storageText: TextView
    private lateinit var shizukuText: TextView
    private lateinit var focusText: TextView
    private var focusEnabled = false
    private val selectedPackages = linkedSetOf<String>()
    private val protectedPackages = linkedSetOf(
        "com.android.systemui",
        "com.android.settings",
        "com.sec.android.app.launcher",
        "com.google.android.inputmethod.latin"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedPackages.addAll(prefs.getStringSet("selected", emptySet()) ?: emptySet())
        protectedPackages.addAll(prefs.getStringSet("protected", emptySet()) ?: emptySet())
        focusEnabled = prefs.getBoolean("focus", false)
        buildUi()
        updateStats()
        updateShizukuStatus()
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
            setBackgroundColor(Color.rgb(16, 16, 25))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(label("SMART RAM OPTIMIZER", 25, Color.WHITE, true))
        root.addView(label("Shizuku-assisted Smart Focus", 14, Color.rgb(174, 169, 198), false))
        root.addView(space(18))

        val card = panel()
        ramText = label("RAM: reading…", 17, Color.WHITE, true)
        storageText = label("Storage: reading…", 16, Color.WHITE, false)
        card.addView(ramText)
        card.addView(space(8))
        card.addView(storageText)
        root.addView(card)

        root.addView(space(12))
        val shizukuCard = panel()
        shizukuText = label("Shizuku: checking…", 16, Color.WHITE, true)
        shizukuCard.addView(shizukuText)
        shizukuCard.addView(space(8))
        shizukuCard.addView(button("Refresh Shizuku status") { updateShizukuStatus() })
        shizukuCard.addView(button("Open Usage Access settings") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        })
        root.addView(shizukuCard)

        root.addView(space(12))
        val focusCard = panel()
        focusText = label("", 16, Color.WHITE, true)
        focusCard.addView(focusText)
        focusCard.addView(space(8))
        focusCard.addView(button(if (focusEnabled) "Turn Smart Focus OFF" else "Turn Smart Focus ON") {
            focusEnabled = !focusEnabled
            prefs.edit().putBoolean("focus", focusEnabled).apply()
            focusText.text = if (focusEnabled) {
                "Smart Focus is ON. This starter monitors status only; it will not silently kill apps."
            } else "Smart Focus is OFF."
            rebuildFocusButton(focusCard)
        })
        focusCard.addView(label(
            "Smart Focus does not freeze RAM directly. Android controls cached processes; use the app list below for explicit, confirmed force-stop actions.",
            13, Color.rgb(190, 185, 210), false
        ))
        root.addView(focusCard)
        focusText.text = if (focusEnabled) "Smart Focus is ON" else "Smart Focus is OFF"

        root.addView(space(18))
        root.addView(label("APP MANAGER", 20, Color.WHITE, true))
        root.addView(label("Select only apps you are comfortable stopping. Force-stop may disable notifications and background work until the app is opened again.", 13, Color.rgb(190, 185, 210), false))
        root.addView(space(8))
        root.addView(button("Reload app list") { loadAppList() })
        root.addView(button("Force-stop selected apps (Shizuku)") { confirmForceStop() })
        root.addView(space(8))
        loadAppList()
    }

    private fun rebuildFocusButton(card: LinearLayout) {
        // The focus status text is updated immediately; the screen can be reopened to refresh button label.
        focusText.text = if (focusEnabled) "Smart Focus is ON" else "Smart Focus is OFF"
    }

    private fun loadAppList() {
        val old = root.findViewWithTag<LinearLayout>("app-list")
        if (old != null) root.removeView(old)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = "app-list"
        }
        val apps = packageManager.getInstalledApplications(0)
            .filter { it.packageName != packageName && (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .sortedBy { packageManager.getApplicationLabel(it).toString().lowercase() }

        apps.forEach { info ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setBackgroundColor(Color.rgb(27, 27, 41))
            }
            val appName = packageManager.getApplicationLabel(info).toString()
            val check = CheckBox(this).apply {
                text = "$appName\n${info.packageName}"
                textSize = 14f
                setTextColor(Color.WHITE)
                isChecked = selectedPackages.contains(info.packageName)
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.rgb(124, 77, 255))
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedPackages.add(info.packageName) else selectedPackages.remove(info.packageName)
                    prefs.edit().putStringSet("selected", selectedPackages.toSet()).apply()
                }
            }
            row.addView(check)
            val protect = CheckBox(this).apply {
                text = "Protect this app from optimizer actions"
                textSize = 12f
                setTextColor(Color.rgb(190, 185, 210))
                isChecked = protectedPackages.contains(info.packageName)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) protectedPackages.add(info.packageName) else protectedPackages.remove(info.packageName)
                    prefs.edit().putStringSet("protected", protectedPackages.toSet()).apply()
                }
            }
            row.addView(protect)
            list.addView(row)
            list.addView(space(5))
        }
        root.addView(list)
    }

    private fun confirmForceStop() {
        val targets = selectedPackages.filter { it !in protectedPackages && it != packageName }
        if (targets.isEmpty()) {
            Toast.makeText(this, "Select at least one unprotected app first.", Toast.LENGTH_LONG).show()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Force-stop ${targets.size} app(s)?")
            .setMessage("This can stop notifications, music, syncing and background work. Android may restrict this operation depending on your Shizuku mode/version. Protected apps will be skipped.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Force-stop") { _, _ -> runForceStop(targets) }
            .show()
    }

    private fun runForceStop(targets: List<String>) {
        if (!Shizuku.pingBinder()) {
            Toast.makeText(this, "Shizuku is not running. Start Shizuku and try again.", Toast.LENGTH_LONG).show()
            updateShizukuStatus()
            return
        }
        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                Shizuku.requestPermission(1001)
            } catch (e: Exception) {
                Toast.makeText(this, "Open Shizuku and grant this app permission.", Toast.LENGTH_LONG).show()
            }
            return
        }
        executor.execute {
            val results = mutableListOf<String>()
            for (pkg in targets) {
                try {
                    val process = Shizuku.newProcess(arrayOf("sh", "-c", "am force-stop ${shellQuote(pkg)}"), null, null)
                    val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
                    val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
                    val code = process.waitFor()
                    results.add("$pkg: " + if (code == 0) "command sent" else "failed ($code) ${stderr.ifBlank { stdout }}")
                } catch (e: Exception) {
                    results.add("$pkg: failed (${e.message ?: "unknown error"})")
                }
            }
            runOnUiThread {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Optimization result")
                    .setMessage(results.joinToString("\n"))
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun updateStats() {
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            val mem = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mem)
            val total = mem.totalMem
            val available = mem.availMem
            val used = (total - available).coerceAtLeast(0)
            ramText.text = "RAM used: ${Formatter.formatFileSize(this, used)} / ${Formatter.formatFileSize(this, total)}\nAvailable: ${Formatter.formatFileSize(this, available)}"
        } catch (_: Exception) {
            ramText.text = "RAM information unavailable"
        }
        try {
            val stat = StatFs(filesDir.absolutePath)
            val total = stat.totalBytes
            val free = stat.availableBytes
            storageText.text = "Storage used: ${Formatter.formatFileSize(this, total - free)} / ${Formatter.formatFileSize(this, total)}\nAvailable: ${Formatter.formatFileSize(this, free)}"
        } catch (_: Exception) {
            storageText.text = "Storage information unavailable"
        }
    }

    private fun updateShizukuStatus() {
        val running = try { Shizuku.pingBinder() } catch (_: Exception) { false }
        val granted = running && try {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }
        shizukuText.text = when {
            !running -> "Shizuku: NOT RUNNING\nStart Shizuku separately, then refresh."
            granted -> "Shizuku: CONNECTED • Permission granted"
            else -> "Shizuku: CONNECTED • Permission not granted"
        }
    }

    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        setBackgroundColor(Color.rgb(27, 27, 41))
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean): TextView =
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

    private fun space(heightDp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(heightDp))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
