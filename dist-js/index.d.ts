import { type PluginListener } from '@tauri-apps/api/core';
/**
 * A push notification as delivered to JS. `data` is the push's custom
 * key/value bag (deep-link routing fields and the like) — everything the
 * sender attached outside the platform envelope, with non-string scalars
 * stringified.
 */
export interface PushNotification {
    title?: string;
    body?: string;
    data?: Record<string, string>;
}
/** Whether notification permission is currently granted. Desktop: `false`. */
export declare function isPermissionGranted(): Promise<boolean>;
/**
 * Ensure notification permission, prompting if the OS still allows a prompt.
 * A denial is an answer (`false`), not an error. Desktop: `false`.
 */
export declare function requestPermission(): Promise<boolean>;
/**
 * Register this device for push and return its token — the APNs hex device
 * token on iOS, the FCM registration token on Android. Hand it to your
 * server; it is the address notifications are sent to.
 *
 * Rejects when permission is not granted, when the app lacks the platform
 * prerequisites (iOS: the `aps-environment` entitlement; Android: Firebase
 * configuration), or on desktop.
 *
 * Tokens can rotate: call this on every launch/login and re-register the
 * result server-side rather than caching it.
 */
export declare function registerForPush(): Promise<string>;
/**
 * A push arrived while the app is in the FOREGROUND. The system shows
 * nothing for these (the app owns its in-app surface — show your own
 * toast/banner); background pushes go to the system tray instead and arrive
 * only as {@link onNotificationTapped} if the user taps them.
 */
export declare function onNotificationReceived(handler: (notification: PushNotification) => void): Promise<PluginListener>;
/**
 * The user tapped a notification — from the tray, or the tap that launched
 * the app cold (replayed on registration, never lost). Route `data`'s
 * deep-link fields here.
 */
export declare function onNotificationTapped(handler: (notification: PushNotification) => void): Promise<PluginListener>;
