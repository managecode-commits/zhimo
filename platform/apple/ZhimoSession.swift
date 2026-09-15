// Copyright © 2026 立方田 <managecode@gmail.com>
import Foundation
import ZhimoCore

final class ZhimoSession {
    private var handle: OpaquePointer?
    private(set) var lastOperationSucceeded = false

    init(engine: String = "bilingual", dataDirectory: URL) {
        handle = engine.withCString { enginePointer in
            dataDirectory.path.withCString { directoryPointer in
                ime_runtime_new_with_data_dir(enginePointer, directoryPointer)
            }
        }
    }

    deinit {
        if let handle {
            _ = ime_runtime_flush(handle)
            ime_runtime_free(handle)
        }
    }

    func feed(_ text: String) -> [Any] {
        lastOperationSucceeded = false
        guard let handle else { return [] }
        guard text.withCString({ ime_runtime_feed_utf8(handle, $0) }) == 0 else { return [] }
        return actions(handle)
    }

    func command(_ command: UInt32) -> [Any] {
        lastOperationSucceeded = false
        guard let handle else { return [] }
        guard ime_runtime_send_command(handle, command) == 0 else { return [] }
        return actions(handle)
    }

    func select(_ id: String) -> [Any] {
        lastOperationSucceeded = false
        guard let handle else { return [] }
        guard id.withCString({ ime_runtime_select_candidate(handle, $0) }) == 0 else { return [] }
        return actions(handle)
    }

    @discardableResult
    func switchEngine(_ id: String) -> Bool {
        guard let handle else { return false }
        return id.withCString { ime_runtime_switch_engine(handle, $0) } == 0
    }

    func setPrivacy(learning: Bool) {
        guard let handle else { return }
        _ = ime_runtime_set_privacy_policy(handle, learning ? 1 : 0, 0)
    }

    func flush() { if let handle { _ = ime_runtime_flush(handle) } }

    func setPasswordScope(_ password: Bool) {
        guard let handle else { return }
        _ = ime_runtime_set_input_scope(handle, password ? 1 : 0)
    }

    private func actions(_ handle: OpaquePointer) -> [Any] {
        guard let pointer = ime_runtime_last_actions_json(handle),
              let data = String(cString: pointer).data(using: .utf8),
              let result = try? JSONSerialization.jsonObject(with: data) as? [Any] else {
            return []
        }
        lastOperationSucceeded = true
        return result
    }
}
