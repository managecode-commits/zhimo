import AVFoundation
import Speech
import UIKit

final class TranscriptionViewController: UIViewController {
    private let textView = UITextView()
    private let recordButton = UIButton(type: .system)
    private let audioEngine = AVAudioEngine()
    private var task: SFSpeechRecognitionTask?
    private var request: SFSpeechAudioBufferRecognitionRequest?

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
        } else {
            requestPermissionsAndStart()
        }
    }

    private func requestPermissionsAndStart() {
        SFSpeechRecognizer.requestAuthorization { [weak self] speechStatus in
            AVAudioSession.sharedInstance().requestRecordPermission { microphoneAllowed in
                DispatchQueue.main.async {
                    guard speechStatus == .authorized, microphoneAllowed else { return }
                    try? self?.startRecording()
                }
            }
        }
    }

    private func startRecording() throws {
        let recognizer = SFSpeechRecognizer(locale: Locale.current)
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        if #available(iOS 13, *), recognizer?.supportsOnDeviceRecognition == true {
            request.requiresOnDeviceRecognition = true
        }
        let node = audioEngine.inputNode
        let format = node.outputFormat(forBus: 0)
        node.installTap(onBus: 0, bufferSize: 1_024, format: format) { buffer, _ in
            request.append(buffer)
        }
        try AVAudioSession.sharedInstance().setCategory(.record, mode: .measurement)
        try AVAudioSession.sharedInstance().setActive(true)
        audioEngine.prepare()
        try audioEngine.start()
        self.request = request
        task = recognizer?.recognitionTask(with: request) { [weak self] result, error in
            if let result { self?.textView.text = result.bestTranscription.formattedString }
            if error != nil || result?.isFinal == true { self?.stopRecording() }
        }
        recordButton.setTitle("停止", for: .normal)
    }

    private func stopRecording() {
        audioEngine.stop()
        audioEngine.inputNode.removeTap(onBus: 0)
        request?.endAudio()
        task?.cancel()
        request = nil
        task = nil
        try? AVAudioSession.sharedInstance().setActive(false)
        recordButton.setTitle("开始听写", for: .normal)
    }
}
