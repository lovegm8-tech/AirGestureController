import Foundation
import SwiftUI
import CryptoKit
import Security
import Combine

struct OutgoingItem: Identifiable {
    let id: UUID
    let file: URL
    let displayName: String
    let fromShare: Bool
    init(file: URL, name: String, fromShare: Bool) {
        self.id = UUID()
        self.file = file
        self.displayName = name
        self.fromShare = fromShare
    }
}

/// App Group mailbox shared by the separate iOS Share Extension.
enum SharedInbox {
    static let group = "group.tw.airgrab.transfer"
    static let key = "pending_file_names"

    static func folder() -> URL? {
        guard let dir = FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: group) else { return nil }
        let result = dir.appendingPathComponent("SharedInbox", isDirectory: true)
        try? FileManager.default.createDirectory(at: result, withIntermediateDirectories: true)
        return result
    }

    static func pending() -> [URL] {
        guard let root = folder() else { return [] }
        let names = UserDefaults(suiteName: group)?.stringArray(forKey: key) ?? []
        return names.prefix(32).map { root.appendingPathComponent($0) }
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

/// Small Keychain trust-list; keyed by Android stable public-key fingerprint.
/// A never-seen key requires a visible confirmation before LAN connection.
enum BridgeTrust {
    private static let account = "tw.airgrab.ios.trusted-p256.v1"

    static func fingerprints() -> [String] {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var data: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &data) == errSecSuccess,
              let bytes = data as? Data,
              let list = try? JSONDecoder().decode([String].self, from: bytes) else { return [] }
        return list.filter { $0.count == 64 }
    }

    static func approve(_ fingerprint: String) throws {
        var keys = fingerprints()
        if !keys.contains(fingerprint) { keys.append(fingerprint) }
        let bytes = try JSONEncoder().encode(keys)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: account
        ]
        let result = SecItemUpdate(query as CFDictionary,
                                   [kSecValueData as String: bytes] as CFDictionary)
        if result == errSecSuccess { return }
        guard result == errSecItemNotFound else { throw BridgeError.badHandshake }
        let attrs = query.merging([
            kSecValueData as String: bytes,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        ]) { _, right in right }
        guard SecItemAdd(attrs as CFDictionary, nil) == errSecSuccess else {
            throw BridgeError.badHandshake
        }
    }
}

@MainActor
final class TransferModel: ObservableObject {
    @Published var status = "先選擇分享內容，再使用 NFC 碰觸 Android"
    @Published var busy = false
    @Published var progress: Double = 0
    @Published var outgoing: [OutgoingItem] = []
    @Published var received: [URL] = []
    @Published var receivedLink: URL?
    @Published var typedLink = ""
    @Published var sendingURL = false
    @Published var awaitingTrust = false
    @Published var pendingFingerprint = ""
    @Published var recognizedIdentity = false

    private var reader: BridgeNFCReader?
    private var pending: BridgeNFCHandshake?
    private var requestSend = true

    init() { reloadShared(); reloadReceived() }

    private func safeName(_ string: String) -> String {
        // Matches Android TransferPolicy.safeName, including the 120-char bound.
        let component = string.replacingOccurrences(of: "\\", with: "/")
            .components(separatedBy: "/").last ?? "file"
        let noControl = String(component.unicodeScalars.map {
            CharacterSet.controlCharacters.contains($0) ? "_" : String($0)
        }.joined())
        let trimmed = String(noControl.trimmingCharacters(in: .whitespacesAndNewlines).prefix(120))
        return trimmed.isEmpty || trimmed == "." || trimmed == ".." ? "file" : trimmed
    }

    private func displayName(_ url: URL) -> String {
        let original = url.lastPathComponent
        // The Share Extension prefixes a unique UUID to avoid collisions.
        if let range = original.range(of:
            #"^[0-9A-Fa-f-]{36}-"#, options: .regularExpression) {
            return safeName(String(original[range.upperBound...]))
        }
        return safeName(original)
    }

    func addFiles(_ files: [URL]) {
        guard !files.isEmpty, files.count + outgoing.count <= 32 else {
            status = "每次最多選擇 32 個檔案"
            return
        }
        outgoing.append(contentsOf: files.map {
            OutgoingItem(file: $0, name: displayName($0), fromShare: false)
        })
        sendingURL = false
        status = "已選取 \(outgoing.count) 個檔案。請在 Android 開啟 iPhone NFC 接收頁面"
    }

