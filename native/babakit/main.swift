// babakit — Task Baba's small native macOS bridge.
//
// The Kotlin/Compose app spawns this helper for the few things the JVM cannot do natively:
//   babakit serve                                        UserNotifications over newline-delimited JSON (stdin/stdout)
//   babakit keychain get|set|delete <service> <account>  generic-password Keychain items (secret via stdin)
//   babakit login status|enable|disable                  launch at login via SMAppService.mainApp
//   babakit auth check|verify <reason>                   Touch ID / Mac password (LocalAuthentication)
//
// It must live inside the app bundle (Contents/MacOS/babakit) so Bundle.main resolves to Task Baba.app;
// outside a bundle it answers with errors instead of crashing.

import Foundation

let args = Array(CommandLine.arguments.dropFirst())

func fail(_ message: String, code: Int32 = 1) -> Never {
    FileHandle.standardError.write((message + "\n").data(using: .utf8)!)
    exit(code)
}

switch args.first {
case "serve":
    NotificationServer.shared.run()
case "keychain":
    exit(KeychainCommand.run(Array(args.dropFirst())))
case "login":
    exit(LoginCommand.run(Array(args.dropFirst())))
case "auth":
    exit(AuthCommand.run(Array(args.dropFirst())))
case "version":
    print("babakit 1.0 bundle=\(Bundle.main.bundleIdentifier ?? "none")")
default:
    fail("usage: babakit serve | keychain get|set|delete <service> <account> | login status|enable|disable | auth check|verify | version", code: 64)
}
