package au.com.kit.fitnesslogsync

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private lateinit var autoButton: Button
    private lateinit var autoStatus: TextView
    private lateinit var syncButton: Button
    private lateinit var syncStatus: TextView
    private lateinit var connectionStatus: TextView
    private lateinit var healthStatus: TextView
    private lateinit var samsungStatus: TextView
    private lateinit var notificationStatus: TextView
    private lateinit var importStatus: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var historyStatus: TextView
    private lateinit var otherStatus: TextView
    private var changingAutoSync = false
    private lateinit var progress: ProgressBar
    private var busy = false
    private val permissions = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        refresh(); runSync()
    }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh(); runSync() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(28))
        }
        fun text(value: String, size: Float = 15f) = TextView(this).apply {
            text = value; textSize = size
            setTextColor(0xFF35433D.toInt())
            setPadding(0, dp(4), 0, dp(4))
        }
        fun gap(height: Int) { layout.addView(Space(this), LinearLayout.LayoutParams(1, dp(height))) }
        fun section(label: String) {
            gap(26)
            layout.addView(text(label, 18f).apply { setTypeface(typeface, Typeface.BOLD) })
            gap(8)
        }
        fun button(label: String, click: () -> Unit): Button = Button(this).apply {
            text = label; isAllCaps = false; minHeight = dp(52)
            setOnClickListener { click() }
            layout.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        fun detail(): TextView = text("").apply {
            setPadding(dp(8), dp(2), dp(8), dp(10))
            layout.addView(this)
        }
        layout.addView(text("Fitness Log Sync", 28f).apply { setTypeface(typeface, Typeface.BOLD) })
        // Read the installed APK's version so this cannot drift from Android's version metadata.
        val installedVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "Unknown"
        layout.addView(text("Version $installedVersion", 14f))
        gap(22)
        autoButton = button("Enable auto sync") { toggleAutoSync() }.apply {
            backgroundTintList = ColorStateList.valueOf(0xFF256B5D.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, Typeface.BOLD)
            minHeight = dp(58)
        }
        autoStatus = detail()
        syncButton = button("Sync now / retry uploads") { runSync() }
        syncStatus = detail()
        progress = ProgressBar(this).apply { visibility = View.GONE }
        layout.addView(progress, LinearLayout.LayoutParams(dp(30), dp(30)).apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        })

        section("Connection and permissions")
        button("Connect Google Sheet") { configure() }
        connectionStatus = detail()
        button("Connect Samsung steps") { connectSamsungSteps() }
        samsungStatus = detail()
        button("Grant health access") { grantHealth() }
        healthStatus = detail()
        button("Allow issue notifications") {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
        notificationStatus = detail()

        section("History and settings")
        button("Import earlier dates") { backfill() }
        importStatus = detail()
        button("Battery settings") { batterySettings() }
        batteryStatus = detail()
        button("Recent sync history") { Store(this).use { message(it.history().ifEmpty { "No sync attempts yet." }) } }
        historyStatus = detail()
        button("Step count diagnostic") { showStepDiagnostic() }
        detail().text = "Read today and yesterday directly from Samsung Health."
        val checkins = CheckBox(this).apply {
            text = "Optional reminder if unopened for 7 days"
            isChecked = Store(this@MainActivity).use { it.get("checkins") == "true" }
        }
        layout.addView(checkins)
        val checkinStatus = detail().apply { text = if (checkins.isChecked) "Reminder on" else "Reminder off" }
        checkins.setOnCheckedChangeListener { _, checked ->
            Store(this@MainActivity).use { it.put("checkins", checked.toString()) }
            checkinStatus.text = if (checked) "Reminder on" else "Reminder off"
        }
        gap(18)
        otherStatus = text("").apply {
            setTextColor(0xFF8A2424.toInt())
            setTextIsSelectable(true)
            visibility = View.GONE
        }
        layout.addView(otherStatus)
        val scroll = ScrollView(this).apply { addView(layout) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)
    }
    override fun onResume() {
        super.onResume()
        Store(this).use { it.put("last_open", Instant.now().toString()) }
        Scheduling.ensureScheduled(this)
        refresh()
        // Opening a settings screen and coming back should not cause repeated uploads.
        val needsCheck = Store(this).use {
            it.get("successful_app_version") != APP_VERSION ||
                (it.get("enabled") == "true" && !SyncPolicy.recentlySuccessful(it.get("last_success"), Instant.now()))
        }
        if (Configuration(this).ready() && needsCheck) runSync()
    }
    private fun toggleAutoSync() {
        if (changingAutoSync) return
        val enabled = Store(this).use { it.get("enabled") == "true" }
        if (enabled) {
            Scheduling.disable(this)
            refresh()
            return
        }
        changingAutoSync = true
        autoButton.isEnabled = false
        lifecycleScope.launch {
            try {
                check(Configuration(this@MainActivity).ready()) { "Connect the Google Sheet first." }
                check(HealthConnectClient.getSdkStatus(this@MainActivity) == HealthConnectClient.SDK_AVAILABLE) { "Install or update Health Connect first." }
                val granted = HealthConnectClient.getOrCreate(this@MainActivity).permissionController.getGrantedPermissions()
                check(BACKGROUND in granted) { "Grant background health access first. If unavailable on this phone, sync when opening the app." }
                check(SamsungSteps(this@MainActivity).hasPermission()) { "Tap Connect Samsung steps first." }
                Scheduling.enable(this@MainActivity)
                runSync()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { message(e.message ?: "Could not enable syncing.")
            } finally {
                changingAutoSync = false
                refresh()
            }
        }
    }
    private fun refresh() {
        if (!::autoStatus.isInitialized) return
        lifecycleScope.launch {
            Store(this@MainActivity).use { store ->
                val ready = Configuration(this@MainActivity).ready()
                val healthAvailable = HealthConnectClient.getSdkStatus(this@MainActivity) == HealthConnectClient.SDK_AVAILABLE
                val access = if (healthAvailable)
                    runCatching { HealthConnectClient.getOrCreate(this@MainActivity).permissionController.getGrantedPermissions() }.getOrDefault(emptySet()) else emptySet()
                fun whenAt(key: String): String = runCatching {
                    Instant.parse(store.get(key)).atZone(SYDNEY).format(DateTimeFormatter.ofPattern("d MMM, h:mm a"))
                }.getOrDefault("Not yet")
                val enabled = store.get("enabled") == "true"
                autoButton.text = if (enabled) "Disable auto sync" else "Enable auto sync"
                autoButton.isEnabled = !changingAutoSync
                autoStatus.text = if (enabled) "Automatic syncing on\nBefore your check-ins\nNext planned sync: ${whenAt("next_sync_at")}" else "Automatic syncing off"
                syncButton.isEnabled = ready && !busy
                syncStatus.text = buildString {
                    if (busy) appendLine("Syncing…")
                    if (!ready) appendLine("Connect Google Sheet below to start syncing.")
                    if (store.get("today_steps_date") == LocalDate.now(SYDNEY).toString()) {
                        val stepState = store.get("today_steps_status")
                        appendLine("Today's steps: ${if (stepState == "value") store.get("today_steps") else stepState.replace('_', ' ')}")
                        appendLine("Checked Samsung Health: ${whenAt("checked_at_steps")}")
                    }
                    appendLine("Pending uploads: ${store.count()}")
                    append("Last complete sync: ${whenAt("last_success")}")
                }
                connectionStatus.text = if (ready)
                    "Google connection configured\nLast confirmed upload: ${whenAt("last_upload")}" else "Google connection not configured"
                samsungStatus.text = "Direct Samsung steps: ${store.get("status_steps", "connection needed").replace('_', ' ')}"
                healthStatus.text = if (!healthAvailable) "Health Connect unavailable" else buildString {
                    appendLine("Background health access: ${if (BACKGROUND in access) "allowed" else "needed"}")
                    for (category in DAILY.filter { it.key != "steps" } + MEASUREMENTS) {
                        val state = if (category.permission !in access) "access needed" else store.get("status_${category.key}", "ready")
                        appendLine("${category.label}: ${state.replace('_', ' ')}")
                    }
                }.trimEnd()
                notificationStatus.text = "Issue notifications ${if (Alerts(this@MainActivity, store).available()) "on" else "off"}"
                importStatus.text = buildString {
                    append("History access: ${if (HISTORY in access) "allowed" else "needed for older dates"}")
                    val pending = DAILY.mapNotNull { category ->
                        store.get("backfill_${category.key}").takeIf { it.isNotEmpty() }?.let { "${category.label} from $it" }
                    }
                    if (pending.isNotEmpty()) append("\nImport pending: ${pending.joinToString("; ")}")
                }
                val power = getSystemService(PowerManager::class.java)
                batteryStatus.text = "Android battery optimisation ${if (power.isIgnoringBatteryOptimizations(packageName)) "off" else "on"}"
                historyStatus.text = "Last sync attempt: ${whenAt("last_attempt")}"
                val other = listOf(
                    store.get("last_error").takeIf { it.isNotEmpty() }?.let { "Sync issue\n$it" },
                    store.get("history_gap").takeIf { it.isNotEmpty() }?.let { "History gap\n$it" }
                ).filterNotNull().joinToString("\n\n")
                otherStatus.text = other
                otherStatus.visibility = if (other.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }
    private fun runSync(start: LocalDate? = null) {
        if (busy || !Configuration(this).ready()) return
        busy = true; progress.visibility = View.VISIBLE; refresh()
        lifecycleScope.launch {
            try { SyncRepository(this@MainActivity).sync(true, start) }
            finally { busy = false; progress.visibility = View.GONE; refresh() }
        }
    }
    private fun showStepDiagnostic() {
        if (busy) { message("Wait for the current sync to finish first."); return }
        lifecycleScope.launch {
            busy = true; progress.visibility = View.VISIBLE; refresh()
            try {
                val result = SamsungSteps(this@MainActivity).diagnose(LocalDate.now(SYDNEY))
                Store(this@MainActivity).use { it.put("steps_diagnostic", result) }
                message(result)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { message(e.message ?: "Could not read the step diagnostic.")
            } finally { busy = false; progress.visibility = View.GONE; refresh() }
        }
    }
    private fun configure() {
        val config = Configuration(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 16, 28, 16) }
        val url = EditText(this).apply { hint = "Apps Script URL ending in /exec"; setText(config.endpoint); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI }
        val secret = EditText(this).apply { hint = "Connection key (blank keeps existing key)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        box.addView(url); box.addView(secret)
        val dialog = AlertDialog.Builder(this).setTitle("Connect Google Sheet").setView(box)
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    config.save(url.text.toString(), secret.text.toString().ifBlank { config.secret })
                    dialog.dismiss(); refresh(); runSync()
                } catch (e: Exception) { secret.error = e.message }
            }
        }
        dialog.show()
    }
    private fun connectSamsungSteps() {
        if (busy) { message("Wait for the current sync to finish first."); return }
        lifecycleScope.launch {
            busy = true; progress.visibility = View.VISIBLE; refresh()
            var granted = false
            try {
                granted = SamsungSteps(this@MainActivity).requestPermission(this@MainActivity)
                if (!granted) message("Samsung step access was not granted. Enable Developer mode for Data Read in Samsung Health, then try again. See START_HERE.md.")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                message("Samsung connection: ${e.message}\n\nFor this personal app, turn on Developer mode for Data Read in Samsung Health > Settings > About Samsung Health (tap the version 10 times). Then try Connect Samsung steps again.")
            } finally { busy = false; progress.visibility = View.GONE; refresh() }
            if (granted) runSync()
        }
    }
    private fun grantHealth() {
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            message("Health Connect is unavailable. On Android 14 or newer, check it in Settings. On older Android, install Health Connect from Google Play.")
            return
        }
        permissions.launch(HealthReader(HealthConnectClient.getOrCreate(this)).requestPermissions())
    }
    private fun backfill() {
        val input = EditText(this).apply { setText("2026-07-09"); hint = "Start date: yyyy-mm-dd" }
        val dialog = AlertDialog.Builder(this).setTitle("Import earlier dates").setMessage("Existing dates are updated safely. Older dates require history access.")
            .setView(input).setPositiveButton("Import", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val date = LocalDate.parse(input.text.toString().trim())
                    require(date >= LocalDate.parse("2026-07-09") && date <= LocalDate.now(SYDNEY)) { "Choose a date from 9 July 2026 through today." }
                    dialog.dismiss(); runSync(date)
                } catch (e: Exception) { input.error = e.message ?: "Use yyyy-mm-dd." }
            }
        }
        dialog.show()
    }
    private fun batterySettings() {
        val intent = Intent("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY")
            .setPackage("com.samsung.android.lool").putExtra("activity_type", 2)
        try { startActivity(intent) } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            message("Set battery use to Unrestricted. On Samsung, also find Background usage limits and add Fitness Log Sync to Never sleeping apps.")
        }
    }
    private fun message(text: String) { AlertDialog.Builder(this).setMessage(text).setPositiveButton("OK", null).show() }
}
