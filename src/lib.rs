//! Push notifications for Tauri 2 apps.
//!
//! - **iOS**: APNs. `register_for_push` returns the hex device token;
//!   foreground pushes and notification taps arrive as plugin events. The
//!   app needs the `aps-environment` entitlement (Push Notifications
//!   capability) — without it registration rejects.
//! - **Android**: FCM. `register_for_push` returns the FCM registration
//!   token. The consumer app must be configured for Firebase
//!   (`google-services.json` + the `com.google.gms.google-services` Gradle
//!   plugin) or registration rejects at runtime.
//! - **Desktop**: permission reads report `false`; registration rejects
//!   with `Unsupported`. (Use Web Push or OS-native notification crates on
//!   desktop — this plugin is deliberately mobile-push only.)
//!
//! Every command is async end-to-end (`run_mobile_plugin_async`): a Tauri
//! mobile command that blocks the calling thread while awaiting the native
//! side can deadlock the iOS main thread against WebKit work on the shared
//! serial `ipc` queue — this plugin never blocks by construction.
//!
//! Delivery contract for events: `trigger()` reaches registered channels
//! only, so events that fire before a JS listener exists (most importantly
//! the cold-start tap that launched the app) are queued natively and
//! replayed when the guest bindings arm delivery via
//! `start_notification_events` — the same drain-then-arm pattern as
//! tauri-plugin-purchases' purchase updates.

use tauri::{
    plugin::{Builder, TauriPlugin},
    Manager, Runtime,
};

pub use models::*;

#[cfg(desktop)]
mod desktop;
#[cfg(mobile)]
mod mobile;

mod commands;
mod error;
mod models;

pub use error::{Error, Result};

#[cfg(desktop)]
use desktop::PushNotifications;
#[cfg(mobile)]
use mobile::PushNotifications;

/// Extensions to [`tauri::App`], [`tauri::AppHandle`] and [`tauri::Window`]
/// to access the push-notifications APIs.
pub trait PushNotificationsExt<R: Runtime> {
    fn push_notifications(&self) -> &PushNotifications<R>;
}

impl<R: Runtime, T: Manager<R>> crate::PushNotificationsExt<R> for T {
    fn push_notifications(&self) -> &PushNotifications<R> {
        self.state::<PushNotifications<R>>().inner()
    }
}

/// Initializes the plugin. Call this from your Tauri app's `lib.rs`:
///
/// ```ignore
/// .plugin(tauri_plugin_push_notifications::init())
/// ```
pub fn init<R: Runtime>() -> TauriPlugin<R> {
    Builder::new("push-notifications")
        .invoke_handler(tauri::generate_handler![
            commands::is_permission_granted,
            commands::request_permission,
            commands::register_for_push,
            commands::start_notification_events,
        ])
        .setup(|app, api| {
            #[cfg(mobile)]
            let push = mobile::init(app, api)?;
            #[cfg(desktop)]
            let push = desktop::init(app, api)?;
            app.manage(push);
            Ok(())
        })
        .build()
}
