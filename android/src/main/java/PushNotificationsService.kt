// The FCM entry point. Only FOREGROUND deliveries route through here in a
// way the app must handle itself:
//
//   - notification messages with the app in BACKGROUND go to the system
//     tray (FCM's default) — the tap then arrives as an activity intent the
//     plugin reads (`readTapIntent`), never through this service.
//   - messages while the app is in FOREGROUND (data-only always) land in
//     `onMessageReceived` and are forwarded as `notificationReceived`.
//
// Token rotation (`onNewToken`) is deliberately not an event: consumers
// re-register on every launch/login, which re-reads the current token.

package app.tauri.pushnotifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class PushNotificationsService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val plugin = PushNotificationsPlugin.instance
        if (plugin != null) {
            plugin.deliverForegroundMessage(message)
        } else {
            // Delivered before the plugin loaded (very early launch) — the
            // plugin drains this queue in load().
            PushNotificationsPlugin.earlyMessages.addLast(message)
        }
    }

    override fun onNewToken(token: String) {
        // No-op by design: registration re-reads the token on every call.
    }
}
