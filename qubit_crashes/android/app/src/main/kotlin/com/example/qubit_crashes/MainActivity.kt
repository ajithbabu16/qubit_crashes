package com.example.qubit_crashes

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : FlutterActivity() {

    companion object {
        const val METHOD_CHANNEL   = "com.qubit/usage_stats"
        const val EVENT_CHANNEL    = "com.qubit/crash_events"
        const val CRASH_CHANNEL_ID = "qubit_crash_alerts"
        const val FG_CHANNEL_ID    = "qubit_foreground"
        const val CRASH_NOTIF_ID   = 1001
    }

    private var pollingJob: Job? = null
    private var eventSink: EventChannel.EventSink? = null
    private val isPolling = AtomicBoolean(false)
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private lateinit var notifManager: NotificationManager

    // Dedup: don't show same crash type twice within 8s
    private val lastFiredAt = mutableMapOf<String, Long>()

    // Current target
    private var currentTarget = ""
    private var targetLabel   = ""

    // Track last prefs crash timestamp to avoid replaying
    private var lastReadPrefTs = 0L

    // ── Broadcast receiver for live in-app display ────────────────────────────
    // QubitNotificationListener sends this when it detects a crash.
    // This is OPTIONAL — notifications are shown by the service regardless.
    private val crashReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != QubitNotificationListener.ACTION_CRASH) return
            val type = intent.getStringExtra(QubitNotificationListener.EXTRA_TYPE) ?: "CRASH"
            val msg  = intent.getStringExtra(QubitNotificationListener.EXTRA_MSG)  ?: ""
            pushToFlutter(type, msg)
        }
    }

    // ── Flutter engine ────────────────────────────────────────────────────────
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        notifManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannels()

        // Listen for live crash broadcasts from QubitNotificationListener
        // MUST specify RECEIVER_NOT_EXPORTED on Android 13+ (API 33) or app crashes on launch
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                crashReceiver,
                IntentFilter(QubitNotificationListener.ACTION_CRASH),
                RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(crashReceiver, IntentFilter(QubitNotificationListener.ACTION_CRASH))
        }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "hasPermission" ->
                        result.success(hasUsagePermission())

                    "openPermissionSettings" -> {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        result.success(null)
                    }
                    "hasNotificationPerm" -> {
                        val s = Settings.Secure.getString(
                            contentResolver, "enabled_notification_listeners")
                        result.success(s?.contains(packageName) == true)
                    }
                    "openNotificationPerm" -> {
                        startActivity(
                            Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                        result.success(null)
                    }
                    "isIgnoringBattery" -> {
                        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                        result.success(pm.isIgnoringBatteryOptimizations(packageName))
                    }
                    "openBatterySettings" -> {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(intent)
                        result.success(null)
                    }
                    "openXiaomiAutostart" -> {
                        try {
                            val intent = Intent()
                            intent.setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                            startActivity(intent)
                            result.success(true)
                        } catch (e: Exception) {
                            result.success(false)
                        }
                    }
                    "openXiaomiDisplayPopups" -> {
                        try {
                            val intent = Intent("miui.intent.action.APP_PERM_EDITOR")
                            intent.setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                            intent.putExtra("extra_pkgname", packageName)
                            startActivity(intent)
                            result.success(true)
                        } catch (e: Exception) {
                            result.success(false)
                        }
                    }
                    "getActiveTarget" -> {
                        val prefs = getSharedPreferences(QubitNotificationListener.PREFS_NAME, MODE_PRIVATE)
                        result.success(prefs.getString(QubitNotificationListener.KEY_TARGET_PKG, null))
                    }
                    "getDebugLog" -> {
                        val prefs = getSharedPreferences(QubitNotificationListener.PREFS_NAME, MODE_PRIVATE)
                        result.success(prefs.getString(QubitNotificationListener.KEY_DEBUG_LOG, "No logs yet"))
                    }
                    "enterPip" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val params = android.app.PictureInPictureParams.Builder().build()
                            enterPictureInPictureMode(params)
                            result.success(true)
                        } else {
                            result.success(false)
                        }
                    }
                    "startWatching" -> {
                        val pkg = call.argument<String>("package") ?: ""
                        startWatching(pkg)
                        result.success(null)
                    }
                    "stopWatching" -> {
                        stopWatching()
                        result.success(null)
                    }
                    else -> result.notImplemented()
                }
            }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(a: Any?, sink: EventChannel.EventSink?) { eventSink = sink }
                override fun onCancel(a: Any?) { eventSink = null }
            })
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration?) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        MethodChannel(flutterEngine!!.dartExecutor.binaryMessenger, METHOD_CHANNEL)
            .invokeMethod("onPipChanged", isInPictureInPictureMode)
    }

    override fun onDestroy() {
        try { unregisterReceiver(crashReceiver) } catch (_: Exception) {}
        // DO NOT call stopWatching() here! 
        // We want the NotificationListener to keep tracking even if the UI is killed.
        super.onDestroy()
    }

    // ── Channels ──────────────────────────────────────────────────────────────
    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifManager.createNotificationChannel(
                NotificationChannel(CRASH_CHANNEL_ID, "Qubit Crash Alerts",
                    NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true); setBypassDnd(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
            notifManager.createNotificationChannel(
                NotificationChannel(FG_CHANNEL_ID, "Qubit Tracking",
                    NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    // ── Permission ────────────────────────────────────────────────────────────
    private fun hasUsagePermission(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    }

    // ── Push crash to Flutter in-app display ──────────────────────────────────
    private fun pushToFlutter(type: String, shortMsg: String,
                              longMsg: String = "", stackTrace: String = "",
                              processName: String = "", pid: String = "") {
        val now  = System.currentTimeMillis()
        val last = lastFiredAt[type] ?: 0L
        if (now - last < 8_000) return
        lastFiredAt[type] = now

        val payload = mapOf(
            "type"        to type,
            "package"     to currentTarget,
            "time"        to sdf.format(Date()),
            "shortMsg"    to shortMsg,
            "longMsg"     to longMsg,
            "stackTrace"  to stackTrace,
            "processName" to processName.ifBlank { currentTarget },
            "pid"         to pid
        )
        CoroutineScope(Dispatchers.Main).launch {
            eventSink?.success(payload)
        }
    }

    // ── START WATCHING ────────────────────────────────────────────────────────
    private fun startWatching(targetPackage: String) {
        if (isPolling.getAndSet(true)) return

        currentTarget = targetPackage
        lastFiredAt.clear()

        // resolve app label
        targetLabel = try {
            val ai = packageManager.getApplicationInfo(targetPackage, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) { targetPackage.substringAfterLast('.') }

        // ── Write target to SharedPreferences so QubitNotificationListener ────
        // can read it even when this Activity is dead
        val prefs = getSharedPreferences(QubitNotificationListener.PREFS_NAME, MODE_PRIVATE)
        prefs.edit()
            .putString(QubitNotificationListener.KEY_TARGET_PKG, targetPackage)
            .putString(QubitNotificationListener.KEY_TARGET_LABEL, targetLabel)
            .putLong(QubitNotificationListener.KEY_CRASH_TS, System.currentTimeMillis()) // seed
            .apply()
        lastReadPrefTs = prefs.getLong(QubitNotificationListener.KEY_CRASH_TS, 0L)

        // ── Foreground service (keeps Qubit alive in background) ────────────
        showForegroundNotification(targetPackage)

        // ── Start UsageStats backup polling ──────────────────────────────────
        startUsageStatsPolling()
    }

    private fun showForegroundNotification(pkg: String) {
        // This is just a status bar notification; the actual foreground service
        // is managed by flutter_local_notifications plugin
    }

    // ── UsageStats polling (backup detection for non-MIUI devices) ────────────
    private fun startUsageStatsPolling() {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val launcherPkg = try {
            packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName ?: ""
        } catch (_: Exception) { "" }

        pollingJob = CoroutineScope(Dispatchers.IO).launch {
            var inFg            = false
            var confirmedAlive  = false
            var lastBgTs        = 0L
            var watchTicks      = 0
            var lastQueryTime   = System.currentTimeMillis()

            while (isPolling.get()) {

                // ── A: OS error state (works on stock Android, often blocked on MIUI) ──
                try {
                    val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    val errors = am.processesInErrorState
                    errors?.forEach { e ->
                        if (e.processName == currentTarget ||
                            e.processName.startsWith("$currentTarget:")) {
                            val type = if (e.condition ==
                                ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING)
                                "ANR" else "CRASH"
                            pushToFlutter(type,
                                shortMsg    = e.shortMsg ?: "App crashed",
                                longMsg     = e.longMsg ?: "",
                                stackTrace  = e.stackTrace ?: "",
                                processName = e.processName,
                                pid         = e.pid.toString()
                            )
                        }
                    }
                } catch (_: Exception) {}

                // ── B: Check SharedPreferences for crash written by NotifListener ──
                // (Acts as a backup in case the broadcast was lost)
                try {
                    val prefs = getSharedPreferences(
                        QubitNotificationListener.PREFS_NAME, MODE_PRIVATE)
                    val ts  = prefs.getLong(QubitNotificationListener.KEY_CRASH_TS, 0L)
                    if (ts > lastReadPrefTs) {
                        val type = prefs.getString(QubitNotificationListener.KEY_CRASH_TYPE, "CRASH") ?: "CRASH"
                        val msg  = prefs.getString(QubitNotificationListener.KEY_CRASH_MSG, "") ?: ""
                        if (msg.isNotEmpty()) {
                            lastReadPrefTs = ts
                            pushToFlutter(type, msg, longMsg = "Detected via Notification Intercept")
                        }
                    }
                } catch (_: Exception) {}

                // ── C: UsageEvents lifecycle gap (catches crashes missed above) ──
                val now    = System.currentTimeMillis()
                val events = usm.queryEvents(lastQueryTime - 500, now)
                lastQueryTime = now
                val ev = UsageEvents.Event()
                var movedBg = false; var movedFg = false; var otherFg = false

                while (events.hasNextEvent()) {
                    events.getNextEvent(ev)
                    val isFg = ev.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                    val isBg = ev.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND
                    when {
                        ev.packageName == currentTarget && isFg -> {
                            inFg = true; movedFg = true; confirmedAlive = true; watchTicks = 0
                        }
                        ev.packageName == currentTarget && isBg -> {
                            movedBg = true; lastBgTs = ev.timeStamp
                        }
                        ev.packageName != currentTarget && isFg -> otherFg = true
                    }
                }

                if (inFg && movedBg && !movedFg) {
                    inFg = false
                    watchTicks = if (otherFg) 0 else 10  // watch for 5 seconds
                    if (otherFg) confirmedAlive = false
                }

                if (watchTicks > 0) {
                    watchTicks--
                    val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST,
                        now - 15_000, now)
                    val lastUsed = stats?.firstOrNull {
                        it.packageName == currentTarget }?.lastTimeUsed ?: 0L
                    if (confirmedAlive && (lastUsed == 0L || lastUsed <= lastBgTs)) {
                        pushToFlutter("CRASH", "App closed unexpectedly",
                            longMsg = "Process vanished after going background with no user navigation.",
                            processName = currentTarget)
                        watchTicks = 0; confirmedAlive = false
                    }
                    if (watchTicks == 0) confirmedAlive = false
                }

                delay(500)
            }
        }
    }

    // ── STOP WATCHING ─────────────────────────────────────────────────────────
    private fun stopWatching() {
        isPolling.set(false)
        pollingJob?.cancel()
        pollingJob    = null
        currentTarget = ""
        targetLabel   = ""
        lastFiredAt.clear()

        // Clear the target from SharedPreferences so QubitNotificationListener stops
        try {
            getSharedPreferences(QubitNotificationListener.PREFS_NAME, MODE_PRIVATE)
                .edit().remove(QubitNotificationListener.KEY_TARGET_PKG).apply()
        } catch (_: Exception) {}
    }
}
