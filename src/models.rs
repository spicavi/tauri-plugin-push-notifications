use serde::{Deserialize, Serialize};

/// Whether notification permission is currently granted.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PermissionStatus {
    pub granted: bool,
}

/// A successful push registration.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PushRegistration {
    /// The device push token: APNs hex token on iOS, FCM registration token
    /// on Android. Hand it to your server; it is the address notifications
    /// are sent to.
    pub token: String,
}

/// An OS-local notification to schedule (a reminder — no server involved).
/// Taps arrive through the same `notificationTapped` event as push taps,
/// carrying `data`.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalNotification {
    /// Stable i32 identifier — scheduling the same id again REPLACES the
    /// pending notification (the resync primitive).
    pub id: i32,
    pub title: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub body: Option<String>,
    /// Epoch milliseconds to fire at. Past instants fire immediately.
    pub at_ms: f64,
    /// Custom key/value bag delivered back on tap (deep-link routing data).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub data: Option<std::collections::HashMap<String, String>>,
}

/// Ids of local notifications to cancel.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CancelLocalArgs {
    pub ids: Vec<i32>,
}

/// The ids of still-pending (scheduled, not yet fired) local notifications.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PendingLocal {
    pub ids: Vec<i32>,
}
