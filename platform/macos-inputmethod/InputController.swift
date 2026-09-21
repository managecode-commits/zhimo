// Copyright © 2026 立方田 <managecode@gmail.com>
import Cocoa
import InputMethodKit
import Carbon

@objc(ZhimoInputController)
final class InputController: IMKInputController {
    private let session: ZhimoSession
    private var candidateWindow: IMKCandidates!
    private var choices: [[String: Any]] = []
    private var page = 0
    private var selectedIndex = 0
    private var composing = false
    private var active = false
    private var pinyin = true
    private var capsLock = false
    private var generation: UInt64 = 0
    private var hostPID: pid_t = 0
    private weak var targetClient: AnyObject?
    private var eligible: Bool {
        active && !IsSecureEventInputEnabled() && NSWorkspace.shared.frontmostApplication?.processIdentifier == hostPID
    }

    override init!(server: IMKServer!, delegate: Any!, client inputClient: Any!) {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Zhimo", isDirectory: true)
        try? FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
        session = ZhimoSession(dataDirectory: support)
        super.init(server: server, delegate: delegate, client: inputClient)
        pinyin = session.switchEngine("pinyin.reference")
        session.setPrivacy(learning: UserDefaults.standard.bool(forKey: "LearningEnabled"))
        candidateWindow = IMKCandidates(server: server, panelType: kIMKSingleColumnScrollingCandidatePanel)
    }

