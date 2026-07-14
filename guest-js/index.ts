import {
	addPluginListener,
	invoke,
	type PluginListener,
} from '@tauri-apps/api/core';

// ---------------------------------------------------------------------------
// Types (mirror src/models.rs)
// ---------------------------------------------------------------------------

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

interface PermissionPayload {
	granted: boolean;
}

interface RegistrationPayload {
	token: string;
}

// ---------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------

/** Whether notification permission is currently granted. Desktop: `false`. */
export async function isPermissionGranted(): Promise<boolean> {
	const status = await invoke<PermissionPayload>(
		'plugin:push-notifications|is_permission_granted',
	);
	return status.granted;
}

/**
 * Ensure notification permission, prompting if the OS still allows a prompt.
 * A denial is an answer (`false`), not an error. Desktop: `false`.
 */
export async function requestPermission(): Promise<boolean> {
	const status = await invoke<PermissionPayload>(
		'plugin:push-notifications|request_permission',
	);
	return status.granted;
}

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
export async function registerForPush(): Promise<string> {
	const registration = await invoke<RegistrationPayload>(
		'plugin:push-notifications|register_for_push',
	);
	return registration.token;
}

// ---------------------------------------------------------------------------
// Events
// ---------------------------------------------------------------------------

/**
 * Arm native event delivery. `trigger()` only reaches registered channels,
 * so anything that fired before the first listener existed (most importantly
 * the cold-start tap that launched the app) is queued natively and replayed
 * the moment this runs. Idempotent; failures are swallowed (desktop hosts
 * have nothing to arm).
 */
async function armDelivery(): Promise<void> {
	await invoke('plugin:push-notifications|start_notification_events').catch(
		() => {
			/* desktop / arming is best-effort */
		},
	);
}

/**
 * A push arrived while the app is in the FOREGROUND. The system shows
 * nothing for these (the app owns its in-app surface — show your own
 * toast/banner); background pushes go to the system tray instead and arrive
 * only as {@link onNotificationTapped} if the user taps them.
 */
export async function onNotificationReceived(
	handler: (notification: PushNotification) => void,
): Promise<PluginListener> {
	const listener = await addPluginListener(
		'push-notifications',
		'notificationReceived',
		handler,
	);
	await armDelivery();
	return listener;
}

/**
 * The user tapped a notification — from the tray, or the tap that launched
 * the app cold (replayed on registration, never lost). Route `data`'s
 * deep-link fields here.
 */
export async function onNotificationTapped(
	handler: (notification: PushNotification) => void,
): Promise<PluginListener> {
	const listener = await addPluginListener(
		'push-notifications',
		'notificationTapped',
		handler,
	);
	await armDelivery();
	return listener;
}
