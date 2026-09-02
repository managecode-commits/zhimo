import Foundation
import ShurufaCore

final class ShurufaSession {
    private var handle: OpaquePointer?

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
        guard let handle else { return [] }
        _ = text.withCString { ime_runtime_feed_utf8(handle, $0) }
        return actions(handle)
    }

    func command(_ command: UInt32) -> [Any] {
        guard let handle else { return [] }
        _ = ime_runtime_send_command(handle, command)
        return actions(handle)
    }

    func select(_ id: String) -> [Any] {
        guard let handle else { return [] }
        _ = id.withCString { ime_runtime_select_candidate(handle, $0) }
        return actions(handle)
    }

    func switchEngine(_ id: String) {
        guard let handle else { return }
        _ = id.withCString { ime_runtime_switch_engine(handle, $0) }
    }

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
        return result
    }
}
