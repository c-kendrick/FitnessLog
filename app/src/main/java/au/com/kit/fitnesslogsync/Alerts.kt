package au.com.kit.fitnesslogsync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import java.time.Instant

class Alerts(private val context: Context, private val store: Store) {
    private val manager = context.getSystemService(NotificationManager::class.java)
    init {
        manager.createNotificationChannel(NotificationChannel("issues", "Sync problems", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel("checkins", "Optional app check-ins", NotificationManager.IMPORTANCE_LOW))
    }
    fun available(): Boolean = manager.areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    fun issue(code: String, message: String, immediate: Boolean) {
        val now = Instant.now()
        val previous = store.get("issue_code")
        if (previous != code) {
            store.put("issue_code", code); store.put("issue_since", now.toString()); store.put("issue_notified", "")
        }
        val since = runCatching { Instant.parse(store.get("issue_since")) }.getOrDefault(now)
        if (!immediate && now.epochSecond - since.epochSecond < 6 * 3600) return
        if (store.get("issue_notified") == code || !available()) return
        notify(100, "issues", "Fitness sync needs attention", message)
        store.put("issue_notified", code)
    }
    fun recovered() {
        if (store.get("issue_notified").isNotEmpty() && available()) {
            notify(100, "issues", "Fitness sync recovered", "The latest sync completed without reported issues.")
        } else manager.cancel(100)
        store.put("issue_code", ""); store.put("issue_since", ""); store.put("issue_notified", "")
    }
    fun optionalCheckIn() {
        if (store.get("checkins") != "true" || !available()) return
        val lastOpen = runCatching { Instant.parse(store.get("last_open")) }.getOrElse { return }
        val now = Instant.now()
        val lastReminder = runCatching { Instant.parse(store.get("last_checkin")) }.getOrDefault(Instant.EPOCH)
        if (now.epochSecond - lastOpen.epochSecond >= 7 * 86400 && now.epochSecond - lastReminder.epochSecond >= 7 * 86400) {
            notify(101, "checkins", "Quick Fitness Log check-in", "Tap to open the app and refresh its connection.")
            store.put("last_checkin", now.toString())
        }
    }
    private fun notify(id: Int, channel: String, title: String, text: String) {
        val intent = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(id, NotificationCompat.Builder(context, channel).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(intent).setAutoCancel(true).build())
    }
}
