// Posts a scheduled local notification when its AlarmManager broadcast fires.
//
// The alarm intent carries the notification's content (title/body) and its
// custom data bag. The posted notification's content intent relaunches the
// app's launcher activity with those data keys as extras plus the
// LOCAL_TAP_MARKER, so `readTapIntent` in the plugin routes the tap through
// the same `notificationTapped` event as push taps — one pipeline, warm or
// cold start.

package app.tauri.pushnotifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Intent extra marking a locally-posted notification's tap intent. */
internal const val LOCAL_TAP_MARKER = "app.tauri.pushnotifications.local"

/** Channel local reminders post to (created lazily, user-visible name). */
internal const val LOCAL_CHANNEL_ID = "reminders"

internal const val EXTRA_ID = "app.tauri.pushnotifications.id"
internal const val EXTRA_TITLE = "app.tauri.pushnotifications.title"
internal const val EXTRA_BODY = "app.tauri.pushnotifications.body"
internal const val EXTRA_DATA = "app.tauri.pushnotifications.data"

/** SharedPreferences ledger of scheduled-but-not-yet-fired ids — Android has
 *  no API to query AlarmManager, so `getPendingLocal` reads this. */
internal const val LEDGER_PREFS = "tauri_push_notifications_local"
internal const val LEDGER_KEY = "pending_ids"

internal fun ensureLocalChannel(context: Context) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (manager.getNotificationChannel(LOCAL_CHANNEL_ID) != null) return
    manager.createNotificationChannel(
        NotificationChannel(
            LOCAL_CHANNEL_ID,
            "Reminders",
            NotificationManager.IMPORTANCE_HIGH,
        )
    )
}

internal fun ledgerUpdate(context: Context, mutate: (MutableSet<String>) -> Unit) {
    val prefs = context.getSharedPreferences(LEDGER_PREFS, Context.MODE_PRIVATE)
    val ids = prefs.getStringSet(LEDGER_KEY, emptySet())!!.toMutableSet()
    mutate(ids)
    prefs.edit().putStringSet(LEDGER_KEY, ids).apply()
}

class LocalNotificationPublisher : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, 0)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val body = intent.getStringExtra(EXTRA_BODY)
        ensureLocalChannel(context)

        // Tap → launcher activity with the data bag + marker; readTapIntent
        // picks it up (cold start via `load`, warm via `onNewIntent`).
        val launch =
            context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        launch.putExtra(LOCAL_TAP_MARKER, "1")
        @Suppress("UNCHECKED_CAST")
        val data = intent.getSerializableExtra(EXTRA_DATA) as? HashMap<String, String>
        data?.forEach { (key, value) -> launch.putExtra(key, value) }
        val contentIntent =
            PendingIntent.getActivity(
                context,
                id,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val notification =
            android.app.Notification.Builder(context, LOCAL_CHANNEL_ID)
                .setSmallIcon(context.applicationInfo.icon)
                .setContentTitle(title)
                .apply { body?.let { setContentText(it) } }
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .build()
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(id, notification)
        ledgerUpdate(context) { it.remove(id.toString()) }
    }
}
