import Foundation
import ServiceManagement

enum LoginCommand {
    static func run(_ args: [String]) -> Int32 {
        guard Bundle.main.bundleIdentifier != nil else {
            print("notFound")
            FileHandle.standardError.write("not running inside an app bundle\n".data(using: .utf8)!)
            return 2
        }
        let service = SMAppService.mainApp
        do {
            switch args.first {
            case "status", nil: break
            case "enable": if service.status != .enabled { try service.register() }
            case "disable": if service.status == .enabled || service.status == .requiresApproval { try service.unregister() }
            default:
                FileHandle.standardError.write("usage: babakit login status|enable|disable\n".data(using: .utf8)!)
                return 64
            }
        } catch {
            print(describe(service.status))
            FileHandle.standardError.write("login item error: \(error.localizedDescription)\n".data(using: .utf8)!)
            return 1
        }
        print(describe(service.status))
        return 0
    }

    static func describe(_ status: SMAppService.Status) -> String {
        switch status {
        case .enabled: return "enabled"
        case .requiresApproval: return "requiresApproval"
        case .notRegistered: return "disabled"
        case .notFound: return "notFound"
        @unknown default: return "notFound"
        }
    }
}
