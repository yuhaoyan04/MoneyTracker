package com.mudasir.smartledger.util

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.google.gson.Gson
import com.mudasir.smartledger.BuildConfig
import com.mudasir.smartledger.capture.CheckoutPageParser
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Explicit, time-limited, local-only diagnostics. All events are constructed from an allowlist. */
object GuardDiagnosticLog {
    private const val PREFS = "guard_diagnostic_session"
    private const val MAX_MS = 30 * 60 * 1000L
    private const val MAX_QUEUE = 256
    private val executor = Executors.newSingleThreadExecutor()
    private val pending = AtomicInteger(0)
    private val dropped = AtomicInteger(0)
    private val gson = Gson()
    @Volatile private var context: Context? = null
    @Volatile private var active = false
    @Volatile private var startedAt = 0L
    @Volatile var lastError: String? = null
        private set

    private fun storage(c: Context) = RollingDiagnosticFile(c.noBackupFilesDir)

    @Synchronized fun initialize(c: Context) {
        if (context != null) return
        val app = c.applicationContext
        context = app
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        startedAt = prefs.getLong("started_at", 0)
        active = prefs.getBoolean("active", false) && startedAt > 0 &&
            System.currentTimeMillis() - startedAt in 0 until MAX_MS &&
            (storage(app).current.exists() || storage(app).previous.exists())
        if (!active) prefs.edit().putBoolean("active", false).apply()
        else enqueue("PROCESS_RESTART", "self", "", emptyMap(), force = true)
    }

    fun isRecording(): Boolean {
        if (active && System.currentTimeMillis() - startedAt !in 0 until MAX_MS) stop("TIME_LIMIT")
        return active
    }

