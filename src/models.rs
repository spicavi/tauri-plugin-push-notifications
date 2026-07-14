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
