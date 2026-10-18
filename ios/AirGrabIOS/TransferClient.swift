import Foundation
import CryptoKit
import Security

struct TransferOffer: Identifiable {
    let id: String
    let name: String
    let size: Int64
    let mime: String
}

struct PairingInfo {
    let host: String
    let port: UInt16
    let key: Data

    init(link: String) throws {
        guard let components = URLComponents(string: link.trimmingCharacters(in: .whitespacesAndNewlines)),
              components.scheme == "airgrab", components.host == "pair",
              let items = components.queryItems,
              let h = items.first(where: { $0.name == "host" })?.value,
              let p = UInt16(items.first(where: { $0.name == "port" })?.value ?? ""),
              let secret = items.first(where: { $0.name == "key" })?.value else {
            throw AirGrabError.badPairing
        }
        let base64 = secret.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let padded = base64 + String(repeating: "=", count: (4 - base64.count % 4) % 4)
        guard let data = Data(base64Encoded: padded), data.count == 32 else {
            throw AirGrabError.badPairing
        }
        host = h
        port = p
        key = data
    }

    var link: String {
        let keyString = key.base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        return "airgrab://pair?host=" + host + "&port=" + String(port) + "&key=" + keyString
    }
}

enum PairingVault {
    private static let account = "airgrab.pairing.v1"
    static func save(_ pairing: PairingInfo) throws {
        let secret = Data(pairing.link.utf8)
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrAccount as String: account]
        SecItemDelete(query as CFDictionary)
        let attrs = query.merging([kSecValueData as String: secret,
                                   kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]) { _, right in right }
        let result = SecItemAdd(attrs as CFDictionary, nil)
        guard result == errSecSuccess else { throw AirGrabError.badPairing }
    }

    static func load() -> PairingInfo? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword,
                                    kSecAttrAccount as String: account,
                                    kSecReturnData as String: true,
                                    kSecMatchLimit as String: kSecMatchLimitOne]
        var output: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &output) == errSecSuccess,
              let data = output as? Data,
              let raw = String(data: data, encoding: .utf8) else { return nil }
        return try? PairingInfo(link: raw)
    }
}

enum SharedInbox {
    static let group = "group.tw.airgrab.transfer"
    static let key = "pending_file_names"
    static func folder() -> URL? {
        guard let root = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group) else { return nil }
        let path = root.appendingPathComponent("SharedInbox", isDirectory: true)
        try? FileManager.default.createDirectory(at: path, withIntermediateDirectories: true)
        return path
    }
    static func pending() -> [URL] {
        guard let root = folder() else { return [] }
        let names = UserDefaults(suiteName: group)?.stringArray(forKey: key) ?? []
        return names.map { root.appendingPathComponent($0) }
            .filter { FileManager.default.fileExists(atPath: $0.path) }
    }
    static func complete(_ url: URL) {
        guard let defaults = UserDefaults(suiteName: group) else { return }
        var names = defaults.stringArray(forKey: key) ?? []
        names.removeAll { $0 == url.lastPathComponent }
        defaults.set(names, forKey: key)
        try? FileManager.default.removeItem(at: url)
    }
}

@MainActor
final class TransferModel: ObservableObject {
    @Published var pairing: PairingInfo? = PairingVault.load()
    @Published var offers: [TransferOffer] = []
    @Published var inbox: [URL] = []
    @Published var received: [URL] = []
    @Published var status = "請先掃描 S26 Ultra 的配對 QR Code"
    @Published var busy = false
    @Published var progress: Double = 0

    init() { reloadLocal() }

    func reloadLocal() {
        inbox = SharedInbox.pending()
        let dir = receivedFolder()
        received = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []
    }

    func pair(link: String) {
        do {
            let record = try PairingInfo(link: link)
            try PairingVault.save(record)
            pairing = record
            status = "已配對：" + record.host + "（請保持同一區域網路）"
        } catch { status = error.localizedDescription }
    }

    private func session() async throws -> SecureChannel {
        guard let pairing else { throw AirGrabError.badPairing }
        let socket = try SecureChannel(host: pairing.host, port: pairing.port, key: pairing.key)
        try await socket.connect()
        return socket
    }

    func refreshOffers() {
        Task {
            busy = true
            defer { busy = false }
            do {
                let socket = try await session()
                defer { socket.close() }
                try await socket.sendJSON(["op": "list"])
                let response = try await socket.receiveJSON()
                offers = (response["offers"] as? [[String: Any]] ?? []).compactMap { item in
                    guard let id = item["id"] as? String, let name = item["name"] as? String,
                          let mime = item["mime"] as? String,
                          let size = item["size"] as? NSNumber else { return nil }
                    return TransferOffer(id: id, name: name, size: size.int64Value, mime: mime)
                }
                status = "找到 " + String(offers.count) + " 個可接收項目"
            } catch { status = "搜尋失敗：" + error.localizedDescription }
        }
    }