    func reloadShared() {
        // Do not drop existing picker selections when importing share-extension items.
        outgoing.removeAll { $0.fromShare }
        outgoing.append(contentsOf: SharedInbox.pending().prefix(max(0, 32 - outgoing.count))
            .map { OutgoingItem(file: $0, name: displayName($0), fromShare: true) })
    }

    func clearOutgoing() {
        outgoing.removeAll()
        status = "傳送清單已清除"
    }

    func reloadReceived() {
        let dir = receivedDirectory()
        received = ((try? FileManager.default.contentsOfDirectory(
            at: dir, includingPropertiesForKeys: nil)) ?? [])
            .filter { !$0.lastPathComponent.hasPrefix(".") }
            .sorted { $0.lastPathComponent < $1.lastPathComponent }
    }

    func startNFC(send: Bool) {
        guard !busy else { return }
        if send && sendingURL {
            guard validURL(typedLink) != nil else {
                status = "請填入有效的 http 或 https 網址"
                return
            }
        } else if send && (outgoing.isEmpty || outgoing.count > 32) {
            status = "請先從「檔案」或其他 App 分享選取 1～32 個檔案"
            return
        }
        requestSend = send
        busy = true
        progress = 0
        receivedLink = nil
        status = "開啟 NFC 掃描，請靠近 Android 已待命的手機"
        reader = BridgeNFCReader { [weak self] result in
            guard let self else { return }
            self.reader = nil
            switch result {
            case .failure(let e):
                self.busy = false
                self.status = "NFC 掃描失敗：" + e.localizedDescription
            case .success(let hs):
                self.pending = hs
                let fingerprints = BridgeTrust.fingerprints()
                if fingerprints.contains(hs.fingerprint) {
                    self.recognizedIdentity = true
                    self.exchange(hs)
                } else {
                    self.recognizedIdentity = false
                    self.pendingFingerprint = hs.fingerprint
                    self.awaitingTrust = true
                    self.status = fingerprints.isEmpty
                        ? "首次 NFC 配對：確認裝置指紋後才能連線"
                        : "偵測到尚未信任的 Android 金鑰：請確認是否為原本裝置"
                }
            }
        }
        reader?.begin()
    }

    func approveIdentity() {
        guard let pending else { return }
        do {
            try BridgeTrust.approve(pending.fingerprint)
            awaitingTrust = false
            recognizedIdentity = true
            exchange(pending)
        } catch {
            busy = false
            status = "儲存信任裝置失敗：" + error.localizedDescription
        }
    }

    func denyIdentity() {
        pending = nil
        awaitingTrust = false
        busy = false
        status = "已取消陌生 NFC 裝置配對"
    }

    private func exchange(_ hs: BridgeNFCHandshake) {
        Task {
            defer { busy = false; pending = nil }
            do {
                status = "NFC 驗證成功，連接 \(hs.host):\(hs.port)…"
                let socket = try BridgeWire(hs)
                defer { socket.close() }
                try await socket.open()
                let hello = try await socket.readJSON(expected: "hello")
                let role = hello["role"] as? String
                let major = (hello["major"] as? NSNumber)?.intValue
                let sidHex = hs.sid.map { String(format: "%02x", $0) }.joined()
                guard major == 1, hello["sid"] as? String == sidHex,
                      (role == "android_sender" || role == "android_receiver"),
                      (role == "android_receiver") == requestSend else {
                    throw BridgeError.badProtocol
                }
                try await socket.sendJSON(["type": "ready", "major": 1, "minor": 0])
                if requestSend {
                    if sendingURL {
                        try await sendLink(socket)
                    } else {
                        try await sendFiles(socket)
                    }
                } else {
                    try await receiveOffer(socket)
                }
            } catch {
                status = "傳輸失敗：" + error.localizedDescription
            }
        }
    }

    private func validURL(_ raw: String) -> URL? {
        let str = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let url = URL(string: str),
              (url.scheme == "https" || url.scheme == "http"),
              url.host != nil, url.user == nil, url.password == nil,
              str.utf8.count < 16384 else { return nil }
        return url
    }

