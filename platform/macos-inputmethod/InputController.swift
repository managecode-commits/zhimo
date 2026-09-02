import Cocoa
import InputMethodKit

final class InputController: IMKInputController {
    private let session: ShurufaSession

    override init!(server: IMKServer!, delegate: Any!, client inputClient: Any!) {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Shurufa", isDirectory: true)
        try? FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
        session = ShurufaSession(dataDirectory: support)
        super.init(server: server, delegate: delegate, client: inputClient)
    }

    override func inputText(_ string: String!, client sender: Any!) -> Bool {
        apply(session.feed(string), to: sender as? IMKTextInput)
        return true
    }

    override func didCommand(by aSelector: Selector!, client sender: Any!) -> Bool {
        let command: UInt32?
        switch NSStringFromSelector(aSelector) {
        case "deleteBackward:": command = 0
        case "insertNewline:": command = 1
        case "cancelOperation:": command = 3
        case "moveLeft:": command = 4
        case "moveRight:": command = 5
        default: command = nil
        }
        guard let command else { return false }
        apply(session.command(command), to: sender as? IMKTextInput)
        return true
    }

    private func apply(_ actions: [Any], to client: IMKTextInput?) {
        for item in actions {
            if let name = item as? String, name == "CloseComposition" {
                client?.setMarkedText("", selectionRange: NSRange(location: 0, length: 0), replacementRange: NSRange(location: NSNotFound, length: NSNotFound))
                continue
            }
            guard let action = item as? [String: Any] else { continue }
            if let text = action["CommitText"] as? String {
                client?.insertText(text, replacementRange: NSRange(location: NSNotFound, length: NSNotFound))
            } else if let composition = action["UpdateComposition"] as? [String: Any],
                      let segments = composition["segments"] as? [[String: Any]] {
                let text = segments.compactMap { $0["text"] as? String }.joined()
                client?.setMarkedText(text, selectionRange: NSRange(location: text.utf16.count, length: 0), replacementRange: NSRange(location: NSNotFound, length: NSNotFound))
            }
        }
    }
}
