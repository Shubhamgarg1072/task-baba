import Foundation
import Security

enum KeychainCommand {
    static let servicePrefix = "app.taskbaba"

    static func run(_ args: [String]) -> Int32 {
        guard args.count == 3 else {
            err("usage: babakit keychain get|set|delete <service> <account>")
            return 64
        }
        let (op, rawService, account) = (args[0], args[1], args[2])
        // Namespace every item so the helper cannot be used to read unrelated Keychain entries.
        let service = rawService.hasPrefix(servicePrefix) ? rawService : "\(servicePrefix).\(rawService)"
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        switch op {
        case "get":
            var query = base
            query[kSecReturnData as String] = true
            query[kSecMatchLimit as String] = kSecMatchLimitOne
            var out: CFTypeRef?
            let status = SecItemCopyMatching(query as CFDictionary, &out)
            if status == errSecItemNotFound { return 44 }
            guard status == errSecSuccess, let data = out as? Data else { return report(status) }
            FileHandle.standardOutput.write(data)
            return 0
        case "set":
            let secret = FileHandle.standardInput.readDataToEndOfFile()
            guard !secret.isEmpty else {
                err("empty secret on stdin")
                return 65
            }
            let update = SecItemUpdate(base as CFDictionary, [kSecValueData as String: secret] as CFDictionary)
            if update == errSecSuccess { return 0 }
            if update != errSecItemNotFound { return report(update) }
            var add = base
            add[kSecValueData as String] = secret
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            add[kSecAttrLabel as String] = "Task Baba: \(account)"
            let status = SecItemAdd(add as CFDictionary, nil)
            return status == errSecSuccess ? 0 : report(status)
        case "delete":
            let status = SecItemDelete(base as CFDictionary)
            return (status == errSecSuccess || status == errSecItemNotFound) ? 0 : report(status)
        default:
            err("unknown keychain op \(op)")
            return 64
        }
    }

    private static func report(_ status: OSStatus) -> Int32 {
        let message = (SecCopyErrorMessageString(status, nil) as String?) ?? "OSStatus \(status)"
        err("keychain error: \(message)")
        return 1
    }

    private static func err(_ s: String) {
        FileHandle.standardError.write((s + "\n").data(using: .utf8)!)
    }
}
