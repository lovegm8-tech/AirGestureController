import UIKit
import UniformTypeIdentifiers

/**
 * iOS Share Extension stages raw representations made available by the source app.
 * The user then opens AirGrabIOS and confirms sending. No background camera access.
 */
final class ShareViewController: UIViewController {
    private let statusLabel = UILabel()
    private let closeButton = UIButton(type: .system)
    private let group = "group.tw.airgrab.transfer"
    private let pendingKey = "pending_file_names"

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        statusLabel.text = "正在取得分享內容…"
        statusLabel.numberOfLines = 0
        statusLabel.textAlignment = .center
        closeButton.setTitle("完成", for: .normal)
        closeButton.isEnabled = false
        closeButton.addTarget(self, action: #selector(close), for: .touchUpInside)
        let stack = UIStackView(arrangedSubviews: [statusLabel, closeButton])
        stack.axis = .vertical
        stack.spacing = 24
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 20),
            stack.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -20),
            stack.centerYAnchor.constraint(equalTo: view.centerYAnchor)
        ])
        stage()
    }

    private func stage() {
        guard let root = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) else {
            statusLabel.text = "App Group 尚未設定，無法與主 App 共用檔案"
            closeButton.isEnabled = true
            return
        }
        let folder = root.appendingPathComponent("SharedInbox", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? [])
            .flatMap { $0.attachments ?? [] }
        let group = DispatchGroup()
        let lock = NSLock()
        var names = [String]()

        for provider in providers {
            let types = provider.registeredTypeIdentifiers
            let fileType = types.first { identifier in
                guard let type = UTType(identifier) else { return false }
                return type.conforms(to: .image) || type.conforms(to: .movie)
                    || type.conforms(to: .pdf) || type.conforms(to: .data)
            }
            if let type = fileType {
                group.enter()
                provider.loadFileRepresentation(forTypeIdentifier: type) { input, _ in
                    defer { group.leave() }
                    guard let input else { return }
                    let ext = input.pathExtension.isEmpty
                        ? (UTType(type)?.preferredFilenameExtension ?? "bin") : input.pathExtension
                    let name = UUID().uuidString + "." + ext
                    let dest = folder.appendingPathComponent(name)
                    if (try? FileManager.default.copyItem(at: input, to: dest)) != nil {
                        lock.lock(); names.append(name); lock.unlock()
                    }
                }
            } else if provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
                group.enter()
                provider.loadItem(forTypeIdentifier: UTType.url.identifier, options: nil) { item, _ in
                    defer { group.leave() }
                    guard let url = item as? URL ?? URL(string: item as? String ?? "") else { return }
                    let name = UUID().uuidString + "-link.txt"
                    if (try? Data(url.absoluteString.utf8).write(to: folder.appendingPathComponent(name))) != nil {
                        lock.lock(); names.append(name); lock.unlock()
                    }
                }
            } else if provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
                group.enter()
                provider.loadItem(forTypeIdentifier: UTType.plainText.identifier, options: nil) { item, _ in
                    defer { group.leave() }
                    guard let text = item as? String else { return }
                    let name = UUID().uuidString + "-text.txt"
                    if (try? Data(text.utf8).write(to: folder.appendingPathComponent(name))) != nil {
                        lock.lock(); names.append(name); lock.unlock()
                    }
                }
            }
        }
        group.notify(queue: .main) {
            if let defaults = UserDefaults(suiteName: self.group) {
                var old = defaults.stringArray(forKey: self.pendingKey) ?? []
                old.append(contentsOf: names)
                defaults.set(Array(Set(old)), forKey: self.pendingKey)
            }
            self.statusLabel.text = names.isEmpty ? "來源 App 沒有提供可分享的檔案或連結"
                : "已儲存 " + String(names.count) + " 個項目。請開啟「隔空抓取」App，選擇傳送至 Android。"
            self.closeButton.isEnabled = true
        }
    }

    @objc private func close() {
        extensionContext?.completeRequest(returningItems: [], completionHandler: nil)
    }
}
