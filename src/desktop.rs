use serde::de::DeserializeOwned;
use tauri::{plugin::PluginApi, AppHandle, Runtime};

use crate::models::*;
use crate::Error;

const UNSUPPORTED: &str = "push notifications are only available on iOS and Android";

pub fn init<R: Runtime, C: DeserializeOwned>(
    _app: &AppHandle<R>,
    _api: PluginApi<R, C>,
) -> crate::Result<PushNotifications<R>> {
    Ok(PushNotifications(std::marker::PhantomData))
}

/// Desktop stub — permission reads answer honestly (`false`), registration
/// rejects with `Unsupported`, and arming events is a harmless no-op so the
/// guest bindings can call it unconditionally.
// fn() -> R keeps PushNotifications Send+Sync regardless of R's auto-traits.
pub struct PushNotifications<R: Runtime>(std::marker::PhantomData<fn() -> R>);

impl<R: Runtime> PushNotifications<R> {
    pub async fn is_permission_granted(&self) -> crate::Result<PermissionStatus> {
        Ok(PermissionStatus { granted: false })
    }

    pub async fn request_permission(&self) -> crate::Result<PermissionStatus> {
        Ok(PermissionStatus { granted: false })
    }

    pub async fn register_for_push(&self) -> crate::Result<PushRegistration> {
        Err(Error::Unsupported(UNSUPPORTED))
    }

    pub async fn start_notification_events(&self) -> crate::Result<()> {
        Ok(())
    }
}