    override func activateServer(_ sender: Any!) {
        capsLock = NSEvent.modifierFlags.contains(.capsLock)
        active = true; generation &+= 1
        targetClient = sender as AnyObject?
        hostPID = NSWorkspace.shared.frontmostApplication?.processIdentifier ?? 0
        session.setPrivacy(learning: UserDefaults.standard.bool(forKey: "LearningEnabled"))
        session.setPasswordScope(IsSecureEventInputEnabled())
    }
    override func deactivateServer(_ sender: Any!) {
        cancel(to: sender as? IMKTextInput)
        active = false; generation &+= 1; targetClient = nil
        session.flush()
    }
    override func recognizedEvents(_ sender: Any!) -> Int {
        Int(NSEvent.EventTypeMask.keyDown.rawValue | NSEvent.EventTypeMask.flagsChanged.rawValue)
    }
    override func handle(_ event: NSEvent!, client sender: Any!) -> Bool {
        guard let event, let client = sender as? IMKTextInput else { return false }
        session.setPasswordScope(IsSecureEventInputEnabled())
        guard eligible else { cancel(to: client); return false }
        let flags = event.modifierFlags.intersection(.deviceIndependentFlagsMask)
        capsLock = flags.contains(.capsLock)
        if event.type == .flagsChanged {
            if capsLock && composing { _ = finishRaw(to: client) }
            return false
        }
        guard event.type == .keyDown else { return false }
        if flags.contains(.command) || flags.contains(.option) { return false }
        if flags.contains(.control) && flags.contains(.shift) {
            if event.keyCode == 4 { openPanel(speech: false); return true }
            if event.keyCode == 109 { openPanel(speech: true); return true }
        }
        if flags.contains(.control) { return false }
        if event.keyCode == 49 && flags.contains(.shift) { toggleLanguage(); return true }
        MacInputPanel.dismiss(owner: self)
        guard pinyin else { return false }
        let text = event.characters ?? ""
        let asciiLetter = !text.isEmpty && text.unicodeScalars.allSatisfy {
            (65...90).contains($0.value) || (97...122).contains($0.value)
        }
        if capsLock || (flags.contains(.shift) && asciiLetter) {
            return !finishRaw(to: client)
        }
        if composing, let n = Int(text), (1...9).contains(n), page * 9 + n - 1 < choices.count {
            choose(page * 9 + n - 1); return true
        }
        if composing && (event.keyCode == 116 || event.keyCode == 121) {
            page = max(0, min((choices.count - 1) / 9, page + (event.keyCode == 116 ? -1 : 1)))
            selectedIndex = page * 9
            candidateWindow.update(); candidateWindow.show(kIMKLocateCandidatesBelowHint); return true
        }
        if composing && !choices.isEmpty && (event.keyCode == 125 || event.keyCode == 126) {
            candidateWindow.interpretKeyEvents([event]); return true
        }
        if composing && event.keyCode == 49 && !choices.isEmpty {
            choose(selectedIndex); return true
        }
        let commands: [UInt16: UInt32] = [51: 0, 36: 1, 76: 1, 53: 3, 123: 4, 124: 5, 49: 2]
        if let command = commands[event.keyCode] {
            guard composing else { return false }
            let actions = session.command(command)
            guard session.lastOperationSucceeded else { return false }
            apply(actions, to: client); return true
        }
        guard !text.isEmpty, text.unicodeScalars.allSatisfy({ !CharacterSet.controlCharacters.contains($0) }) else { return false }
        if !composing && !text.unicodeScalars.allSatisfy({ CharacterSet.letters.contains($0) }) { return false }
        return inputText(text, client: sender)
    }
    override func candidates(_ sender: Any!) -> [Any]! {
        Array(choices.dropFirst(page * 9).prefix(9)).map { NSAttributedString(string: $0["display_text"] as? String ?? "") }
    }
    override func candidateSelected(_ candidateString: NSAttributedString!) {
        guard eligible, let text = candidateString?.string,
              let index = choices.dropFirst(page * 9).prefix(9).firstIndex(where: { $0["display_text"] as? String == text }) else { return }
        choose(index)
    }
    override func candidateSelectionChanged(_ candidateString: NSAttributedString!) {
        guard let text = candidateString?.string,
              let index = choices.dropFirst(page * 9).prefix(9).firstIndex(where: { $0["display_text"] as? String == text }) else { return }
        selectedIndex = index
    }
    private func choose(_ index: Int) {
        guard eligible, choices.indices.contains(index), let id = choices[index]["id"] as? String else { return }
        apply(session.select(id), to: targetClient as? IMKTextInput)
    }
    private func cancel(to client: IMKTextInput?) {
        MacInputPanel.dismiss(owner: self)
        if composing { apply(session.command(3), to: client) }
        composing = false; choices = []; candidateWindow?.hide()
    }
    override func menu() -> NSMenu! {
        let menu = NSMenu(title: "知墨")
        capsLock = NSEvent.modifierFlags.contains(.capsLock)
        if capsLock {
            let status = NSMenuItem(title: "A · 大写锁定：英文直输，关闭 Caps Lock 恢复原模式", action: nil, keyEquivalent: "")
            status.isEnabled = false
            menu.addItem(status)
        }
        for (title, action) in [(pinyin ? "中文拼音 ✓ · 切换英文" : "英文直输 ✓ · 切换中文", #selector(toggleLanguage)),
                                ("离线单字手写", #selector(handwriting)), ("离线语音", #selector(speech)),
                                (UserDefaults.standard.bool(forKey: "LearningEnabled") ? "关闭本地词频学习" : "启用本地词频学习（默认关闭）", #selector(toggleLearning))] {
            let item = NSMenuItem(title: title, action: action, keyEquivalent: ""); item.target = self; menu.addItem(item)
        }
        return menu
    }
    @objc private func toggleLanguage() {
        guard eligible else { return }
        cancel(to: targetClient as? IMKTextInput)
        if session.switchEngine(pinyin ? "latin" : "pinyin.reference") { pinyin.toggle() }
    }
    @objc private func toggleLearning() {
        let value = !UserDefaults.standard.bool(forKey: "LearningEnabled")
        UserDefaults.standard.set(value, forKey: "LearningEnabled"); session.setPrivacy(learning: value)
    }
    @objc private func handwriting() { openPanel(speech: false) }
    @objc private func speech() { openPanel(speech: true) }
    private func openPanel(speech: Bool) {
        guard eligible else { return }
        if MacInputPanel.isOpen(owner: self) { MacInputPanel.dismiss(owner: self); return }
        cancel(to: targetClient as? IMKTextInput)
        let token = generation
        MacInputPanel.open(owner: self, speech: speech, eligible: { [weak self] in self?.eligible == true && self?.generation == token }, commit: { [weak self] text in
            guard let self, self.eligible, self.generation == token, let client = self.targetClient as? IMKTextInput else { return }
            client.insertText(text, replacementRange: NSRange(location: NSNotFound, length: 0))
        })
    }

    override func inputText(_ string: String!, client sender: Any!) -> Bool {
        guard eligible, pinyin else { return false }
        guard let string, let client = sender as? IMKTextInput else { return false }
        if NSEvent.modifierFlags.contains(.capsLock) || string.unicodeScalars.contains(where: { (65...90).contains($0.value) }) {
            return !finishRaw(to: client)
        }
        let actions = string == " " ? session.command(2) : session.feed(string)
        guard session.lastOperationSucceeded, !actions.isEmpty,
              !actions.contains(where: { ($0 as? String) == "Ignored" }) else { return false }
        apply(actions, to: client)
        return true
    }

    private func finishRaw(to client: IMKTextInput) -> Bool {
        guard composing else { return true }
        let actions = session.command(1)
        guard session.lastOperationSucceeded else { return false }
        apply(actions, to: client)
        return true
    }

    override func didCommand(by aSelector: Selector!, client sender: Any!) -> Bool {
        guard eligible, composing else { return false }
        let command: UInt32?
        switch NSStringFromSelector(aSelector) {
        case "deleteBackward:": command = 0
        case "insertNewline:": command = 1
        case "cancelOperation:": command = 3
        case "moveLeft:": command = 4
        case "moveRight:": command = 5
        default: command = nil
        }
        guard let command, let client = sender as? IMKTextInput else { return false }
        let actions = session.command(command)
        guard session.lastOperationSucceeded, !actions.isEmpty,
              !actions.contains(where: { ($0 as? String) == "Ignored" }) else { return false }
        apply(actions, to: client)
        return true
    }

    private func apply(_ actions: [Any], to client: IMKTextInput?) {
        for item in actions {
            if let name = item as? String, name == "CloseComposition" {
                composing = false; choices = []; candidateWindow.hide()
                client?.setMarkedText("", selectionRange: NSRange(location: 0, length: 0), replacementRange: NSRange(location: NSNotFound, length: NSNotFound))
                continue
            }
            guard let action = item as? [String: Any] else { continue }
            if let text = action["CommitText"] as? String {
                client?.insertText(text, replacementRange: NSRange(location: NSNotFound, length: NSNotFound))
            } else if let composition = action["UpdateComposition"] as? [String: Any],
                      let segments = composition["segments"] as? [[String: Any]] {
                let text = segments.compactMap { $0["text"] as? String }.joined()
                composing = !text.isEmpty
                client?.setMarkedText(text, selectionRange: NSRange(location: text.utf16.count, length: 0), replacementRange: NSRange(location: NSNotFound, length: NSNotFound))
            } else if let values = action["ShowCandidates"] as? [[String: Any]] {
                choices = values; page = 0; selectedIndex = 0; candidateWindow.update()
                if values.isEmpty { candidateWindow.hide() } else { candidateWindow.show(kIMKLocateCandidatesBelowHint) }
            }
        }
    }
}
