// FCM push registration + notification events.
//
// Wire contract mirrors the iOS implementation exactly (payloads match
// src/models.rs / guest-js):
//
//   - `registerForPush` resolves the FCM registration token. The CONSUMER
//     app must be Firebase-configured (google-services.json + the
//     com.google.gms.google-services Gradle plugin) — without it the token
//     fetch rejects at runtime, never at build time.
//   - Foreground deliveries (both data-only and notification messages while
//     the app is in front) emit `notificationReceived`.
//   - Taps emit `notificationTapped`: a background notification-message tap
//     relaunches/resumes the activity with the push's data keys as intent
//     extras, read from the cold-start intent in `load` and from
//     `onNewIntent` after.
//   - Events fired before a JS listener exists are queued and flushed by
//     `startNotificationEvents` (the guest bindings call it right after the
//     first listener registers) — same drain-then-arm contract as iOS.
//
// All mutable state is confined to the main thread (`handler.post`), so the
// plugin needs no further synchronization.

package app.tauri.pushnotifications

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import app.tauri.PermissionState
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.Permission
import app.tauri.annotation.PermissionCallback
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.RemoteMessage

private const val PERMISSION_ALIAS = "postNotification"

// Local-notification command payloads (mirror src/models.rs / guest-js).

@InvokeArg
class ScheduleLocalArgs {
    var id: Int = 0
    lateinit var title: String
    var body: String? = null
    var atMs: Double = 0.0
    var data: Map<String, String>? = null
}

@InvokeArg
class CancelLocalArgs {
    var ids: List<Int> = emptyList()
}

@TauriPlugin(
    permissions = [
        Permission(strings = [Manifest.permission.POST_NOTIFICATIONS], alias = PERMISSION_ALIAS)
    ]
)
class PushNotificationsPlugin(private val activity: Activity) : Plugin(activity) {

