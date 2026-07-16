const COMMANDS: &[&str] = &[
    "is_permission_granted",
    "request_permission",
    "register_for_push",
    // Foreground receives and taps are pushed as `notificationReceived` /
    // `notificationTapped` plugin events, so the listener commands are needed;
    // `start_notification_events` arms delivery once a JS listener exists
    // (and replays anything that arrived earlier — e.g. the cold-start tap).
    "start_notification_events",
    "register_listener",
    "remove_listener",
    // OS-local scheduled notifications (reminders): same tap pipeline as push.
    "schedule_local",
    "cancel_local",
    "get_pending_local",
];

fn main() {
    tauri_plugin::Builder::new(COMMANDS)
        .android_path("android")
        .ios_path("ios")
        .build();
}
