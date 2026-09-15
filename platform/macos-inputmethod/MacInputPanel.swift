// Copyright © 2026 立方田 <managecode@gmail.com>
import AppKit
import AVFoundation
import CryptoKit
import ZhimoCore
import ZhimoSpeech

private final class InputPanelWindow: NSPanel {
    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }
}

private final class InkCanvas: NSView {
    override var isFlipped: Bool { true }
    var strokes: [[CGPoint]] = []
    var changed: (() -> Void)?
    private(set) var drawing = false
    override func mouseDown(with event: NSEvent) {
        guard strokes.count < 64 else { return }
        drawing = true; strokes.append([point(event)]); needsDisplay = true; changed?()
    }
    override func mouseDragged(with event: NSEvent) {
        guard drawing, !strokes.isEmpty, strokes[strokes.count - 1].count < 512 else { return }
        strokes[strokes.count - 1].append(point(event)); needsDisplay = true
    }
    override func mouseUp(with event: NSEvent) {
        guard drawing else { return }
        mouseDragged(with: event); drawing = false; changed?()
    }
    private func point(_ event: NSEvent) -> CGPoint {
        let p = convert(event.locationInWindow, from: nil)
        return CGPoint(x: max(0, min(bounds.width, p.x)), y: max(0, min(bounds.height, p.y)))
    }
    override func draw(_ dirtyRect: NSRect) {
        NSColor.textBackgroundColor.setFill(); bounds.fill()
        NSColor.gridColor.withAlphaComponent(0.3).setStroke()
        let grid = NSBezierPath(); grid.lineWidth = 0.5
        for x in stride(from: CGFloat(20), to: bounds.width, by: 20) { grid.move(to: CGPoint(x: x, y: 0)); grid.line(to: CGPoint(x: x, y: bounds.height)) }
        for y in stride(from: CGFloat(20), to: bounds.height, by: 20) { grid.move(to: CGPoint(x: 0, y: y)); grid.line(to: CGPoint(x: bounds.width, y: y)) }
        grid.stroke(); NSColor.labelColor.withAlphaComponent(0.85).setStroke()
        for stroke in strokes where !stroke.isEmpty {
            let path = NSBezierPath(); path.lineWidth = 3; path.lineCapStyle = .round; path.lineJoinStyle = .round
            path.move(to: stroke[0]); for p in stroke.dropFirst() { path.line(to: p) }
            if stroke.count == 1 { path.line(to: CGPoint(x: stroke[0].x + 0.1, y: stroke[0].y)) }
            path.stroke()
        }
    }
}

final class MacInputPanel: NSWindowController {
    private static var current: MacInputPanel?
    static func isOpen(owner: AnyObject) -> Bool { current?.owner === owner }
    static func dismiss(owner: AnyObject) { if current?.owner === owner { current?.finishClose(); current = nil } }
    static func open(owner: AnyObject, speech: Bool, eligible: @escaping () -> Bool, commit: @escaping (String) -> Void) {
        current?.finishClose()
        let value = MacInputPanel(owner: owner, speech: speech, eligible: eligible, commit: commit)
        current = value; value.show()
    }
    private weak var owner: AnyObject?
    private let eligible: () -> Bool
    private let commit: (String) -> Void
    private let speechMode: Bool
    private let status = NSTextField(wrappingLabelWithString: "")
    private let preview = NSTextField(wrappingLabelWithString: "")
    private let candidateRow = NSStackView()
    private let canvas = InkCanvas(frame: NSRect(x: 0, y: 0, width: 620, height: 280))
    private let worker = DispatchQueue(label: "dev.zhimo.mac.inference")
    private var timer: Timer?
    private var pending: DispatchWorkItem?
    private var revision: UInt64 = 0
    private var closed = false
    private var choices: [String] = []
    private var handModel: OpaquePointer?
    private var audioEngine: AVAudioEngine?
    private let audioLock = NSLock()
    private var samples: [Float] = []
    private var sampleRate: Double = 0
    private var speechSession: UInt64 = 0
    private var stopTimer: Timer?
    private var busy = false
    private var speechText = ""

