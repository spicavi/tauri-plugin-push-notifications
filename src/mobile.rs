use serde::de::DeserializeOwned;
use tauri::{
    plugin::{PluginApi, PluginHandle},
    AppHandle, Runtime,
};

use crate::models::*;

#[cfg(target_os = "ios")]
tauri::ios_plugin_binding!(init_plugin_push_notifications);

/// Initializes the Kotlin or Swift plugin classes registered by the host app.
pub fn init<R: Runtime, C: DeserializeOwned>(
    _app: &AppHandle<R>,
    api: PluginApi<R, C>,
) -> crate::Result<PushNotifications<R>> {
    #[cfg(target_os = "android")]
    let handle =
        api.register_android_plugin("app.tauri.pushnotifications", "PushNotificationsPlugin")?;
    #[cfg(target_os = "ios")]
    let handle = api.register_ios_plugin(init_plugin_push_notifications)?;
    Ok(PushNotifications(handle))
}

/// Access to the push-notifications APIs on mobile.
pub struct PushNotifications<R: Runtime>(PluginHandle<R>);

impl<R: Runtime> PushNotifications<R> {
    // Method names are camelCase to match the @objc / @Command methods on
    // the Swift / Kotlin sides. The async variant is REQUIRED, not a style
    // choice: the blocking `run_mobile_plugin` parks the calling thread on a
    // channel recv, and from a sync command that thread is iOS's main thread
    // — which WebKit work on the serial `ipc` queue may itself be waiting on
    // (an ABBA deadlock ending in a 0x8BADF00D watchdog kill).

    pub async fn is_permission_granted(&self) -> crate::Result<PermissionStatus> {
        self.0
            .run_mobile_plugin_async("isPermissionGranted", ())
            .await
            .map_err(Into::into)
    }

    pub async fn request_permission(&self) -> crate::Result<PermissionStatus> {
        self.0
            .run_mobile_plugin_async("requestPermission", ())
            .await
            .map_err(Into::into)
    }

    pub async fn register_for_push(&self) -> crate::Result<PushRegistration> {
        self.0
            .run_mobile_plugin_async("registerForPush", ())
            .await
            .map_err(Into::into)
    }

    /// Arm event delivery once the JS listener exists, replaying anything
    /// queued before it (foreground receives, and most importantly the
    /// cold-start tap that launched the app — `trigger()` only reaches
    /// registered channels, so firing it earlier would drop the event).
    pub async fn start_notification_events(&self) -> crate::Result<()> {
        self.0
            .run_mobile_plugin_async("startNotificationEvents", ())
            .await
            .map_err(Into::into)
    }

    pub async fn schedule_local(&self, notification: LocalNotification) -> crate::Result<()> {
        self.0
            .run_mobile_plugin_async("scheduleLocal", notification)
            .await
            .map_err(Into::into)
    }

    pub async fn cancel_local(&self, ids: Vec<i32>) -> crate::Result<()> {
        self.0
            .run_mobile_plugin_async("cancelLocal", CancelLocalArgs { ids })
            .await
            .map_err(Into::into)
    }

    pub async fn get_pending_local(&self) -> crate::Result<PendingLocal> {
        self.0
            .run_mobile_plugin_async("getPendingLocal", ())
            .await
            .map_err(Into::into)
    }
}
