// Copyright © 2026 立方田 <managecode@gmail.com>
import Foundation

@main
struct CoreProbe {
    static func main() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("zhimo-probe-" + UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let session = ZhimoSession(dataDirectory: directory)
        session.setPrivacy(learning: false)
        precondition(session.switchEngine("pinyin.reference"))
        for input in ["nihao", "nh", "wm", "lvdai", "wulianwang"] {
            _ = session.command(3)
            var last: [Any] = []
            for c in input { last = session.feed(String(c)); precondition(session.lastOperationSucceeded) }
            let candidates = last.compactMap { ($0 as? [String: Any])?["ShowCandidates"] as? [[String: Any]] }.flatMap { $0 }
            precondition(!candidates.isEmpty, "missing candidates: \(input)")
            let id = candidates[0]["id"] as! String
            let selected = session.select(id)
            precondition(selected.contains { ($0 as? [String: Any])?["CommitText"] != nil })
        }
        precondition(session.switchEngine("latin"))
        precondition("知墨😀".utf16.count == 4)
        session.flush()
        print("PASS: Swift/C ABI, default Pinyin selection, full/abbreviated input, UTF-16 (not IMK UI validation)")
    }
}