    companion object {
        // The FCM service (a system-instantiated component) delivers through
        // here; a Tauri app registers exactly one plugin instance. Messages
        // arriving before the plugin loads are queued statically.
        @Volatile internal var instance: PushNotificationsPlugin? = null
        internal val earlyMessages = ArrayDeque<RemoteMessage>()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var armed = false
    private val queuedReceives = mutableListOf<JSObject>()
    private val queuedTaps = mutableListOf<JSObject>()

    override fun load(webView: WebView) {
        super.load(webView)
        instance = this
        handler.post {
            // A cold-start tap launched the activity with the push's data
            // keys as intent extras — queue it for the first listener.
            activity.intent?.let { readTapIntent(it) }
            while (earlyMessages.isNotEmpty()) {
                deliverForegroundMessage(earlyMessages.removeFirst())
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        handler.post { readTapIntent(intent) }
    }

    // MARK: Commands

    @Command
    fun isPermissionGranted(invoke: Invoke) {
        invoke.resolve(JSObject().put("granted", notificationsAllowed()))
    }

    @Command
    fun requestPermission(invoke: Invoke) {
        if (notificationsAllowed()) {
            invoke.resolve(JSObject().put("granted", true))
            return
        }
        requestPermissionForAlias(PERMISSION_ALIAS, invoke, "permissionCallback")
    }

    @PermissionCallback
    fun permissionCallback(invoke: Invoke) {
        invoke.resolve(JSObject().put("granted", notificationsAllowed()))
    }

    @Command
    fun registerForPush(invoke: Invoke) {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> invoke.resolve(JSObject().put("token", token)) }
            .addOnFailureListener { e ->
                invoke.reject("push registration failed: ${e.message ?: "unknown"}")
            }
    }

    /** Arm live delivery and flush everything queued before a JS listener
     *  existed (trigger() drops events with no registered channel). */
    @Command
    fun startNotificationEvents(invoke: Invoke) {
        handler.post {
            armed = true
            queuedReceives.forEach { trigger("notificationReceived", it) }
            queuedReceives.clear()
            queuedTaps.forEach { trigger("notificationTapped", it) }
            queuedTaps.clear()
            invoke.resolve()
        }
    }

    // MARK: Local scheduled notifications (reminders)

    /** Schedule an OS-local notification via AlarmManager. Same-id scheduling
     *  replaces the pending alarm (FLAG_UPDATE_CURRENT on a same-requestCode
     *  PendingIntent) — the resync primitive. Inexact-while-idle: minute-ish
     *  precision without the SCHEDULE_EXACT_ALARM policy surface. Alarms do
     *  not survive reboot; callers resync on next launch. */
    @Command
    fun scheduleLocal(invoke: Invoke) {
        val id = invoke.parseArgs(ScheduleLocalArgs::class.java)
        val alarm = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            id.atMs.toLong(),
            publisherIntent(id.id) { intent ->
                intent.putExtra(EXTRA_TITLE, id.title)
                id.body?.let { intent.putExtra(EXTRA_BODY, it) }
                id.data?.let { intent.putExtra(EXTRA_DATA, HashMap(it)) }
            },
        )
        ledgerUpdate(activity) { it.add(id.id.toString()) }
        invoke.resolve()
    }

    @Command
    fun cancelLocal(invoke: Invoke) {
        val args = invoke.parseArgs(CancelLocalArgs::class.java)
        val alarm = activity.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val manager =
            activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        for (id in args.ids) {
            alarm.cancel(publisherIntent(id) {})
            manager.cancel(id)
        }
        ledgerUpdate(activity) { set -> args.ids.forEach { set.remove(it.toString()) } }
        invoke.resolve()
    }

    /** Scheduled-but-not-fired ids from the SharedPreferences ledger (Android
     *  has no AlarmManager query API). Reboot drops the alarms but not the
     *  ledger — callers treating this as advisory should resync anyway. */
    @Command
    fun getPendingLocal(invoke: Invoke) {
        val prefs = activity.getSharedPreferences(LEDGER_PREFS, Context.MODE_PRIVATE)
        val ids = prefs.getStringSet(LEDGER_KEY, emptySet())!!.mapNotNull { it.toIntOrNull() }
        val payload = JSObject()
        payload.put("ids", org.json.JSONArray(ids))
        invoke.resolve(payload)
    }

    private fun publisherIntent(id: Int, fill: (Intent) -> Unit): PendingIntent {
        val intent = Intent(activity, LocalNotificationPublisher::class.java)
        intent.putExtra(EXTRA_ID, id)
        fill(intent)
        return PendingIntent.getBroadcast(
            activity,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // MARK: Delivery (from PushNotificationsService)

    internal fun deliverForegroundMessage(message: RemoteMessage) {
        val payload = JSObject()
        message.notification?.title?.let { payload.put("title", it) }
        message.notification?.body?.let { payload.put("body", it) }
        val data = JSObject()
        for ((key, value) in message.data) data.put(key, value)
        payload.put("data", data)
        handler.post { emitOrQueue("notificationReceived", payload, queuedReceives) }
    }

    // MARK: Internals (main thread only)

    private fun notificationsAllowed(): Boolean {
        // Below 13 notifications need no runtime permission.
        if (Build.VERSION.SDK_INT < 33) return true
        return getPermissionState(PERMISSION_ALIAS) == PermissionState.GRANTED
    }

    private fun emitOrQueue(event: String, payload: JSObject, queue: MutableList<JSObject>) {
        if (armed) trigger(event, payload) else queue.add(payload)
    }

    private fun readTapIntent(intent: Intent) {
        val extras = intent.extras ?: return
        // Only intents FCM stamped or our own local-notification marker — a
        // plain launch/deep-link intent is not a notification tap.
        if (extras.getString("google.message_id") == null &&
            extras.getString(LOCAL_TAP_MARKER) == null
        ) {
            return
        }
        val data = JSObject()
        for (key in extras.keySet()) {
            if (key.startsWith("google.") || key.startsWith("gcm.") ||
                key.startsWith("app.tauri.pushnotifications") ||
                key == "from" || key == "collapse_key"
            ) {
                continue
            }
            extras.getString(key)?.let { data.put(key, it) }
        }
        val payload = JSObject().put("data", data)
        emitOrQueue("notificationTapped", payload, queuedTaps)
    }
}
