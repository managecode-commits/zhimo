import Cocoa
import InputMethodKit

let connectionName = Bundle.main.infoDictionary?["InputMethodConnectionName"] as? String ?? "Shurufa_Connection"
_ = IMKServer(name: connectionName, bundleIdentifier: Bundle.main.bundleIdentifier)
NSApplication.shared.run()