    private func sendLink(_ socket: BridgeWire) async throws {
        guard let url = validURL(typedLink) else { throw BridgeError.invalidFile }
        try await socket.sendJSON(["type": "offer", "mode": "url",
                                   "url": url.absoluteString])
        _ = try await socket.readJSON(expected: "accept")
        try await socket.sendJSON(["type": "complete"])
        _ = try await socket.readJSON(expected: "complete_ok")
        status = "網址已傳送至 Android，等待對方選擇是否開啟"
    }

    private struct FileMeta {
        let original: OutgoingItem
        let name: String
        let size: Int64
        let hash: String
    }

    private func metadata(for entry: OutgoingItem) throws -> FileMeta {
        let scope = entry.file.startAccessingSecurityScopedResource()
        defer { if scope { entry.file.stopAccessingSecurityScopedResource() } }
        let name = safeName(entry.displayName)
        guard name == entry.displayName else { throw BridgeError.invalidFile }
        let handle = try FileHandle(forReadingFrom: entry.file)
        defer { try? handle.close() }
        var sha = SHA256()
        var size: Int64 = 0
        while true {
            let chunk = try handle.read(upToCount: BridgeWire.chunkBytes) ?? Data()
            if chunk.isEmpty { break }
            let amount = Int64(chunk.count)
            guard size <= Int64.max / 4 - amount else { throw BridgeError.invalidFile }
            size += amount
            sha.update(data: chunk)
        }
        guard size > 0 else { throw BridgeError.invalidFile }
        return FileMeta(original: entry, name: name, size: size,
            hash: sha.finalize().map { String(format: "%02x", $0) }.joined())
    }

    private func sendFiles(_ socket: BridgeWire) async throws {
        let entries = outgoing
        guard entries.count in 1...32 else { throw BridgeError.noSelection }
        status = "計算 \(entries.count) 個檔案的 SHA-256…"
        let metadata = try entries.map { try self.metadata(for: $0) }
        try await socket.sendJSON([
            "type": "offer", "mode": "files",
            "files": metadata.map { [
                "name": $0.name, "size": $0.size, "sha256": $0.hash
            ] as [String: Any] }
        ])
        _ = try await socket.readJSON(expected: "accept")
        for (index, meta) in metadata.enumerated() {
            status = "傳送原始檔 \(index + 1)/\(metadata.count)：\(meta.name)"
            try await socket.sendJSON(["type": "file_start", "index": index])
            let scope = meta.original.file.startAccessingSecurityScopedResource()
            defer { if scope { meta.original.file.stopAccessingSecurityScopedResource() } }
            let reader = try FileHandle(forReadingFrom: meta.original.file)
            var count: Int64 = 0
            var hash = SHA256()
            defer { try? reader.close() }
            while true {
                let chunk = try reader.read(upToCount: BridgeWire.chunkBytes) ?? Data()
                if chunk.isEmpty { break }
                guard count <= meta.size - Int64(chunk.count) else {
                    throw BridgeError.integrityFailure
                }
                hash.update(data: chunk)
                try await socket.send(2, body: chunk)
                count += Int64(chunk.count)
                progress = Double(count) / Double(meta.size)
            }
            let actual = hash.finalize().map { String(format: "%02x", $0) }.joined()
            guard count == meta.size && actual == meta.hash else {
                throw BridgeError.integrityFailure
            }
            try await socket.sendJSON(["type": "file_end", "index": index])
            let ok = try await socket.readJSON(expected: "file_ok")
            guard (ok["index"] as? NSNumber)?.intValue == index else {
                throw BridgeError.badProtocol
            }
        }
        try await socket.sendJSON(["type": "complete"])
        _ = try await socket.readJSON(expected: "complete_ok")
        for meta in metadata where meta.original.fromShare {
            SharedInbox.complete(meta.original.file)
        }
        reloadShared()
        status = "已成功傳送 \(metadata.count) 個原始檔案，Android 已核對 SHA-256"
    }

    private func receivedDirectory() -> URL {
        let base = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let dest = base.appendingPathComponent("Received", isDirectory: true)
        try? FileManager.default.createDirectory(at: dest, withIntermediateDirectories: true)
        return dest
    }

    private struct Expected {
        let name: String
        let size: Int64
        let sha: String
    }