    func download(_ offer: TransferOffer) {
        Task {
            progress = 0
            let start = Date()
            busy = true
            defer { busy = false }
            do {
                let socket = try await session()
                defer { socket.close() }
                try await socket.sendJSON(["op": "get", "id": offer.id])
                let meta = try await socket.receiveJSON()
                guard meta["status"] as? String == "file",
                      let name = meta["name"] as? String,
                      let hex = meta["sha256"] as? String,
                      let number = meta["size"] as? NSNumber,
                      number.int64Value >= 0, number.int64Value <= 10 * 1024 * 1024 * 1024 else {
                    throw AirGrabError.invalidResponse
                }
                let temp = receivedFolder().appendingPathComponent(UUID().uuidString + ".part")
                FileManager.default.createFile(atPath: temp.path, contents: nil)
                let handle = try FileHandle(forWritingTo: temp)
                defer { try? handle.close() }
                var sha = SHA256()
                var remaining = number.int64Value
                do {
                    while remaining > 0 {
                        let (type, bytes) = try await socket.receive()
                        guard type == 2, !bytes.isEmpty, Int64(bytes.count) <= remaining else {
                            throw AirGrabError.invalidFrame
                        }
                        try handle.write(contentsOf: bytes)
                        sha.update(data: bytes)
                        remaining -= Int64(bytes.count)
                        if number.int64Value > 0 {
                            progress = Double(number.int64Value - remaining) / Double(number.int64Value)
                        }
                    }
                    let end = try await socket.receiveJSON()
                    guard end["status"] as? String == "end" else { throw AirGrabError.invalidResponse }
                    let hash = sha.finalize().map { String(format: "%02x", $0) }.joined()
                    guard hash == hex.lowercased() else { throw AirGrabError.integrityFailure }
                    let safe = name.replacingOccurrences(of: "/", with: "_")
                        .replacingOccurrences(of: "\\", with: "_")
                    let final = receivedFolder().appendingPathComponent(UUID().uuidString + "-" + safe)
                    try FileManager.default.moveItem(at: temp, to: final)
                    reloadLocal()
                    let elapsed = max(0.001, Date().timeIntervalSince(start))
                    let speed = Double(number.int64Value) / 1_000_000 / elapsed
                    status = String(format: "已驗證：%@｜平均 %.1f MB/s", safe, speed)
                } catch {
                    try? FileManager.default.removeItem(at: temp)
                    throw error
                }
            } catch { status = "接收失敗：" + error.localizedDescription }
        }
    }

    func upload(_ file: URL, fromShare: Bool = false) {
        Task {
            progress = 0
            let start = Date()
            busy = true
            defer { busy = false }
            do {
                let scope = file.startAccessingSecurityScopedResource()
                defer { if scope { file.stopAccessingSecurityScopedResource() } }
                let size = ((try FileManager.default.attributesOfItem(atPath: file.path))[.size] as? NSNumber)?.int64Value ?? 0
                guard size <= 10 * 1024 * 1024 * 1024 else { throw AirGrabError.tooLarge }
                var sha = SHA256()
                let reader = try FileHandle(forReadingFrom: file)
                while true {
                    let data = try reader.read(upToCount: SecureChannel.chunkSize) ?? Data()
                    if data.isEmpty { break }
                    sha.update(data: data)
                }
                try reader.seek(toOffset: 0)
                let checksum = sha.finalize().map { String(format: "%02x", $0) }.joined()
                let socket = try await session()
                defer { socket.close(); try? reader.close() }
                try await socket.sendJSON(["op": "put", "name": file.lastPathComponent,
                                           "size": size, "sha256": checksum])
                let reply = try await socket.receiveJSON()
                guard reply["status"] as? String == "ready" else { throw AirGrabError.invalidResponse }
                var transmitted: Int64 = 0
                while true {
                    let data = try reader.read(upToCount: SecureChannel.chunkSize) ?? Data()
                    if data.isEmpty { break }
                    try await socket.sendBytes(data)
                    transmitted += Int64(data.count)
                    if size > 0 { progress = Double(transmitted) / Double(size) }
                }
                try await socket.sendJSON(["status": "end"])
                let receipt = try await socket.receiveJSON()
                guard receipt["status"] as? String == "ok" else { throw AirGrabError.integrityFailure }
                if fromShare { SharedInbox.complete(file) }
                reloadLocal()
                let elapsed = max(0.001, Date().timeIntervalSince(start))
                status = String(format: "驗證成功｜平均 %.1f MB/s", Double(size) / 1_000_000 / elapsed)
            } catch { status = "傳送失敗：" + error.localizedDescription }
        }
    }

    func receivedWebLink(from file: URL) -> URL? {
        guard file.lastPathComponent.hasSuffix("-link.txt")
                || file.lastPathComponent.hasSuffix("shared-link.txt"),
              let content = try? String(contentsOf: file, encoding: .utf8),
              let range = content.range(of: "https?://[^\\s<>]+", options: .regularExpression),
              let url = URL(string: String(content[range])),
              url.scheme == "https" || url.scheme == "http" else { return nil }
        return url
    }

    private func receivedFolder() -> URL {
        let base = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let path = base.appendingPathComponent("Received", isDirectory: true)
        try? FileManager.default.createDirectory(at: path, withIntermediateDirectories: true)
        return path
    }
}
