//
//  PushNotificationsPlugin.swift
//  tauri-plugin-push-notifications
//
//  APNs push registration + notification events.
//
//  The app needs the Push Notifications capability (`aps-environment`
//  entitlement); registration rejects without it. Foreground pushes emit
//  `notificationReceived` (and are NOT presented by the system — the app
//  owns its in-app surface); taps emit `notificationTapped`, including the
//  cold-start tap that launched the app.
//
//  Delivery contract: `trigger()` reaches registered JS channels only, so
//  every event is queued until the guest bindings call
//  `startNotificationEvents` (right after the first listener registers),
//  then queued events replay in order. All shared state is confined to the
//  main queue — command handlers hop onto it and never block it.
//

import Foundation
import Tauri
import UIKit
import UserNotifications
import WebKit

// MARK: - Payloads (mirror src/models.rs / guest-js exactly)

struct PermissionPayload: Encodable {
    let granted: Bool
}

struct RegistrationPayload: Encodable {
    let token: String
}

struct NotificationPayload: Encodable {
    let title: String?
    let body: String?
    /// The push's custom key/value bag (everything outside `aps`), with
    /// non-string scalars stringified — deep-link routing data.
    let data: [String: String]
}

struct ScheduleLocalArgs: Decodable {
    /// Stable i32 — re-scheduling the same id replaces the pending request.
    let id: Int
    let title: String
    let body: String?
    /// Epoch milliseconds to fire at; past instants fire ~immediately.
    let atMs: Double
    /// Delivered back through `notificationTapped` as the `data` bag.
    let data: [String: String]?
}

struct CancelLocalArgs: Decodable {
    let ids: [Int]
}

struct PendingLocalPayload: Encodable {
    let ids: [Int]
}

@available(iOS 15.0, *)
class PushNotificationsPlugin: Plugin, UNUserNotificationCenterDelegate {
    /// The instance the swizzled AppDelegate hooks report back to. A Tauri
    /// app registers exactly one instance of a plugin.
    private static weak var shared: PushNotificationsPlugin?

    /// `registerForPush` invokes awaiting the APNs token callback.
    private var pendingRegistrations: [Invoke] = []

    /// Events that fired before JS armed delivery (`startNotificationEvents`)
    /// — most importantly the cold-start tap that launched the app.
    private var armed = false
    private var queuedReceives: [NotificationPayload] = []
    private var queuedTaps: [NotificationPayload] = []

    public override func load(webview: WKWebView) {
        PushNotificationsPlugin.shared = self
        // The notification-center delegate must exist before the first
        // delivery — including the cold-start tap, which UNUserNotification-
        // Center replays shortly after launch to whoever the delegate is.
        // Plugin load runs during application startup, early enough.
        UNUserNotificationCenter.current().delegate = self
        Self.installAppDelegateHooks()
    }

    // MARK: Commands