    private func receiveOffer(_ socket: BridgeWire) async throws {
        let offer = try await socket.readJSON(expected: "offer")
        switch offer["mode"] as? String {
        case "url":
            guard let raw = offer["url"] as? String,
                  let url = validURL(raw) else { throw BridgeError.invalidFile }
            try await socket.sendJSON(["type": "accept"])
            _ = try await socket.readJSON(expected: "complete")
            try await socket.sendJSON(["type": "complete_ok"])
            receivedLink = url
            status = "已收到網址，請點擊下方連結自行開啟"
        case "files":
            guard let items = offer["files"] as? [[String: Any]],
                  items.count in 1...32 else { throw BridgeError.invalidFile }
            let expected: [Expected] = try items.map { item in
                guard let name = item["name"] as? String,
                      name == safeName(name),
                      let size = (item["size"] as? NSNumber)?.int64Value,
                      size > 0 && size <= Int64.max / 4,
                      let hash = item["sha256"] as? String,
                      hash.range(of: "^[0-9a-f]{64}$",
                          options: .regularExpression) != nil else {
                    throw BridgeError.invalidFile
                }
                return Expected(name: name, size: size, sha: hash)
            }
            try await receiveFiles(socket, expected: expected)
        default:
            throw BridgeError.badProtocol
        }
    }

    private func receiveFiles(_ socket: BridgeWire, expected: [Expected]) async throws {
        let directory = receivedDirectory()
        let fm = FileManager.default
        let available = (try? directory.resourceValues(
            forKeys: [.volumeAvailableCapacityKey]).volumeAvailableCapacity) ?? 0
        var total: Int64 = 0
        for file in expected {
            guard total <= Int64.max / 4 - file.size else {
                throw BridgeError.invalidFile
            }
            total += file.size
        }
        guard available > 64 * 1024 * 1024,
              total < Int64(available - 64 * 1024 * 1024) else {
            throw BridgeError.invalidFile
        }
        var temps: [URL] = []
        var published: [URL] = []
        do {
            try await socket.sendJSON(["type": "accept"])
            for (index, item) in expected.enumerated() {
                status = "接收原始檔 \(index + 1)/\(expected.count)：\(item.name)"
                let start = try await socket.readJSON(expected: "file_start")
                guard (start["index"] as? NSNumber)?.intValue == index else {
                    throw BridgeError.badProtocol
                }
                let temp = directory.appendingPathComponent("." + UUID().uuidString + ".part")
                guard fm.createFile(atPath: temp.path, contents: nil) else {
                    throw BridgeError.invalidFile
                }
                temps.append(temp)
                let handle = try FileHandle(forWritingTo: temp)
                var count: Int64 = 0
                var sha = SHA256()
                do {
                    while count < item.size {
                        let bytes = try await socket.readBinary()
                        guard Int64(bytes.count) <= item.size - count else {
                            throw BridgeError.invalidFrame
                        }
                        try handle.write(contentsOf: bytes)
                        sha.update(data: bytes)
                        count += Int64(bytes.count)
                        progress = Double(count) / Double(item.size)
                    }
                    try handle.close()
                } catch {
                    try? handle.close()
                    throw error
                }
                let end = try await socket.readJSON(expected: "file_end")
                let hash = sha.finalize().map { String(format: "%02x", $0) }.joined()
                guard (end["index"] as? NSNumber)?.intValue == index,
                      count == item.size, hash == item.sha else {
                    throw BridgeError.integrityFailure
                }
                try await socket.sendJSON(["type": "file_ok", "index": index])
            }
            _ = try await socket.readJSON(expected: "complete")
            // Publish only after every file has passed SHA-256; keep rollback handles
            // until Android acknowledges completion.
            for (index, item) in expected.enumerated() {
                let dest = directory.appendingPathComponent(
                    UUID().uuidString + "-" + item.name)
                try fm.moveItem(at: temps[index], to: dest)
                published.append(dest)
            }
            try await socket.sendJSON(["type": "complete_ok"])
            reloadReceived()
            status = "已接收 \(expected.count) 個檔案，全部通過 SHA-256 驗證"
        } catch {
            temps.forEach { try? fm.removeItem(at: $0) }
            published.forEach { try? fm.removeItem(at: $0) }
            throw error
        }
    }
}
