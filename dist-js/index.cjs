'use strict';

var core = require('@tauri-apps/api/core');

// ---------------------------------------------------------------------------
// Commands
// ---------------------------------------------------------------------------
/** Whether notification permission is currently granted. Desktop: `false`. */
async function isPermissionGranted() {
    const status = await core.invoke('plugin:push-notifications|is_permission_granted');
    return status.granted;
}
/**
 * Ensure notification permission, prompting if the OS still allows a prompt.
 * A denial is an answer (`false`), not an error. Desktop: `false`.
 */
async function requestPermission() {
    const status = await core.invoke('plugin:push-notifications|request_permission');
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
async function registerForPush() {
    const registration = await core.invoke('plugin:push-notifications|register_for_push');
    return registration.token;
}
/**
 * Schedule an OS-local notification. iOS: `UNTimeIntervalNotificationTrigger`
 * (survives app termination, not device restart-before-fire edge cases).
 * Android: inexact `AlarmManager` while-idle alarm (minute-ish precision; does
 * NOT survive reboot) — resync by re-scheduling on every launch.
 * Desktop: rejects with `Unsupported`.
 */
async function scheduleLocalNotification(notification) {
    await core.invoke('plugin:push-notifications|schedule_local', { notification });
}
/** Cancel pending (and dismiss delivered) local notifications by id. */
async function cancelLocalNotifications(ids) {
    await core.invoke('plugin:push-notifications|cancel_local', { ids });
}
/** Ids of scheduled-but-not-yet-fired local notifications. Advisory on
 *  Android (a ledger — reboot drops alarms without updating it). */
async function getPendingLocalNotifications() {
    const pending = await core.invoke('plugin:push-notifications|get_pending_local');
    return pending.ids;
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
async function armDelivery() {
    await core.invoke('plugin:push-notifications|start_notification_events').catch(() => {
        /* desktop / arming is best-effort */
    });
}
/**
 * A push arrived while the app is in the FOREGROUND. The system shows
 * nothing for these (the app owns its in-app surface — show your own
 * toast/banner); background pushes go to the system tray instead and arrive
 * only as {@link onNotificationTapped} if the user taps them.
 */
async function onNotificationReceived(handler) {
    const listener = await core.addPluginListener('push-notifications', 'notificationReceived', handler);
    await armDelivery();
    return listener;
}
/**
 * The user tapped a notification — from the tray, or the tap that launched
 * the app cold (replayed on registration, never lost). Route `data`'s
 * deep-link fields here.
 */
async function onNotificationTapped(handler) {
    const listener = await core.addPluginListener('push-notifications', 'notificationTapped', handler);
    await armDelivery();
    return listener;
}

exports.cancelLocalNotifications = cancelLocalNotifications;
exports.getPendingLocalNotifications = getPendingLocalNotifications;
exports.isPermissionGranted = isPermissionGranted;
exports.onNotificationReceived = onNotificationReceived;
exports.onNotificationTapped = onNotificationTapped;
exports.registerForPush = registerForPush;
exports.requestPermission = requestPermission;
exports.scheduleLocalNotification = scheduleLocalNotification;
