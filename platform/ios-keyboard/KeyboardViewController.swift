import UIKit

final class KeyboardViewController: UIInputViewController {
    private lazy var session: ShurufaSession = {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: support, withIntermediateDirectories: true)
        return ShurufaSession(dataDirectory: support)
    }()
    private let candidateRow = UIStackView()
    private let preedit = UILabel()
    private var composing = false
    private var pinyin = false

    override func viewDidLoad() {
        super.viewDidLoad()
        let root = UIStackView()
        root.axis = .vertical
        root.translatesAutoresizingMaskIntoConstraints = false
        candidateRow.axis = .horizontal
        root.addArrangedSubview(preedit)
        root.addArrangedSubview(candidateRow)
        for row in ["qwertyuiop", "asdfghjkl", "zxcvbnm"] {
            let keys = UIStackView()
            keys.distribution = .fillEqually
            row.forEach { keys.addArrangedSubview(button(String($0), #selector(letter(_:)))) }
            root.addArrangedSubview(keys)
        }
        let commands = UIStackView()
        commands.distribution = .fillEqually
        commands.addArrangedSubview(button("🌐", #selector(advanceToNextInputMode)))
        commands.addArrangedSubview(button("中/英", #selector(toggleLanguage)))
        commands.addArrangedSubview(button("Space", #selector(space)))
        commands.addArrangedSubview(button("⌫", #selector(backspace)))
        root.addArrangedSubview(commands)
        view.addSubview(root)
        NSLayoutConstraint.activate([
            root.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            root.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            root.topAnchor.constraint(equalTo: view.topAnchor),
            root.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
    }

    private func button(_ title: String, _ action: Selector) -> UIButton {
        let value = UIButton(type: .system)
        value.setTitle(title, for: .normal)
        value.addTarget(self, action: action, for: .touchUpInside)
        return value
    }

    @objc private func letter(_ sender: UIButton) {
        apply(session.feed(sender.currentTitle ?? ""))
    }

    @objc private func space() {
        if composing { apply(session.command(2)) }
        else { textDocumentProxy.insertText(" ") }
    }
    @objc private func backspace() {
        if composing { apply(session.command(0)) }
        else { textDocumentProxy.deleteBackward() }
    }
    @objc private func toggleLanguage() {
        if composing { apply(session.command(2)) }
        pinyin.toggle()
        session.switchEngine(pinyin ? "pinyin.reference" : "latin")
        composing = false
        preedit.text = ""
        candidateRow.arrangedSubviews.forEach { $0.removeFromSuperview() }
    }

    private func apply(_ actions: [Any]) {
        candidateRow.arrangedSubviews.forEach { $0.removeFromSuperview() }
        for item in actions {
            if let name = item as? String, name == "CloseComposition" {
                composing = false
                preedit.text = ""
            }
            if let action = item as? [String: Any] {
                if let text = action["CommitText"] as? String {
                    textDocumentProxy.insertText(text)
                    composing = false
                    preedit.text = ""
                }
                if let composition = action["UpdateComposition"] as? [String: Any],
                   let segments = composition["segments"] as? [[String: Any]] {
                    let text = segments.compactMap { $0["text"] as? String }.joined()
                    preedit.text = text
                    composing = !text.isEmpty
                }
                if let values = action["ShowCandidates"] as? [[String: Any]] {
                    for value in values.prefix(5) {
                        guard let title = value["display_text"] as? String,
                              let id = value["id"] as? String else { continue }
                        let candidate = UIButton(type: .system)
                        candidate.setTitle(title, for: .normal)
                        candidate.addAction(UIAction { [weak self] _ in self?.apply(self?.session.select(id) ?? []) }, for: .touchUpInside)
                        candidateRow.addArrangedSubview(candidate)
                    }
                }
            }
        }
    }
}