    @Synchronized fun start(c: Context) {
        initialize(c)
        if (isRecording()) return
        val app = context ?: return
        lastError = null
        startedAt = System.currentTimeMillis()
        active = true
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("started_at", startedAt).putBoolean("active", true).apply()
        val guard = app.getSharedPreferences("guard_prefs", Context.MODE_PRIVATE)
        val mode = guard.getString("guard_mode", "overlay").takeIf { it in setOf("overlay", "notify") } ?: "other"
        val scope = guard.getString("guard_scope", "payment").takeIf { it in setOf("payment", "checkout", "app") } ?: "other"
        enqueue("SESSION_START", "self", "", mapOf(
            "version" to BuildConfig.VERSION_NAME,
            "api" to Build.VERSION.SDK_INT.toString(),
            "manufacturer" to safeDevice(Build.MANUFACTURER),
            "model" to safeDevice(Build.MODEL),
            "usage_access" to runCatching { PermissionHelper.hasUsageAccess(app) }.getOrDefault(false).toString(),
            "accessibility_enabled" to runCatching { PermissionHelper.isAccessibilityCaptureEnabled(app) }.getOrDefault(false).toString(),
            "accessibility_running" to AccessibilityCaptureService.isRunning.toString(),
            "overlay_permission" to runCatching { Settings.canDrawOverlays(app) }.getOrDefault(false).toString(),
            "notifications_enabled" to runCatching { androidx.core.app.NotificationManagerCompat.from(app).areNotificationsEnabled() }.getOrDefault(false).toString(),
            "battery_unrestricted" to runCatching {
                (app.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(app.packageName)
            }.getOrDefault(false).toString(),
            "guard_running" to PurchaseGuardService.isRunning.toString(),
            "guard_enabled" to guard.getBoolean("guard_enabled", false).toString(),
            "guard_mode" to mode,
            "guard_scope" to scope
        ), force = true)
    }

    @Synchronized fun stop(reason: String = "USER") {
        val app = context ?: return
        if (!active) return
        enqueue("SESSION_STOP", "self", "", mapOf("reason" to if (reason == "TIME_LIMIT") reason else "USER"), force = true)
        active = false
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("active", false).apply()
    }

    @Synchronized fun detection(pkg: String, cls: String, message: String) {
        if (!isRecording()) return
        val app = context ?: return
        val safePackage = GuardLogPolicy.safePackage(pkg, app.packageName)
        enqueue(GuardLogPolicy.status(message), safePackage,
            if (safePackage == "other") "" else GuardLogPolicy.safeClass(cls), GuardLogPolicy.evidence(message))
    }

    @Synchronized fun accessibilityEvent(pkg: String, cls: String, event: String, eventUptime: Long, role: String = "OTHER") {
        if (!isRecording() || !CheckoutPageParser.isSupported(pkg)) return
        if (event !in setOf("WINDOW_STATE", "WINDOWS_CHANGED", "CONTENT_CHANGED", "VIEW_CLICKED")) return
        val safeRole = role.takeIf { it in setOf("SUBMIT_ORDER", "PAY_ACTION", "GO_TO_PAY") } ?: "OTHER"
        val details = mutableMapOf("event_uptime_ms" to eventUptime.toString())
        if (event == "VIEW_CLICKED") details["role"] = safeRole
        enqueue("A11Y_$event", pkg, GuardLogPolicy.safeClass(cls), details)
    }

    @Synchronized fun lifecycle(kind: String) {
        if (!isRecording()) return
        if (kind !in setOf("GUARD_CREATED", "GUARD_DESTROYED", "A11Y_CONNECTED", "A11Y_INTERRUPTED", "A11Y_UNBOUND", "A11Y_DESTROYED")) return
        enqueue(kind, "self", "", emptyMap())
    }

    @Synchronized fun settingChanged(field: String, value: String) {
        if (!isRecording()) return
        val allowed = when (field) {
            "guard_enabled" -> value in setOf("true", "false")
            "guard_mode" -> value in setOf("overlay", "notify")
            "threshold" -> value == "updated"
            "cooldown" -> value in setOf("3", "5", "10", "15", "20")
            else -> false
        }
        if (allowed) enqueue("SETTING_CHANGED", "self", "", mapOf("setting" to field, "value" to value))
    }

    @Synchronized fun settingsResumed() {
        if (!isRecording()) return
        val app = context ?: return
        enqueue("SETTINGS_RESUMED", "self", "", mapOf(
            "guard_running" to PurchaseGuardService.isRunning.toString(),
            "accessibility_running" to AccessibilityCaptureService.isRunning.toString(),
            "usage_access" to runCatching { PermissionHelper.hasUsageAccess(app) }.getOrDefault(false).toString(),
            "accessibility_enabled" to runCatching { PermissionHelper.isAccessibilityCaptureEnabled(app) }.getOrDefault(false).toString(),
            "overlay_permission" to runCatching { Settings.canDrawOverlays(app) }.getOrDefault(false).toString()
        ))
    }

    @Synchronized fun failure(error: Throwable) {
        if (!isRecording()) return
        val names = generateSequence(error) { it.cause }.take(5)
            .map { GuardLogPolicy.safeClass(it.javaClass.name) }.filter { it.isNotEmpty() }.toList()
        val frames = error.stackTrace.asSequence().filter { it.className.startsWith("com.mudasir.smartledger") }
            .take(8).mapNotNull { frame ->
                val cls = GuardLogPolicy.safeClass(frame.className)
                val method = GuardLogPolicy.safeClass(frame.methodName)
                if (cls.isBlank() || method.isBlank()) null else "$cls.$method:${frame.lineNumber}"
            }.toList()
        enqueue("ERROR", "self", "", mapOf("types" to names.joinToString("|"), "frames" to frames.joinToString("|")))
    }

    private fun safeDevice(value: String?): String = value.orEmpty().filter {
        it.isLetterOrDigit() || it in " -_."
    }.take(80)

    private fun enqueue(kind: String, pkg: String, cls: String, data: Map<String, String>, force: Boolean = false) {
        val app = context ?: return
        if (pending.incrementAndGet() > MAX_QUEUE && !force) { pending.decrementAndGet(); dropped.incrementAndGet(); return }
        val wall = System.currentTimeMillis()
        val uptime = SystemClock.elapsedRealtime()
        executor.execute {
            try {
                val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(Date(wall))
                val event = linkedMapOf<String, Any>("time_utc" to timestamp, "elapsed_ms" to uptime,
                    "event" to kind, "package" to pkg, "class" to cls)
                if (data.isNotEmpty()) event["details"] = data
                val skipped = dropped.getAndSet(0)
                if (skipped > 0) event["dropped_events"] = skipped
                storage(app).append(gson.toJson(event))
            } catch (error: Exception) {
                lastError = error.javaClass.simpleName
                active = false
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("active", false).apply()
            } finally { pending.decrementAndGet() }
        }
    }

    /** Waits for queued events before exporting; null streams and failed writes propagate. */
    suspend fun export(c: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
        initialize(c)
        executor.submit<Long> {
            val app = context ?: error("not initialized")
            val store = storage(app)
            check(listOf(store.previous, store.current).any { it.exists() && it.length() > 0 }) { "no diagnostic records" }
            val stream = app.contentResolver.openOutputStream(uri) ?: error("cannot open output")
            stream.use { out -> store.copyTo(out) }
        }.get()
    }

    suspend fun clear(c: Context) = withContext(Dispatchers.IO) {
        initialize(c)
        stop()
        executor.submit {
            val app = context ?: return@submit
            storage(app).clear()
        }.get()
    }
}