    @objc public func isPermissionGranted(_ invoke: Invoke) {
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            invoke.resolve(PermissionPayload(granted: settings.authorizationStatus.grantsDelivery))
        }
    }

    @objc public func requestPermission(_ invoke: Invoke) {
        UNUserNotificationCenter.current().requestAuthorization(options: [
            .alert, .badge, .sound,
        ]) { granted, _ in
            // A denied prompt is an answer, not an error.
            invoke.resolve(PermissionPayload(granted: granted))
        }
    }

    /// Ask APNs for the device token. Resolution happens in the swizzled
    /// AppDelegate callbacks below; parking the Invoke (instead of blocking)
    /// keeps the ipc queue free while APNs round-trips.
    @objc public func registerForPush(_ invoke: Invoke) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else {
                invoke.reject("plugin is gone")
                return
            }
            UNUserNotificationCenter.current().getNotificationSettings { settings in
                guard settings.authorizationStatus.grantsDelivery else {
                    invoke.reject("notification permission not granted")
                    return
                }
                DispatchQueue.main.async {
                    self.pendingRegistrations.append(invoke)
                    UIApplication.shared.registerForRemoteNotifications()
                }
            }
        }
    }

    /// Called by the guest bindings right after a JS listener registers:
    /// replay everything queued while no channel existed, then deliver live.
    @objc public func startNotificationEvents(_ invoke: Invoke) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else {
                invoke.resolve()
                return
            }
            self.armed = true
            for payload in self.queuedReceives {
                try? self.trigger("notificationReceived", data: payload)
            }
            self.queuedReceives.removeAll()
            for payload in self.queuedTaps {
                try? self.trigger("notificationTapped", data: payload)
            }
            self.queuedTaps.removeAll()
            invoke.resolve()
        }
    }

    // MARK: Local scheduled notifications (reminders)

    /// Schedule an OS-local notification. Same-id scheduling REPLACES the
    /// pending request (UNUserNotificationCenter semantics) — callers resync
    /// by re-scheduling with deterministic ids. Taps flow through the same
    /// `notificationTapped` event as push taps (`didReceive` reads userInfo);
    /// foreground firings keep the system banner (`willPresent` only mutes
    /// PUSH presentations).
    @objc public func scheduleLocal(_ invoke: Invoke) throws {
        let args = try invoke.parseArgs(ScheduleLocalArgs.self)
        let content = UNMutableNotificationContent()
        content.title = args.title
        if let body = args.body { content.body = body }
        if let data = args.data { content.userInfo = data }
        content.sound = .default
        let seconds = max(1, (args.atMs / 1000) - Date().timeIntervalSince1970)
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: seconds, repeats: false)
        let request = UNNotificationRequest(
            identifier: String(args.id), content: content, trigger: trigger)
        UNUserNotificationCenter.current().add(request) { error in
            if let error = error {
                invoke.reject("scheduling failed: \(error.localizedDescription)")
            } else {
                invoke.resolve()
            }
        }
    }

    @objc public func cancelLocal(_ invoke: Invoke) throws {
        let args = try invoke.parseArgs(CancelLocalArgs.self)
        let ids = args.ids.map(String.init)
        let center = UNUserNotificationCenter.current()
        center.removePendingNotificationRequests(withIdentifiers: ids)
        center.removeDeliveredNotifications(withIdentifiers: ids)
        invoke.resolve()
    }

    /// Ids of still-pending local requests. Only numeric identifiers are
    /// ours (push notifications never enter the pending store).
    @objc public func getPendingLocal(_ invoke: Invoke) {
        UNUserNotificationCenter.current().getPendingNotificationRequests { requests in
            let ids = requests.compactMap { Int($0.identifier) }
            invoke.resolve(PendingLocalPayload(ids: ids))
        }
    }

    // MARK: APNs registration callbacks (via the AppDelegate hooks)

    fileprivate func apnsTokenReceived(_ deviceToken: Data) {
        let token = deviceToken.map { String(format: "%02x", $0) }.joined()
        let waiting = pendingRegistrations
        pendingRegistrations.removeAll()
        for invoke in waiting {
            invoke.resolve(RegistrationPayload(token: token))
        }
    }

    fileprivate func apnsRegistrationFailed(_ error: Error) {
        let waiting = pendingRegistrations
        pendingRegistrations.removeAll()
        for invoke in waiting {
            invoke.reject("push registration failed: \(error.localizedDescription)")
        }
    }

    // MARK: UNUserNotificationCenterDelegate

    /// A push arrived while the app is FOREGROUND. Hand it to JS (the app
    /// owns its in-app surface — a toast/banner of its own) and present
    /// nothing system-side, so the user never sees a double notification.
    /// Non-push notifications (local — not produced by this plugin, but a
    /// host app may schedule its own) keep the system presentation.
    public func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) ->
            Void
    ) {
        guard notification.request.trigger is UNPushNotificationTrigger else {
            completionHandler([.banner, .list, .sound])
            return
        }
        let payload = Self.payload(from: notification.request.content)
        DispatchQueue.main.async { [weak self] in
            self?.emitOrQueue("notificationReceived", payload, queue: \.queuedReceives)
        }
        completionHandler([])
    }

    /// The user TAPPED a notification (background, tray, or the cold-start
    /// tap that launched the app — the center replays that one to the
    /// delegate set at launch, which `load` guarantees we are).
    public func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        if response.actionIdentifier == UNNotificationDefaultActionIdentifier {
            let payload = Self.payload(from: response.notification.request.content)
            DispatchQueue.main.async { [weak self] in
                self?.emitOrQueue("notificationTapped", payload, queue: \.queuedTaps)
            }
        }
        completionHandler()
    }

    /// Main-queue only. Deliver live once armed; queue before that.
    private func emitOrQueue(
        _ event: String,
        _ payload: NotificationPayload,
        queue: ReferenceWritableKeyPath<PushNotificationsPlugin, [NotificationPayload]>
    ) {
        if armed {
            try? trigger(event, data: payload)
        } else {
            self[keyPath: queue].append(payload)
        }
    }

    private static func payload(from content: UNNotificationContent) -> NotificationPayload {
        var data: [String: String] = [:]
        for (key, value) in content.userInfo {
            guard let key = key as? String, key != "aps" else { continue }
            if let string = value as? String {
                data[key] = string
            } else if let number = value as? NSNumber {
                data[key] = number.stringValue
            }
        }
        return NotificationPayload(
            title: content.title.isEmpty ? nil : content.title,
            body: content.body.isEmpty ? nil : content.body,
            data: data
        )
    }

    // MARK: AppDelegate hooks

    /// APNs reports the device token through `UIApplicationDelegate`
    /// callbacks the host app's generated delegate does not implement. Add
    /// them at runtime (or wrap the originals if a future template adds
    /// them) and forward to the plugin. Installed once, from `load`.
    private static var hooksInstalled = false

    private static func installAppDelegateHooks() {
        guard !hooksInstalled, let delegate = UIApplication.shared.delegate else { return }
        hooksInstalled = true
        let cls: AnyClass = type(of: delegate)

        let registeredSelector = #selector(
            UIApplicationDelegate.application(_:didRegisterForRemoteNotificationsWithDeviceToken:))
        install(on: cls, selector: registeredSelector) { _, token in
            guard let deviceToken = token as? Data else { return }
            DispatchQueue.main.async { shared?.apnsTokenReceived(deviceToken) }
        }

        let failedSelector = #selector(
            UIApplicationDelegate.application(_:didFailToRegisterForRemoteNotificationsWithError:))
        install(on: cls, selector: failedSelector) { _, err in
            let error =
                (err as? Error)
                ?? NSError(
                    domain: "tauri-plugin-push-notifications", code: -1,
                    userInfo: [NSLocalizedDescriptionKey: "unknown registration failure"])
            DispatchQueue.main.async { shared?.apnsRegistrationFailed(error) }
        }
    }

    /// Add a `(UIApplication, AnyObject) -> Void` delegate method to `cls`,
    /// forwarding to `handler`. If the class already implements it (a future
    /// template, or another plugin got there first), chain: the original
    /// runs first, then `handler` — never replaced, never dropped.
    private static func install(
        on cls: AnyClass, selector: Selector,
        handler: @escaping (AnyObject, AnyObject) -> Void
    ) {
        let addedBlock: @convention(block) (AnyObject, AnyObject, AnyObject) -> Void = {
            _, app, arg in
            handler(app, arg)
        }
        let imp = imp_implementationWithBlock(addedBlock)
        if class_addMethod(cls, selector, imp, "v@:@@") { return }
        guard let method = class_getInstanceMethod(cls, selector) else { return }
        typealias Fn = @convention(c) (AnyObject, Selector, AnyObject, AnyObject) -> Void
        let originalFn = unsafeBitCast(method_getImplementation(method), to: Fn.self)
        let wrapper: @convention(block) (AnyObject, AnyObject, AnyObject) -> Void = {
            target, app, arg in
            originalFn(target, selector, app, arg)
            handler(app, arg)
        }
        method_setImplementation(method, imp_implementationWithBlock(wrapper))
    }
}

@available(iOS 15.0, *)
extension UNAuthorizationStatus {
    /// Authorized or provisional — either delivers notifications.
    fileprivate var grantsDelivery: Bool {
        self == .authorized || self == .provisional || self == .ephemeral
    }
}

@_cdecl("init_plugin_push_notifications")
func initPlugin() -> Plugin {
    if #available(iOS 15.0, *) {
        return PushNotificationsPlugin()
    }
    // The consumer app declares minimumSystemVersion 15.0; this branch only
    // pacifies the compiler on older availability paths.
    fatalError("tauri-plugin-push-notifications requires iOS 15+")
}
