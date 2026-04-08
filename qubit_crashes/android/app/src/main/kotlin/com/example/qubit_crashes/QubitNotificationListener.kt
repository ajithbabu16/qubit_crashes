package com.example.qubit_crashes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class QubitNotificationListener : NotificationListenerService() {

    companion object {
        const val PREFS_NAME         = "qubit_prefs"
        const val KEY_TARGET_PKG     = "target_pkg"
        const val KEY_TARGET_LABEL   = "target_label"
        const val KEY_CRASH_TS       = "last_crash_ts"
        const val KEY_CRASH_TYPE     = "last_crash_type"
        const val KEY_CRASH_MSG      = "last_crash_msg"
        const val KEY_DEBUG_LOG      = "debug_log"

        const val ACTION_CRASH       = "com.qubit.CRASH_DETECTED"
        const val EXTRA_TYPE         = "type"
        const val EXTRA_MSG          = "msg"
        const val EXTRA_SOURCE       = "source"

        const val CRASH_CHANNEL_ID   = "qubit_crash_alerts"
        const val CRASH_NOTIF_ID     = 2001

        val CRASH_KEYWORDS = listOf(
            "stopped", "responding", "closed", "crash", "error", "failed", 
            "exception", "finish", "compatible", "compatibility", "details"
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == "com.example.qubit_crashes") return

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val targetPkg = prefs.getString(KEY_TARGET_PKG, null) ?: return
        val targetLabel = prefs.getString(KEY_TARGET_LABEL, "") ?: ""

        val extras  = sbn.notification?.extras ?: return
        val title   = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text    = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val big     = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val combined = "$title $text $big".lowercase()

        val isXiaomiAssistant = sbn.packageName == "com.miui.thirdappassistant"
        
        // ── MATCHING ──────────────────────────────────────────────────────────
        val matchesApp = combined.contains(targetPkg.lowercase()) || 
                        (targetLabel.isNotEmpty() && combined.contains(targetLabel.lowercase())) ||
                        sbn.packageName == targetPkg

        if (matchesApp) {
            // If it's a known crash keyword OR if it's from Xiaomi's assistant (which only notifies about issues)
            val hasKeyword = CRASH_KEYWORDS.any { combined.contains(it) }
            
            if (hasKeyword || isXiaomiAssistant) {
                val type = if (combined.contains("responding")) "ANR" else "CRASH"
                val msg = "$text $big".trim().ifBlank { title }
                val now = System.currentTimeMillis()

                // Store for Activity to read
                prefs.edit()
                    .putLong(KEY_CRASH_TS, now)
                    .putString(KEY_CRASH_TYPE, type)
                    .putString(KEY_CRASH_MSG, msg)
                    .apply()

                showQubitNotification(type, msg, targetPkg)

                sendBroadcast(Intent(ACTION_CRASH).apply {
                    setPackage("com.example.qubit_crashes")
                    putExtra(EXTRA_TYPE, type)
                    putExtra(EXTRA_MSG, msg)
                    putExtra(EXTRA_SOURCE, sbn.packageName)
                })
                logDebug("!!! MATCHED CRASH !!! Source: ${sbn.packageName}")
            } else {
                logDebug("Ignored (No keyword): $combined")
            }
        }
    }

    private fun logDebug(msg: String) {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val current = prefs.getString(KEY_DEBUG_LOG, "") ?: ""
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val newLog = "[$time] $msg\n$current".take(5000)
        prefs.edit().putString(KEY_DEBUG_LOG, newLog).apply()
    }

    private fun showQubitNotification(type: String, msg: String, pkg: String) {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(NotificationChannel(CRASH_CHANNEL_ID, "Qubit Alerts", NotificationManager.IMPORTANCE_HIGH))
        }

        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CRASH_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("🔴 Qubit: $type Detected")
                .setContentText(pkg)
                .setStyle(Notification.BigTextStyle().bigText("Package: $pkg\n\n$msg"))
                .setAutoCancel(true).setContentIntent(pi).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this).setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("🔴 Qubit: $type Detected").setContentText(pkg)
                .setPriority(Notification.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(pi).build()
        }
        mgr.notify(CRASH_NOTIF_ID, notif)
    }
}
