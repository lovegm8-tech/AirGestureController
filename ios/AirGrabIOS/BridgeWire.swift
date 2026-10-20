import Foundation
import Network
import CryptoKit

/// AirGrab Wi-Fi v1.0 wire format: TCP-AUTH, HKDF/HMAC, deterministic
/// AES-256-GCM nonces, per-direction counters and length-framed ciphertext.
final class BridgeWire {
    static let chunkBytes = 48 * 1024
    static let maxFrameBytes = 65_536
    static let maxControlBytes = 16 * 1024

    private let session: BridgeNFCHandshake
    private let connection: NWConnection
    private let outgoingKey: SymmetricKey
    private let incomingKey: SymmetricKey
    private var outgoingSequence: UInt64 = 0
    private var incomingSequence: UInt64 = 0
    private var unread = Data()

    init(_ handshake: BridgeNFCHandshake) throws {
        guard let port = NWEndpoint.Port(rawValue: handshake.port) else {
            throw BridgeError.badHandshake
        }
        session = handshake
        connection = NWConnection(host: NWEndpoint.Host(handshake.host), port: port, using: .tcp)
        let salt = handshake.sid + handshake.androidNonce + handshake.iosNonce
        let prk = Self.hmac(key: salt, data: handshake.secret)
        outgoingKey = SymmetricKey(data: Self.hmac(key: prk, data:
            Data("AIRGRAB-IOS-WIFI-V1/i2a".utf8) + Data([1])))
        incomingKey = SymmetricKey(data: Self.hmac(key: prk, data:
            Data("AIRGRAB-IOS-WIFI-V1/a2i".utf8) + Data([1])))
    }

    private static func hmac(key: Data, data: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: data, using: SymmetricKey(data: key)))
    }

    static func be64(_ value: UInt64) -> Data {
        var num = value.bigEndian
        return withUnsafeBytes(of: &num) { Data($0) }
    }

    func open() async throws {
        let socket = connection
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            var finished = false
            socket.stateUpdateHandler = { state in
                guard !finished else { return }
                switch state {
                case .ready:
                    finished = true; socket.stateUpdateHandler = nil; c.resume()
                case .failed(let e):
                    finished = true; socket.stateUpdateHandler = nil; c.resume(throwing: e)
                case .cancelled:
                    finished = true; socket.stateUpdateHandler = nil
                    c.resume(throwing: BridgeError.disconnected)
                default: break
                }
            }
            socket.start(queue: DispatchQueue(label: "airgrab.ios.bridge.tcp"))
        }
        let signature = Self.hmac(key: session.secret, data:
            Data("TCP-AUTH-V1".utf8) + session.sid + session.iosNonce)
        try await sendRaw(Data("AGW1".utf8) + session.sid + session.iosNonce + signature)
    }

    func close() { connection.cancel() }

    private func sendRaw(_ data: Data) async throws {
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            connection.send(content: data, completion: .contentProcessed { error in
                if let error { c.resume(throwing: error) }
                else { c.resume() }
            })
        }
    }

    private func readExactly(_ count: Int) async throws -> Data {
        while unread.count < count {
            let wanted = min(65536, count - unread.count)
            let chunk: Data = try await withCheckedThrowingContinuation { c in
                connection.receive(minimumIncompleteLength: 1, maximumLength: wanted) {
                    data, _, isComplete, error in
                    if let error { c.resume(throwing: error) }
                    else if let data, !data.isEmpty { c.resume(returning: data) }
                    else { c.resume(throwing: BridgeError.disconnected) }
                }
            }
            unread.append(chunk)
        }
        let result = Data(unread.prefix(count))
        unread.removeFirst(count)
        return result
    }

    private func nonce(domain: String, sequence: UInt64) throws -> AES.GCM.Nonce {
        try AES.GCM.Nonce(data: Data(domain.utf8) + Self.be64(sequence))
    }

    func send(_ tag: UInt8, body: Data) async throws {
        guard (tag == 1 && body.count <= Self.maxControlBytes) ||
              (tag == 2 && !body.isEmpty && body.count <= Self.chunkBytes),
              outgoingSequence < UInt64.max else { throw BridgeError.invalidFrame }
        let sequence = outgoingSequence
        let nonce = try nonce(domain: "I2A1", sequence: sequence)
        let aad = session.sid + Self.be64(sequence)
        let sealed = try AES.GCM.seal(Data([tag]) + body, using: outgoingKey,
                                       nonce: nonce, authenticating: aad)
        let encrypted = sealed.ciphertext + sealed.tag  // NEVER append nonce on the wire.
        guard encrypted.count >= 17 && encrypted.count <= Self.maxFrameBytes else {
            throw BridgeError.invalidFrame
        }
        var count = UInt32(encrypted.count).bigEndian
        let prefix = withUnsafeBytes(of: &count) { Data($0) }
        try await sendRaw(prefix + encrypted)
        outgoingSequence += 1
    }

    func receive() async throws -> (UInt8, Data) {
        let length = try await readExactly(4).reduce(UInt32(0)) {
            ($0 << 8) | UInt32($1)
        }
        guard length >= 17 && length <= Self.maxFrameBytes,
              incomingSequence < UInt64.max else { throw BridgeError.invalidFrame }
        let encrypted = try await readExactly(Int(length))
        let sequence = incomingSequence
        let nonce = try nonce(domain: "A2I1", sequence: sequence)
        let sealed = try AES.GCM.SealedBox(nonce: nonce,
            ciphertext: Data(encrypted.dropLast(16)), tag: Data(encrypted.suffix(16)))
        let plain = try AES.GCM.open(sealed, using: incomingKey,
                                     authenticating: session.sid + Self.be64(sequence))
        guard let tag = plain.first, tag == 1 || tag == 2 else {
            throw BridgeError.invalidFrame
        }
        let body = Data(plain.dropFirst())
        guard (tag == 1 && body.count <= Self.maxControlBytes) ||
              (tag == 2 && !body.isEmpty && body.count <= Self.chunkBytes) else {
            throw BridgeError.invalidFrame
        }
        incomingSequence += 1
        return (tag, body)
    }

    func sendJSON(_ json: [String: Any]) async throws {
        let encoded = try JSONSerialization.data(withJSONObject: json)
        try await send(1, body: encoded)
    }

    func readJSON(expected: String? = nil) async throws -> [String: Any] {
        let (tag, data) = try await receive()
        guard tag == 1,
              let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = json["type"] as? String else { throw BridgeError.badProtocol }
        if type == "error" { throw BridgeError.badProtocol }
        if let expected, type != expected { throw BridgeError.badProtocol }
        return json
    }

    func readBinary() async throws -> Data {
        let (tag, data) = try await receive()
        guard tag == 2 else { throw BridgeError.badProtocol }
        return data
    }
}
