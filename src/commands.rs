use tauri::{command, AppHandle, Runtime};

use crate::models::*;
use crate::PushNotificationsExt;
use crate::Result;

// Every command is async: Tauri runs async commands on the async runtime's
// worker pool, and the mobile calls below await `run_mobile_plugin_async` —
// nothing here can ever block the main thread (the sync-command +
// run_mobile_plugin combination deadlocks iOS against WebKit work on the
// shared serial `ipc` dispatch queue).

#[command]
pub(crate) async fn is_permission_granted<R: Runtime>(
    app: AppHandle<R>,
) -> Result<PermissionStatus> {
    app.push_notifications().is_permission_granted().await
}

#[command]
pub(crate) async fn request_permission<R: Runtime>(app: AppHandle<R>) -> Result<PermissionStatus> {
    app.push_notifications().request_permission().await
}

#[command]
pub(crate) async fn register_for_push<R: Runtime>(app: AppHandle<R>) -> Result<PushRegistration> {
    app.push_notifications().register_for_push().await
}

#[command]
pub(crate) async fn start_notification_events<R: Runtime>(app: AppHandle<R>) -> Result<()> {
    app.push_notifications().start_notification_events().await
}

#[command]
pub(crate) async fn schedule_local<R: Runtime>(
    app: AppHandle<R>,
    notification: LocalNotification,
) -> Result<()> {
    app.push_notifications().schedule_local(notification).await
}

#[command]
pub(crate) async fn cancel_local<R: Runtime>(app: AppHandle<R>, ids: Vec<i32>) -> Result<()> {
    app.push_notifications().cancel_local(ids).await
}

#[command]
pub(crate) async fn get_pending_local<R: Runtime>(app: AppHandle<R>) -> Result<PendingLocal> {
    app.push_notifications().get_pending_local().await
}
