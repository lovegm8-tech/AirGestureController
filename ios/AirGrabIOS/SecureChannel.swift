import Foundation
import Network
import CryptoKit

enum AirGrabError: LocalizedError {
    case invalidFrame, invalidResponse, disconnected, badPairing, integrityFailure, tooLarge
    var errorDescription: String? {
        switch self {
        case .invalidFrame: return "傳輸資料格式錯誤"
        case .invalidResponse: return "裝置回應不正確"
        case .disconnected: return "連線中斷"
        case .badPairing: return "配對資料不正確"
        case .integrityFailure: return "檔案校驗失敗，未保存"
        case .tooLarge: return "檔案超過目前的安全上限"
        }
    }
}

final class SecureChannel {
    private let connection: NWConnection
    private let key: SymmetricKey
    private var unread = Data()
    static let chunkSize = 256 * 1024
    private static let maxPayload = 512 * 1024

    init(host: String, port: UInt16, key: Data) throws {
        guard key.count == 32, let p = NWEndpoint.Port(rawValue: port) else {
            throw AirGrabError.badPairing
        }
        self.key = SymmetricKey(data: key)
        self.connection = NWConnection(host: NWEndpoint.Host(host), port: p, using: .tcp)
    }

    func connect() async throws {
        let connection = self.connection
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            var completed = false
            connection.stateUpdateHandler = { state in
                guard !completed else { return }
                switch state {
                case .ready:
                    completed = true
                    connection.stateUpdateHandler = nil
                    continuation.resume()
                case .failed(let error):
                    completed = true
                    connection.stateUpdateHandler = nil
                    continuation.resume(throwing: error)
                case .cancelled:
                    completed = true
                    connection.stateUpdateHandler = nil
                    continuation.resume(throwing: AirGrabError.disconnected)
                default: break
                }
            }
            connection.start(queue: DispatchQueue(label: "airgrab.nw.connection"))
        }
    }

    func close() { connection.cancel() }

    func sendJSON(_ object: [String: Any]) async throws {
        let data = try JSONSerialization.data(withJSONObject: object)
        try await send(type: 1, payload: data)
    }

    func sendBytes(_ data: Data) async throws {
        try await send(type: 2, payload: data)
    }

    private func send(type: UInt8, payload: Data) async throws {
        guard payload.count <= Self.maxPayload else { throw AirGrabError.invalidFrame }
        let encrypted = try AES.GCM.seal(Data([type]) + payload, using: key)
        guard let bytes = encrypted.combined else { throw AirGrabError.invalidFrame }
        var length = UInt32(bytes.count).bigEndian
        let header = withUnsafeBytes(of: &length) { Data($0) }
        let packet = header + bytes
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            connection.send(content: packet, completion: .contentProcessed { error in
                if let error { continuation.resume(throwing: error) }
                else { continuation.resume() }
            })
        }
    }

    func receive() async throws -> (UInt8, Data) {
        let lengthBytes = try await readExactly(4)
        let size = lengthBytes.reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        guard size >= 29 && size <= Self.maxPayload + 29 else { throw AirGrabError.invalidFrame }
        let ciphertext = try await readExactly(Int(size))
        let box = try AES.GCM.SealedBox(combined: ciphertext)
        let plain = try AES.GCM.open(box, using: key)
        guard let type = plain.first, type == 1 || type == 2 else {
            throw AirGrabError.invalidFrame
        }
        return (type, plain.dropFirst())
    }

    func receiveJSON() async throws -> [String: Any] {
        let (type, bytes) = try await receive()
        guard type == 1,
              let dict = try JSONSerialization.jsonObject(with: bytes) as? [String: Any] else {
            throw AirGrabError.invalidResponse
        }
        return dict
    }

    private func readExactly(_ count: Int) async throws -> Data {
        while unread.count < count {
            let amount = min(512 * 1024, count - unread.count)
            let chunk = try await withCheckedThrowingContinuation {
                (continuation: CheckedContinuation<Data, Error>) in
                connection.receive(minimumIncompleteLength: 1, maximumLength: amount) {
                    data, _, done, error in
                    if let error { continuation.resume(throwing: error) }
                    else if let data, !data.isEmpty { continuation.resume(returning: data) }
                    else { continuation.resume(throwing: AirGrabError.disconnected) }
                }
            }
            unread.append(chunk)
        }
        let result = Data(unread.prefix(count))
        unread.removeFirst(count)
        return result
    }
}
