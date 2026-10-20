import Foundation
import CoreNFC
import CryptoKit
import Security

/// Mirrors docs/IOS_NFC_WIFI_PROTOCOL_V1.md, NFC AID F041495247524149.
/// iPhone stays the reader in BOTH transfer directions. NFC never carries file bytes.
struct BridgeNFCHandshake {
    let hello: Data
    let sid: Data
    let androidNonce: Data
    let secret: Data
    let iosNonce: Data
    let androidPublicKey: Data
    let host: String
    let port: UInt16

    var fingerprint: String {
        SHA256.hash(data: androidPublicKey).map { String(format: "%02x", $0) }.joined()
    }

    init(hello: Data, iosNonce: Data) throws {
        let bytes = [UInt8](hello)
        guard bytes.count == 140, Array(bytes[0..<4]) == Array("AGI1".utf8),
              bytes[4] == 1, iosNonce.count == 16, bytes[75] == 0x04 else {
            throw BridgeError.badHandshake
        }
        let port = UInt16(bytes[25]) << 8 | UInt16(bytes[26])
        guard port > 0 else { throw BridgeError.badHandshake }
        self.hello = hello
        self.sid = hello.subdata(in: 5..<21)
        self.androidNonce = hello.subdata(in: 27..<43)
        self.secret = hello.subdata(in: 43..<75)
        self.androidPublicKey = hello.subdata(in: 75..<140)
        self.iosNonce = iosNonce
        self.host = bytes[21..<25].map { String($0) }.joined(separator: ".")
        self.port = port
        // Refuse invalid elliptic curve points before any TCP connection.
        _ = try P256.Signing.PublicKey(x963Representation: androidPublicKey)
    }
}

enum BridgeError: LocalizedError {
    case nfcUnavailable, badHandshake, apduRejected(UInt8, UInt8), unsupportedDevice
    case identityChanged, invalidFrame, disconnected, badProtocol, integrityFailure
    case invalidFile, noSelection, networkUnavailable

    var errorDescription: String? {
        switch self {
        case .nfcUnavailable: return "此手機暫時無法使用 NFC 讀取"
        case .badHandshake: return "Android NFC 配對資料不正確"
        case let .apduRejected(a, b): return String(format: "Android NFC 拒絕指令（%02X%02X）", a, b)
        case .unsupportedDevice: return "不是已啟用跨平台 NFC 待命的 Android 手機"
        case .identityChanged: return "Android 裝置身分已變更，連線已取消"
        case .invalidFrame: return "收到不合法的傳輸封包"
        case .disconnected: return "Wi-Fi 連線中斷"
        case .badProtocol: return "兩端傳輸協定不相容或步驟不一致"
        case .integrityFailure: return "原始檔案完整性驗證失敗"
        case .invalidFile: return "檔案名稱、大小或存取權限不符合要求"
        case .noSelection: return "請先選擇分享內容"
        case .networkUnavailable: return "兩台手機需要連上相同的 Wi-Fi 或熱點"
        }
    }
}

final class BridgeNFCReader: NSObject, NFCTagReaderSessionDelegate {
    private var reader: NFCTagReaderSession?
    private let resultHandler: (Result<BridgeNFCHandshake, Error>) -> Void
    private let lock = NSLock()
    private var completed = false

    init(resultHandler: @escaping (Result<BridgeNFCHandshake, Error>) -> Void) {
        self.resultHandler = resultHandler
    }

    func begin() {
        guard NFCTagReaderSession.readingAvailable else {
            finish(.failure(BridgeError.nfcUnavailable))
            return
        }
        let scan = NFCTagReaderSession(pollingOption: .iso14443, delegate: self, queue: nil)
        scan.alertMessage = "請將 iPhone 14 頂端靠近已啟動「iPhone NFC」待命的 Android 手機背面"
        reader = scan
        scan.begin()
    }

    func tagReaderSessionDidBecomeActive(_ session: NFCTagReaderSession) {}

    func tagReaderSession(_ session: NFCTagReaderSession, didDetect tags: [NFCTag]) {
        guard tags.count == 1, case let .iso7816(tag) = tags[0] else {
            session.alertMessage = "偵測到非相容 NFC 裝置，請重新靠近"
            session.restartPolling()
            return
        }
        session.connect(to: tags[0]) { [weak self] error in
            guard let self else { return }
            if let error {
                self.finish(.failure(error))
                session.invalidate()
                return
            }
            Task {
                do {
                    let a = try await self.exchange(tag)
                    session.alertMessage = "NFC 身分已驗證，請將手機分開並繼續 Wi-Fi 傳送"
                    self.finish(.success(a))
                } catch {
                    self.finish(.failure(error))
                }
                session.invalidate()
            }
        }
    }

    private func command(_ bytes: [UInt8], tag: NFCISO7816Tag) async throws -> Data {
        guard let apdu = NFCISO7816APDU(data: Data(bytes)) else {
            throw BridgeError.badHandshake
        }
        return try await withCheckedThrowingContinuation { continuation in
            tag.sendCommand(apdu: apdu) { data, sw1, sw2, error in
                if let error { continuation.resume(throwing: error) }
                else if sw1 != 0x90 || sw2 != 0x00 {
                    continuation.resume(throwing: BridgeError.apduRejected(sw1, sw2))
                } else {
                    continuation.resume(returning: data)
                }
            }
        }
    }

    private func exchange(_ tag: NFCISO7816Tag) async throws -> BridgeNFCHandshake {
        // Explicit SELECT makes the HCE service selection deterministic.
        let aid: [UInt8] = [0xF0, 0x41, 0x49, 0x52, 0x47, 0x52, 0x41, 0x49]
        _ = try await command([0x00, 0xA4, 0x04, 0x00, 0x08] + aid + [0x00], tag: tag)
        let hello = try await command([0x80, 0x20, 0x00, 0x00, 0x00], tag: tag)
        var nonce = [UInt8](repeating: 0, count: 16)
        guard SecRandomCopyBytes(kSecRandomDefault, nonce.count, &nonce) == errSecSuccess else {
            throw BridgeError.badHandshake
        }
        let hs = try BridgeNFCHandshake(hello: hello, iosNonce: Data(nonce))
        let proof = Data(HMAC<SHA256>.authenticationCode(
            for: Data("NFC-AUTH-V1".utf8) + hs.hello + hs.iosNonce,
            using: SymmetricKey(data: hs.secret)
        ))
        let response = try await command([0x80, 0x21, 0x00, 0x00, 0x30] +
            nonce + Array(proof) + [0x00], tag: tag)
        guard let signatureLength = response.first,
              signatureLength >= 64, signatureLength <= 80,
              response.count == Int(signatureLength) + 1 else {
            throw BridgeError.badHandshake
        }
        let pub = try P256.Signing.PublicKey(x963Representation: hs.androidPublicKey)
        let signature = try P256.Signing.ECDSASignature(derRepresentation: Data(response.dropFirst()))
        guard pub.isValidSignature(signature, for: hs.hello + hs.iosNonce) else {
            throw BridgeError.badHandshake
        }
        return hs
    }

    func tagReaderSession(_ session: NFCTagReaderSession, didInvalidateWithError error: Error) {
        finish(.failure(error))
    }

    private func finish(_ result: Result<BridgeNFCHandshake, Error>) {
        lock.lock()
        let first = !completed
        completed = true
        lock.unlock()
        if first { DispatchQueue.main.async { self.resultHandler(result) } }
    }
}
