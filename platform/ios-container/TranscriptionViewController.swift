// Copyright © 2026 立方田 <managecode@gmail.com>
import AVFoundation
import Speech
import UIKit

final class TranscriptionViewController: UIViewController {
    private let textView = UITextView()
    private let recordButton = UIButton(type: .system)
    private let audioEngine = AVAudioEngine()
    private var task: SFSpeechRecognitionTask?
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var hasTap = false
    private var finishing = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        textView.translatesAutoresizingMaskIntoConstraints = false
        recordButton.translatesAutoresizingMaskIntoConstraints = false
        recordButton.setTitle("开始听写", for: .normal)
        recordButton.addTarget(self, action: #selector(toggleRecording), for: .touchUpInside)
        view.addSubview(textView)
        view.addSubview(recordButton)
        NSLayoutConstraint.activate([
            textView.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 16),
            textView.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -16),
            textView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 16),
            recordButton.topAnchor.constraint(equalTo: textView.bottomAnchor, constant: 12),
            recordButton.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            recordButton.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor, constant: -16)
        ])
    }

    @objc private func toggleRecording() {
        if audioEngine.isRunning {
            stopRecording()
        } else if !finishing {
            requestPermissionsAndStart()
        }
    }

    private func requestPermissionsAndStart() {
        SFSpeechRecognizer.requestAuthorization { [weak self] speechStatus in
            AVAudioSession.sharedInstance().requestRecordPermission { microphoneAllowed in
                DispatchQueue.main.async {
                    guard let self, self.viewIfLoaded?.window != nil else { return }
                    guard speechStatus == .authorized, microphoneAllowed else { return }
                    guard !self.audioEngine.isRunning, !self.finishing else { return }
                    do { try self.startRecording() }
                    catch {
                        self.stopRecording(cancel: true)
                        self.textView.text = "无法启动离线听写，请检查麦克风权限与设备支持情况。"
                    }
                }
            }
        }
    }

    private func startRecording() throws {
        guard #available(iOS 13, *),
              let recognizer = SFSpeechRecognizer(locale: Locale.current),
              recognizer.isAvailable, recognizer.supportsOnDeviceRecognition else {
            textView.text = "当前设备或语言不支持系统离线听写，不会回退到联网识别。"
            return
        }
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        request.requiresOnDeviceRecognition = true
        try AVAudioSession.sharedInstance().setCategory(.record, mode: .measurement)
        try AVAudioSession.sharedInstance().setActive(true)
        let node = audioEngine.inputNode
        let format = node.outputFormat(forBus: 0)
        node.installTap(onBus: 0, bufferSize: 1_024, format: format) { buffer, _ in
            request.append(buffer)
        }
        hasTap = true
        audioEngine.prepare()
        try audioEngine.start()
        self.request = request
        task = recognizer.recognitionTask(with: request) { [weak self] result, error in
            DispatchQueue.main.async {
                guard let self, self.request === request else { return }
                if let result { self.textView.text = result.bestTranscription.formattedString }
                if error != nil || result?.isFinal == true { self.stopRecording(cancel: true) }
            }
        }
        recordButton.setTitle("停止", for: .normal)
    }

    private func stopRecording(cancel: Bool = false) {
        audioEngine.stop()
        if hasTap { audioEngine.inputNode.removeTap(onBus: 0); hasTap = false }
        request?.endAudio()
        if cancel {
            request = nil
            task?.cancel()
            task = nil
            finishing = false
        } else if let pending = request {
            finishing = true
            // Preserve the final result, but bound a recognizer that never finishes.
            DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak self] in
                guard let self, self.request === pending else { return }
                self.stopRecording(cancel: true)
            }
        }
        try? AVAudioSession.sharedInstance().setActive(false)
        recordButton.setTitle(finishing ? "正在完成…" : "开始听写", for: .normal)
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        stopRecording(cancel: true)
    }
}