    init(owner: AnyObject, speech: Bool, eligible: @escaping () -> Bool, commit: @escaping (String) -> Void) {
        self.owner = owner; self.speechMode = speech; self.eligible = eligible; self.commit = commit
        let window = InputPanelWindow(contentRect: NSRect(x: 0, y: 0, width: 660, height: 440), styleMask: [.titled, .nonactivatingPanel], backing: .buffered, defer: false)
        window.title = speech ? "知墨 · 离线语音" : "知墨 · 离线单字手写"
        window.level = .floating; window.hidesOnDeactivate = false; window.isReleasedWhenClosed = false
        super.init(window: window)
        let root = NSStackView(); root.orientation = .vertical; root.spacing = 10
        root.edgeInsets = NSEdgeInsets(top: 12, left: 12, bottom: 12, right: 12)
        root.frame = window.contentView!.bounds; root.autoresizingMask = [.width, .height]
        window.contentView!.addSubview(root)
        status.stringValue = speech ? "点击录音 · 最长 60 秒 · 本地转录后确认上屏" : "一次写一字 · 不必拆开偏旁 · 点选上屏"
        root.addArrangedSubview(status)
        status.widthAnchor.constraint(equalToConstant: 620).isActive = true
        if speech {
            preview.stringValue = "转录结果将在这里显示（只读预览）"
            preview.preferredMaxLayoutWidth = 600
            preview.widthAnchor.constraint(equalToConstant: 620).isActive = true
            root.addArrangedSubview(preview)
            root.addArrangedSubview(row([("开始录音", #selector(record)), ("停止并识别", #selector(stopRecording)), ("确认上屏", #selector(confirmSpeech)), ("关闭", #selector(closePanel))]))
        } else {
            candidateRow.orientation = .horizontal; candidateRow.spacing = 8
            let scroll = NSScrollView(); scroll.hasHorizontalScroller = true; scroll.hasVerticalScroller = false
            scroll.documentView = candidateRow; scroll.heightAnchor.constraint(equalToConstant: 56).isActive = true
            scroll.widthAnchor.constraint(equalToConstant: 620).isActive = true
            root.addArrangedSubview(scroll)
            canvas.widthAnchor.constraint(equalToConstant: 620).isActive = true
            canvas.heightAnchor.constraint(equalToConstant: 280).isActive = true
            root.addArrangedSubview(canvas)
            root.addArrangedSubview(row([("撤一笔", #selector(undo)), ("重写", #selector(clearInk)), ("重新识别", #selector(recognize)), ("关闭", #selector(closePanel))]))
            canvas.changed = { [weak self] in self?.inkChanged() }
        }
    }
    required init?(coder: NSCoder) { fatalError("not supported") }
    deinit { if let handModel { ime_handwriting_free(handModel) } }
    private func row(_ items: [(String, Selector)]) -> NSStackView {
        let row = NSStackView(); row.spacing = 10
        for (title, action) in items { let button = NSButton(title: title, target: self, action: action); button.focusRingType = .none; row.addArrangedSubview(button) }
        return row
    }
    private func show() {
        window?.center(); window?.orderFrontRegardless()
        timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            guard let self else { return }
            if !self.eligible() { self.closePanel() }
        }
    }
    @objc private func closePanel() { finishClose(); if Self.current === self { Self.current = nil } }
    private func finishClose() {
        guard !closed else { return }
        closed = true; revision &+= 1; timer?.invalidate(); stopTimer?.invalidate(); pending?.cancel()
        audioEngine?.stop(); audioEngine?.inputNode.removeTap(onBus: 0); audioEngine = nil
        audioLock.lock(); samples.removeAll(); audioLock.unlock()
        if speechSession != 0 { zhimo_speech_cancel(speechSession) }
        window?.orderOut(nil)
    }
    private func inkChanged() {
        revision &+= 1; choices = []; updateChoices(); pending?.cancel()
        let job = DispatchWorkItem { [weak self] in self?.recognize() }; pending = job
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5, execute: job)
    }
    @objc private func undo() { if !canvas.strokes.isEmpty { canvas.strokes.removeLast() }; canvas.needsDisplay = true; inkChanged() }
    @objc private func clearInk() { canvas.strokes = []; canvas.needsDisplay = true; inkChanged() }
    @objc private func recognize() {
        guard !closed, eligible(), !canvas.drawing, !canvas.strokes.isEmpty else { return }
        pending?.cancel(); let token = revision
        let strokes = canvas.strokes.map { s in s.enumerated().map { i, p -> [String: Any] in ["x": Double(p.x), "y": Double(p.y), "time_ms": i] } }
        let request: [String: Any] = ["width": canvas.bounds.width, "height": canvas.bounds.height, "strokes": strokes]
        guard let data = try? JSONSerialization.data(withJSONObject: request), let json = String(data: data, encoding: .utf8) else { return }
        status.stringValue = "正在离线识别…"
        worker.async { [self] in
            if handModel == nil, let url = Bundle.main.resourceURL?.appendingPathComponent("models/handwriting/zh-cn/handwriting-zh_CN.model") {
                handModel = url.path.withCString { ime_handwriting_new($0) }
            }
            var words: [String] = []
            if let handModel, let raw = json.withCString({ ime_handwriting_recognize_json(handModel, $0) }),
               let decoded = try? JSONSerialization.jsonObject(with: Data(String(cString: raw).utf8)) as? [[String: Any]] { words = decoded.compactMap { $0["text"] as? String } }
            DispatchQueue.main.async { [self] in
                guard !closed, eligible(), revision == token else { return }
                choices = words; updateChoices()
                status.stringValue = words.isEmpty ? "未识别到候选或模型不可用，请重写／检查安装包" : "\(words.count) 个候选 · 横向滚动查看更多"
            }
        }
    }
    private func updateChoices() {
        candidateRow.arrangedSubviews.forEach { candidateRow.removeArrangedSubview($0); $0.removeFromSuperview() }
        for (index, word) in choices.prefix(100).enumerated() {
            let button = NSButton(title: word, target: self, action: #selector(selectInk(_:))); button.tag = index
            button.font = .systemFont(ofSize: 24); candidateRow.addArrangedSubview(button)
        }
        candidateRow.layoutSubtreeIfNeeded(); candidateRow.setFrameSize(candidateRow.fittingSize)
    }
    @objc private func selectInk(_ button: NSButton) {
        guard !closed, eligible(), choices.indices.contains(button.tag) else { return }
        commit(choices[button.tag]); closePanel()
    }
    @objc private func record() {
        guard !closed, !busy, eligible() else { return }
        busy = true; speechText = ""; preview.stringValue = ""
        AVCaptureDevice.requestAccess(for: .audio) { [weak self] allowed in
            DispatchQueue.main.async {
                guard let self, !self.closed, self.eligible() else { return }
                guard allowed else { self.busy = false; self.status.stringValue = "麦克风权限被拒绝，请在系统设置中允许知墨使用麦克风"; return }
                self.beginCapture()
            }
        }
    }
    private func beginCapture() {
        let engine = AVAudioEngine(); let input = engine.inputNode; let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else { busy = false; status.stringValue = "没有可用麦克风"; return }
        audioLock.lock(); samples = []; sampleRate = format.sampleRate; audioLock.unlock()
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
            guard let self, let channels = buffer.floatChannelData else { return }
            self.audioLock.lock(); defer { self.audioLock.unlock() }
            let count = min(Int(buffer.frameLength), max(0, Int(format.sampleRate * 60) - self.samples.count))
            for i in 0..<count {
                var mono: Float = 0
                for channel in 0..<Int(buffer.format.channelCount) { mono += channels[channel][i] }
                self.samples.append(mono / Float(buffer.format.channelCount))
            }
        }
        do { try engine.start(); audioEngine = engine; status.stringValue = "正在录音 · 点击停止并识别" }
        catch { input.removeTap(onBus: 0); busy = false; status.stringValue = "麦克风启动失败：\(error.localizedDescription)"; return }
        stopTimer = Timer.scheduledTimer(withTimeInterval: 60, repeats: false) { [weak self] _ in self?.stopRecording() }
    }
    @objc private func stopRecording() {
        guard let engine = audioEngine else { return }
        engine.stop(); engine.inputNode.removeTap(onBus: 0); audioEngine = nil; stopTimer?.invalidate()
        audioLock.lock(); let input = samples; let rate = sampleRate; samples = []; audioLock.unlock()
        guard input.count > Int(rate / 10) else { busy = false; status.stringValue = "录音太短，请重试"; return }
        let token = revision; let job = zhimo_speech_create(); speechSession = job
        status.stringValue = "正在本地转录…"
        worker.async { [self] in
            defer { zhimo_speech_release(job) }
            var text = ""; var message = "转录失败，请检查模型"
            do {
                let root = Bundle.main.resourceURL!.appendingPathComponent("models/speech")
                let model = root.appendingPathComponent("ggml-base-q5_1.bin")
                let vad = root.appendingPathComponent("ggml-silero-v5.1.2.bin")
                try verify(model, "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898")
                try verify(vad, "29940d98d42b91fbd05ce489f3ecf7c72f0a42f027e4875919a28fb4c04ea2cf")
                let count = min(960000, Int(Double(input.count) * 16000 / rate))
                let pcm: [Float] = (0..<count).map { i in
                    let at = Double(i) * rate / 16000; let lo = min(input.count - 1, Int(at)); let hi = min(input.count - 1, lo + 1)
                    return input[lo] + (input[hi] - input[lo]) * Float(at - Double(lo))
                }
                var output: UnsafeMutablePointer<CChar>?
                let result = pcm.withUnsafeBufferPointer { values in zhimo_speech_transcribe_options(job, model.path, values.baseAddress, values.count, "auto", "", vad.path, &output) }
                if let output { text = String(cString: output); zhimo_speech_text_free(output) }
                message = result == 0 ? (text.isEmpty ? "未检测到语音，请重录" : "请检查结果，确认后上屏") : "转录失败（\(result)）"
            } catch { message = "模型校验失败，请重新安装完整包" }
            DispatchQueue.main.async { [self] in
                guard !closed, eligible(), token == revision else { return }
                speechSession = 0; busy = false; speechText = text; preview.stringValue = text; status.stringValue = message
            }
        }
    }
    private func verify(_ url: URL, _ expected: String) throws {
        let digest = SHA256.hash(data: try Data(contentsOf: url)).map { String(format: "%02x", $0) }.joined()
        if digest != expected { throw NSError(domain: "ZhimoModel", code: 1) }
    }
    @objc private func confirmSpeech() {
        guard !closed, !busy, eligible(), !speechText.isEmpty, speechText.utf8.count <= 16384 else { return }
        commit(speechText); closePanel()
    }
}
