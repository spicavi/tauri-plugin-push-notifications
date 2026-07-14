# tauri-plugin-push-notifications

Push notifications for [Tauri 2](https://tauri.app) apps — APNs device tokens
on iOS, FCM registration tokens on Android, with foreground-receive and tap
events (including the cold-start tap that launched the app).

Deliberately small: this plugin registers the device and delivers events.
Sending is your server's job (via APNs/FCM), and in-app presentation is your
app's job — no local-notification scheduling, no channels, no tray API.

| Platform | Backend | `registerForPush` returns |
| -------- | ------- | ------------------------- |
| iOS      | APNs    | hex device token          |
| Android  | FCM     | FCM registration token    |
| Desktop  | —       | rejects (`Unsupported`); permission reads answer `false` |

## Why every command is async

A Tauri mobile command declared as a **sync** `fn` runs on the calling thread
— on iOS the **main thread** — and a blocking `run_mobile_plugin` there
deadlocks against WebKit work on Tauri's shared serial `ipc` dispatch queue
(the app freezes, then the watchdog kills it with `0x8BADF00D`). Every command
in this plugin is `async` end-to-end (`run_mobile_plugin_async`), so that
deadlock is impossible by construction.

## Install

```toml
# src-tauri/Cargo.toml
[dependencies]
tauri-plugin-push-notifications = "0.1"
```

```bash
pnpm add @spicavi/tauri-plugin-push-notifications
```

```rust
// src-tauri/src/lib.rs
tauri::Builder::default()
    .plugin(tauri_plugin_push_notifications::init())
```

```jsonc
// src-tauri/capabilities/default.json
{ "permissions": ["push-notifications:default"] }
```

### iOS setup

- Add the **Push Notifications** capability (`aps-environment` entitlement)
  to the app — registration rejects without it.
- The app's `minimumSystemVersion` must be **15.0+**.

### Android setup

- Configure Firebase in the consumer app: `google-services.json` plus the
  `com.google.gms.google-services` Gradle plugin. The plugin's manifest
  merges the FCM service and the `POST_NOTIFICATIONS` permission for you.

## Usage

```ts
import {
  isPermissionGranted,
  requestPermission,
  registerForPush,
  onNotificationReceived,
  onNotificationTapped,
} from '@spicavi/tauri-plugin-push-notifications';

if (await requestPermission()) {
  // Hand the token to your server — it's the address pushes are sent to.
  // Tokens rotate: re-register on every launch/login, don't cache.
  const token = await registerForPush();
}

// Foreground pushes: the system shows NOTHING for these — surface your own
// in-app toast/banner. Background pushes go to the system tray instead.
await onNotificationReceived((n) => {
  console.log(n.title, n.body, n.data);
});

// Taps — from the tray, or the tap that cold-started the app (replayed on
// registration, never lost). Route your deep-link fields from `data`.
await onNotificationTapped((n) => {
  navigateTo(n.data?.path);
});
```

### Delivery contract

Native events only reach registered JS channels, so everything that fires
before your first listener exists — most importantly the cold-start tap —
is queued natively and replayed the moment a listener registers. Register
your listeners early (app shell init) and you'll never miss an event.

## License

MIT
