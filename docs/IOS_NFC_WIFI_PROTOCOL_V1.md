# 懸浮操作／AirGrab iPhone 14 NFC + Wi-Fi Protocol v1.0

**Status:** Android-only development branch \`feature/ios14-nfc-wifi-v1\`; iOS app not built; requires iPhone 14 + Galaxy actual interoperability tests. **No APK build in this phase.**

## 0. Scope and non-goals

- Existing Android↔Android NFC AID **F041495247524142**, Android Nearby P2P, 1–32 sequential-file transfers, front-camera gestures, accessibility/Chrome/YouTube grab flows and production signing/package are **unchanged**.
- The new cross-platform protocol lives in \`tw.airgesture.transferbeta.crossplatform\`, and has its **own** Android HCE service, Android ShareSheet activity, per-session TCP server and cryptographic material. No dependency on \`MultiPeerTransferManager\` or \`GlobalGestureService\`.
- **iPhone 14 is the NFC reader**, using an **active foreground \`NFCTagReaderSession\`**. **Android is the HCE card.** This remains true in **both file directions**; iPhone never needs to emulate a tag for Android. **The iOS app must be open** to scan an ISO7816 APDU. NFC contact does not silently choose on-screen Chrome/Photos/Facebook content.
- **NFC never sends images or files.** Its purpose is physical-proximity discovery, a user-armed 120-second session, one-time authorization and trusted Android identity bootstrapping. File/URL payloads use a **separate Wi-Fi IPv4 TCP session over an existing shared LAN** (same Wi-Fi or a manually configured hotspot; Wi-Fi Direct is NOT part of v1). Android Nearby is not involved.
- Up to **32 originals in one session**, in sequence. Their exact bytes and SHA-256 hashes are checked and no file is compressed, transcoded or packed into an archive. Practical file sizes depend on disk and OS limitations; there is no hard-coded 2-GiB sum cap.
- **Sending Android → iOS:** Android user selects one URL or 1–32 files via \`分享 → 懸浮操作 · iPhone NFC / Wi-Fi\` and keeps the bridge page open, then iOS reader scans. **Sending iOS → Android:** Android opens the bridge receive page from the existing app dashboard; user prepares their content in the iOS app, which reads the Android HCE and sends via Wi-Fi. Android receives into Images/Videos/Downloads MediaStore collections.
- Android **does not** autonomously read currently visible content. External share adapters may supply \`IosBridgePayload.Url\` or \`IosBridgePayload.Files\` without changing NFC or TCP code.

## 1. iPhone 14 application prerequisites

- Swift iOS application with \`CoreNFC.NFCTagReaderSession(pollingOption: .iso14443)\` and \`NFCISO7816Tag.sendCommand(apdu:)\` / equivalent completion-handler API.
- NFC tag reader formats entitlement (\`com.apple.developer.nfc.readersession.formats\` including \`TAG\`), \`NFCReaderUsageDescription\`, and Info.plist **\`com.apple.developer.nfc.readersession.iso7816.select-identifiers\`** containing the NEW AID **\`F041495247524149\`**. Apple's tag reader itself issues SELECT AID; the reader delegate then sees the tag.
- \`NSLocalNetworkUsageDescription\` + approval of local network access. Read the local Android IPv4 endpoint in the NFC reply and connect with \`Network.framework\` TCP (\`NWConnection\`) or sockets; no Bonjour discovery required in v1. Use iOS CryptoKit \`P256.Signing.PublicKey(x963Representation:)\` to validate Android P-256 identity, \`HMAC<SHA256>\`, HKDF and \`AES.GCM\` with the exact transcript and nonce rules below.
- Keep NFC reader session active for the short APDU exchange; it may end after successful AUTH. Wi-Fi transfer continues in the **foreground iOS app**. Background transfer is not promised.
- Both devices must already reach each other over IPv4 LAN. The bridge does NOT configure Wi-Fi, join hotspots, traverse NAT, or transfer via a web server/cloud. Android listens on an OS-selected dynamic TCP port. iOS initiates TCP after NFC. On networks with client isolation, this fails gracefully.

## 2. Byte primitives and interoperability rules

- All byte lengths/counters/ports use **network byte order (big-endian)**.
- \`sid\`: 16 random bytes, per-arm; \`androidNonce\`: 16 random bytes; \`iosNonce\`: 16 random bytes; \`secret\`: 32 NFC-provisioned random bytes.
- \`P256 public key\`: 65-byte uncompressed X9.63 point \`0x04 || X32 || Y32\`. Persist/pin SHA-256 of these 65 bytes in the iOS app **after the user confirms first-time pairing**. When the key changes on later sessions, warn and require reapproval. AndroidKeyStore identity alias: \`airgrab.iosbridge.identity.p256.v1\`. Private key is never sent.
- Only NFC v1 / Wi-Fi protocol \`major=1\` and \`minor=0\` are emitted now. A future minor release **must preserve all existing fields** and may add ignorable JSON fields; receiver validates major and ignores optional unknown JSON fields. Breaking wire changes require a new major and new NFC AID or explicit negotiation, **not silent reuse of v1 fields**.
- The iOS reader must display the Android identity confirmation/changed-key warning. The Android user explicitly arms the bridge for the next connection; arming is **not** persistent, and receipt of NFC by itself is never permission to read another app's content.
- Status words: \`9000\` = success; \`6985\` = no current armed session/invalid challenge; \`6D00\` = invalid APDU; \`6A80\` = malformed arguments.

## 3. NFC APDU wire protocol

Use application identifier **F041495247524149**. This AID is intentionally different from the existing Android NFC AID \`F041495247524142\`. Android HCE service descriptor is \`res/xml/ios_bridge_hce.xml\`, category \`other\`, unlock required.

1. SELECT (usually sent by Core NFC from the plist list): \`00 A4 04 00 08 F0 41 49 52 47 52 41 49 [00 optional Le]\` → \`90 00\`. Reader must SELECT this AID before any other instruction.
2. Reader GET_HELLO command: \`80 20 00 00 00\`. If Android bridge is armed and unlocked, reply \`HELLO || 90 00\` with a fixed **140-byte HELLO** (below).
3. iOS verifies \`AGI1\`, major, and records the returned Android long-term P256 public key. Generates a fresh random 16-byte \`iosNonce\`.
4. iOS computes \`proof = HMAC-SHA256(secret, ASCII("NFC-AUTH-V1") || HELLO || iosNonce)\`. Send **AUTH**: \`80 21 00 00 30 || iosNonce[16] || proof[32]\` (optional trailing Le).
5. Android validates a *single-use* proof and replies \`signatureLength[1] || signatureDER || 90 00\`. Signature DER is P-256 ECDSA/SHA-256 over **\`HELLO || iosNonce\`**. iOS verifies with the provided key and checks its saved pin. An unsolicited or repeated AUTH fails.
6. **Separate phones physically**. Android awaits one TCP connection only while this particular session is valid; the NFC field is no longer needed.

HELLO binary offsets:

| Bytes (inclusive) | Size | Meaning |
|---|---:|---|
| 0..3 | 4 | ASCII \`AGI1\` |
| 4 | 1 | major = \`01\` |
| 5..20 | 16 | random session ID, \`sid\` |
| 21..24 | 4 | Android LAN IPv4 address |
| 25..26 | 2 | Android TCP listen port, unsigned 16-bit |
| 27..42 | 16 | \`androidNonce\` |
| 43..74 | 32 | NFC ephemeral \`secret\` |
| 75..139 | 65 | stable Android P256 X9.63 public key |

**Security limitation:** The NFC secret is transmitted in cleartext over close-range NFC. Therefore, confidentiality relies on the short physical NFC interaction not being intercepted/relayed. Pairing by NFC is NOT secure against a sophisticated active NFC relay or radio interception. For a higher-assurance production release, add a mutually authenticated ECDH handshake and a user-visible comparison code. Android's signing key prevents an unapproved identity **change** after a successful first approved pairing; it is **not** proof of identity on the first tap without human confirmation. Android only accepts incoming Wi-Fi payloads after the user explicitly arms its receiver screen.

## 4. Wi-Fi connection handshake

On the iPhone, connect to \`<IPv4>:<port>\` learned above. On the unencrypted TCP socket, transmit exactly:

\`\`\`text
ASCII "AGW1"              4 bytes
sid                       16 bytes
iosNonce                  16 bytes
HMAC-SHA256(secret, ASCII("TCP-AUTH-V1") || sid || iosNonce)   32 bytes
\`\`\`

The Android server checks matching NFC session, single-use iOS nonce, NFC authorization and HMAC in constant time before processing any file/URL payload. The TCP endpoint is only available during explicit Android arming (120s); sessions terminate after one accepted connection/one batch.

### Key derivation

\`\`\`salt = sid || androidNonce || iosNonce                 // 48 bytes
PRK  = HMAC-SHA256(salt, secret)
key_a2i = HMAC-SHA256(PRK, ASCII("AIRGRAB-IOS-WIFI-V1/a2i") || 0x01)
key_i2a = HMAC-SHA256(PRK, ASCII("AIRGRAB-IOS-WIFI-V1/i2a") || 0x01)
\`\`\`

Keys are independently derived for each transfer direction. iPhone **outgoing** = \`i2a\`, **incoming** = \`a2i\`. Android **outgoing** = \`a2i\`, **incoming** = \`i2a\`. AES-256-GCM, 16-byte auth tag.

For each direction, start the unsigned 64-bit sequence counter at zero and increment **once per frame**. Encryption nonce:

\`\`\`Android→iPhone = ASCII("A2I1") || UInt64BE(sequence)
iPhone→Android = ASCII("I2A1") || UInt64BE(sequence)
AAD = sid || UInt64BE(sequence)
\`\`\`

### Frame

\`\`\`UInt32BE(ciphertextLength) || AES_256_GCM_Encrypt(
  plaintext = 1-byte type || body,
  key = direction key, nonce = direction nonce, AAD = sid || sequence
)
\`\`\`

\`ciphertextLength\` includes the 16-byte GCM tag. It must be **17..65536 bytes** and must be read exactly before decrypting. No arbitrary resynchronization after authentication failure. Plaintext tag \`0x01\` = UTF-8 JSON control message, at most 16 KiB; \`0x02\` = raw original-file chunk, **1..49152 bytes**. Disallow unknown frame tags, oversize messages, counter reuse/replay, invalid GCM tags. No ZIP, image compression or transcoding.

## 5. State machine / JSON messages

Messages are encrypted control frames (tag 0x01), except \`DATA\` raw frames (tag 0x02).

1. Android sends \`{"type":"hello","major":1,"minor":0,"sid":"<sid lowercase hex>","role":"android_sender|android_receiver","max_files":32}\`.
2. iPhone replies \`{"type":"ready","major":1,"minor":0}\`. Reject if major != 1.
3. The selected **sender** (as dictated by Android \`role\`) sends **one**:
   - URL: \`{"type":"offer","mode":"url","url":"https://example.com/path"}\`
   - File batch: \`{"type":"offer","mode":"files","files":[{"name":"photo.heic","size":123456,"sha256":"64-lowercase-hex"}, ...]}\`, **1..32 items**.
4. Receiver validates offer and replies \`{"type":"accept"}\`. Android only accepts while explicit receive page is armed. For files repeat for **index 0..n-1**:
   - sender \`{"type":"file_start","index":0}\`;
   - sender transmits \`DATA\` frames until **exactly \`files[index].size\`** bytes are received;
   - sender \`{"type":"file_end","index":0}\`;
   - receiver checks byte count and original SHA-256; only if valid responds \`{"type":"file_ok","index":0}\`.
5. Sender sends \`{"type":"complete"}\`; receiver replies \`{"type":"complete_ok"}\`. Any error/connection loss aborts the remaining files. Android receiver rolls back its new MediaStore rows if the batch fails. iOS receiver should similarly write to temporary document/photo locations and publish only when the whole batch succeeds.
6. On an invalid offer, receiver may send \`{"type":"error","reason":"..."}\` and close. Do not echo private file contents in error messages.

Original MIME type is inferred from the original filename on Android receive; including a MIME hint in a **future optional minor-version field** must not change the original file bytes. The file name must be a safe leaf name (no \`/\`, \`\\\`, \`..\` or control-character tricks). Existing \`TransferPolicy.safeName\` applies on Android.

The iOS app must decide whether to offer its own shared content or receive the Android share based on the **Android hello role**, and support displaying a selected single URL without attempting to open an untrusted link automatically.

## 6. Separation of responsibilities

\`\`\`Android system Sharesheet (or future gesture capture adapters)
    → IosBridgePayload.Receive | Url | Files
    → IosBridgeActivity (explicit user action, stage URI into cache)
    → IosBridgeHost (HCE nonce/identity; Wi-Fi short-lived server)
    → IosWifiTransfer (AES-GCM envelopes; SHA-256; MediaStore)
    ↔ iPhone App (Core NFC ISO7816 client + Wi-Fi TCP client)
\`\`\`

No \`GlobalGrabBridge\`, \`GlobalGestureService\`, \`NfcTapCardService\` or \`MultiPeerTransferManager\` symbols are imported by the new bridge. Thus future Chrome/Google/YouTube/Facebook changes affect only the content capture/share adapter; the NFC APDUs, pairing cryptography, Wi-Fi packets and iOS parser stay fixed.

### Android ↔ Android existing paths, untouched

- Android NFC select AID **F041495247524142** → \`NfcTapCardService\` → original NFC ShareSheet → verified Nearby.
- Android gesture capture and multi-file queue → \`GlobalGestureService\` and \`MultiPeerTransferManager\` → verified Nearby.
- The iOS AID **F041495247524149** → **\`IosBridgeHceService\`** → dedicated Wi-Fi module, only when \`IosBridgeActivity\` arms it.

## 7. Known limitations and required validation before using as a production feature

- **No iPhone app or real-device interoperability test yet.** Swift Core NFC/Network.framework implementation, Info.plist permissions, trust prompt and receiver storage need to be written separately.
- Android and iPhone must share routable Wi-Fi IPv4. Network client isolation, mobile-only service, APDU size/phone HCE routing behavior, foreground limitations, firewall and hotspot rules require device testing. The Android bridge is not a Wi-Fi Direct equivalent to Android Nearby; it cannot guarantee transfers in a place with **no shared LAN**.
- Android HCE requires Android NFC enabled, phone unlocked and bridge foreground arm active. iPhone must start its Core NFC reader from within the installed iOS app. iPhone's default background NDEF tag shortcuts cannot emulate this APDU handshake.
- For iPhone→Android, Android opens the explicit receiving page. iOS can select its Photos/Files/URL *within its own app* and send once authenticated. No background process can silently pull content from iOS's Photos/Facebook/YouTube screen.
- \`IosBridgeActivity\` currently needs to remain alive (screen unlocked and app active) while receiving/sending; Android process death cancels transfer. NFC-only Android-Android behavior remains as before.
- Initial physical pairing and Android identity pinning provide meaningful session authentication, but v1 has **no iOS long-term identity proof on Android** and no NFC radio/relay resistance. The bridge only accepts connections after explicit user arming; do not deploy unattended.
- To validate: 1 URL Android→iOS; 1 HEIC iOS→Android; 1 MP4 both directions; 32 mixed files; checksum mismatch rollback; duplicated/replayed encrypted sequence; expired arm; wrong device; Wi-Fi loss; Android app interruption; existing Samsung↔Samsung NFC and Nearby regression; package/signing checks. Until then mark module **experimental**.

## 8. No-build handoff

Android integration lives only on \`feature/ios14-nfc-wifi-v1\`, derived from v1.0.21 signed head \`927256897dffa16d9251f0af4e1dd78d2abcd484\`. Android manifest adds only the new HCE service, HCE XML descriptor and share target plus one manual dashboard entry. **Do not merge to the stable branch or distribute an APK until code-level compilation and on-device verification succeeds.** The production \`applicationId\`, release signing and existing app version are intentionally unchanged.
