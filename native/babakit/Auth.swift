import Foundation
import LocalAuthentication

/// `babakit auth check`            → prints available | unavailable
/// `babakit auth verify <reason>`  → shows Touch ID / Mac password prompt; prints ok | cancelled | failed | unavailable
/// Uses .deviceOwnerAuthentication so Macs without Touch ID fall back to the login password.
enum AuthCommand {
    static func run(_ args: [String]) -> Int32 {
        let context = LAContext()
        var error: NSError?
        let available = context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error)
        switch args.first {
        case "check":
            print(available ? "available" : "unavailable")
            return 0
        case "verify":
            guard available else { print("unavailable"); return 2 }
            let reason = args.dropFirst().joined(separator: " ").isEmpty ? "unlock your secure notes" : args.dropFirst().joined(separator: " ")
            let done = DispatchSemaphore(value: 0)
            var outcome = "failed"
            context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) { ok, err in
                if ok { outcome = "ok" }
                else if let e = err as? LAError, [.userCancel, .appCancel, .systemCancel].contains(e.code) { outcome = "cancelled" }
                done.signal()
            }
            done.wait()
            print(outcome)
            return outcome == "ok" ? 0 : 1
        default:
            FileHandle.standardError.write("usage: babakit auth check | verify <reason>\n".data(using: .utf8)!)
            return 64
        }
    }
}
