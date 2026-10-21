import Foundation
import UserNotifications

/// Newline-delimited JSON server for UserNotifications. One request per line on stdin; replies carry the
/// request's `rid`. Notification responses arrive as unsolicited `{"event":"response",...}` lines.
final class NotificationServer: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationServer()

    private let outLock = NSLock()
    private var registeredCategories: [String: UNNotificationCategory] = [:]
    private lazy var center: UNUserNotificationCenter? = {
        // UNUserNotificationCenter raises an Objective-C exception without a bundle; check first.
        guard Bundle.main.bundleIdentifier != nil else { return nil }
        return UNUserNotificationCenter.current()
    }()

    func run() {
        if let center {
            center.delegate = self
        }
        emit(["event": "ready", "bundled": center != nil, "bundleId": Bundle.main.bundleIdentifier ?? NSNull()])
        let reader = Thread {
            while let line = readLine(strippingNewline: true) {
                guard !line.isEmpty else { continue }
                DispatchQueue.main.async { self.handle(line) }
            }
            // Parent closed our stdin (app quit): stop.
            DispatchQueue.main.async { exit(0) }
        }
        reader.start()
        dispatchMain()
    }

    // MARK: - Protocol

    private func handle(_ line: String) {
        guard let data = line.data(using: .utf8),
              let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let op = obj["op"] as? String
        else {
            emit(["ok": false, "error": "malformed request"])
            return
        }
        let rid = obj["rid"] ?? NSNull()
        if op == "ping" { reply(rid, ok: true); return }
        guard let center else {
            reply(rid, ok: false, extra: ["status": "unavailable", "error": "babakit is not running inside an app bundle"])
            return
        }
        switch op {
        case "authStatus":
            center.getNotificationSettings { settings in
                self.reply(rid, ok: true, extra: ["status": Self.describe(settings.authorizationStatus)])
            }
        case "requestAuth":
            center.requestAuthorization(options: [.alert, .sound, .badge]) { _, error in
                center.getNotificationSettings { settings in
                    var extra: [String: Any] = ["status": Self.describe(settings.authorizationStatus)]
                    if let error { extra["error"] = error.localizedDescription }
                    self.reply(rid, ok: error == nil, extra: extra)
                }
            }
        case "post":
            post(obj, rid: rid, center: center)
        case "remove":
            guard let id = obj["id"] as? String else { reply(rid, ok: false, extra: ["error": "missing id"]); return }
            center.removePendingNotificationRequests(withIdentifiers: [id])
            center.removeDeliveredNotifications(withIdentifiers: [id])
            reply(rid, ok: true)
        case "delivered":
            center.getDeliveredNotifications { list in
                self.reply(rid, ok: true, extra: ["ids": list.map { $0.request.identifier }])
            }
        default:
            reply(rid, ok: false, extra: ["error": "unknown op \(op)"])
        }
    }

    private func post(_ obj: [String: Any], rid: Any, center: UNUserNotificationCenter) {
        guard let id = obj["id"] as? String, let title = obj["title"] as? String else {
            reply(rid, ok: false, extra: ["error": "missing id or title"])
            return
        }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = (obj["body"] as? String) ?? ""
        if (obj["sound"] as? Bool) ?? true { content.sound = .default }
        if let kind = obj["kind"] as? String { content.threadIdentifier = "taskbaba.\(kind.lowercased())" }
        var info: [String: String] = [:]
        if let ui = obj["userInfo"] as? [String: Any] {
            for (k, v) in ui { info[k] = "\(v)" }
        }
        content.userInfo = info

        let actions = (obj["actions"] as? [[String: Any]]) ?? []
        if !actions.isEmpty {
            content.categoryIdentifier = ensureCategory(actions, center: center)
        }
        // Same identifier replaces the previous banner, so repeated reminders never stack.
        let request = UNNotificationRequest(identifier: id, content: content, trigger: nil)
        center.add(request) { error in
            if let error {
                let ns = error as NSError
                if ns.domain == UNErrorDomain && ns.code == UNError.Code.notificationsNotAllowed.rawValue {
                    self.reply(rid, ok: false, extra: ["error": "notAllowed"])
                } else {
                    self.reply(rid, ok: false, extra: ["error": error.localizedDescription])
                }
            } else {
                self.reply(rid, ok: true)
            }
        }
    }

    /// Categories are keyed by their action set so any combination the app sends gets buttons.
    private func ensureCategory(_ actions: [[String: Any]], center: UNUserNotificationCenter) -> String {
        let parsed: [(String, String, Bool)] = actions.compactMap {
            guard let id = $0["id"] as? String, let title = $0["title"] as? String else { return nil }
            return (id, title, ($0["destructive"] as? Bool) ?? false)
        }
        let key = "baba." + parsed.map { "\($0.0)=\($0.1)\($0.2 ? "!" : "")" }.joined(separator: "|")
        if registeredCategories[key] == nil {
            let unActions = parsed.map { id, title, destructive in
                UNNotificationAction(identifier: id, title: title, options: destructive ? [.destructive] : [])
            }
            registeredCategories[key] = UNNotificationCategory(identifier: key, actions: unActions, intentIdentifiers: [], options: [])
            center.setNotificationCategories(Set(registeredCategories.values))
        }
        return key
    }

    // MARK: - Delegate

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list, .sound])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let action: String
        switch response.actionIdentifier {
        case UNNotificationDefaultActionIdentifier: action = "default"
        case UNNotificationDismissActionIdentifier: action = "dismiss"
        default: action = response.actionIdentifier
        }
        let info = response.notification.request.content.userInfo.reduce(into: [String: String]()) { acc, kv in
            acc["\(kv.key)"] = "\(kv.value)"
        }
        emit(["event": "response", "id": response.notification.request.identifier, "action": action, "userInfo": info])
        completionHandler()
    }

    // MARK: - Output

    private func reply(_ rid: Any, ok: Bool, extra: [String: Any] = [:]) {
        var obj = extra
        obj["rid"] = rid
        obj["ok"] = ok
        emit(obj)
    }

    private func emit(_ obj: [String: Any]) {
        guard let data = try? JSONSerialization.data(withJSONObject: obj, options: [.sortedKeys]) else { return }
        outLock.lock(); defer { outLock.unlock() }
        FileHandle.standardOutput.write(data)
        FileHandle.standardOutput.write("\n".data(using: .utf8)!)
    }

    static func describe(_ s: UNAuthorizationStatus) -> String {
        switch s {
        case .notDetermined: return "notDetermined"
        case .denied: return "denied"
        case .authorized, .provisional: return "authorized"
        @unknown default: return "authorized"
        }
    }
}
