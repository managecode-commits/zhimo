// Copyright © 2026 立方田 <managecode@gmail.com>
import Cocoa
import InputMethodKit

let connectionName = Bundle.main.infoDictionary?["InputMethodConnectionName"] as? String ?? "Zhimo_Connection"
let application = NSApplication.shared
application.setActivationPolicy(.accessory)
let inputMethodServer = IMKServer(name: connectionName, bundleIdentifier: Bundle.main.bundleIdentifier)
guard inputMethodServer != nil else { fatalError("Unable to initialize Zhimo InputMethodKit server; check bundle metadata") }
withExtendedLifetime(inputMethodServer) { application.run() }
