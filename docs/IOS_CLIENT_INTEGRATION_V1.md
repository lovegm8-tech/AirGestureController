# iPhone 14 Core NFC × Android Wi-Fi protocol v1 — client integration

## Code status
Source-only prototype on branch `feature/ios14-core-nfc-wifi-client-v1` based on Android `feature/ios14-nfc-wifi-v1`, not merged into Android production.
Do not ship or describe as device-tested. No signed IPA, App Store TestFlight or Android APK has been built here.

## iOS wiring
- `ios/AirGrabIOS/BridgeNFC.swift`: foreground `NFCTagReaderSession` ISO7816 reader. Explicit AID SELECT `F041495247524149`, GET_HELLO, HMAC proof, Android P-256 ECDSA signature, Android stable key fingerprint and human trust confirmation.
- `ios/AirGrabIOS/BridgeWire.swift`: Android contract compatible AGW1 TCP hello + HMAC, HKDF directional AES-256-GCM, A2I1/I2A1 counter nonces, 4-byte-big-endian frame lengths and 48KiB chunks.
- `ios/AirGrabIOS/TransferClient.swift`: iPhone sender/receiver controls, sender matches Android `android_sender/android_receiver` role, 1–32 file offers + per-file SHA-256, URL offers, rollback for partial incoming batch and optional Photos save.
- `ios/AirGrabShare`: system Share Sheet obtains source-provided photo/movie/file/URL/text representations into App Group. A link remains a URL, not a downloaded third-party video.
- `ios/AirGrabIOS/ContentView.swift`: user-initiated NFC scan, file/URL send or receive, identity approval and transfer status.

## Prerequisites
- Build on **macOS Xcode**, with provisioning for the main app and Share Extension plus matching App Groups entitlement. XcodeGen: `cd ios && xcodegen generate`. Current source is not tested with Xcode yet.
- Enable NFC Tag Reading in signing/capabilities, and include AID `F041495247524149` in Info.plist.
- Set App Group `group.tw.airgrab.transfer` in BOTH signed targets. Configure signing team IDs and bundle identifiers for the actual developer account.
- iPhone 14 has to keep the App foreground for Core NFC, then foreground for the Wi-Fi transfer.
- Android bridge feature branch has to be built and run in foreground, user must ARM explicit send/receive page.
- First tap shows a public key SHA-256 fingerprint for approval. This is TOFU: without an authenticated comparison from Android screen, **do not claim first-contact MITM/relay resistance**.
- Must already be on the **same working IPv4 Wi-Fi LAN or hotspot**. This version does not set up Wi-Fi Aware, Wi-Fi Direct, hotspot, QR fallback or peer discovery. No external Internet needed on a working local LAN.

## Contract summary
See `docs/IOS_NFC_WIFI_PROTOCOL_V1.md` for the authoritative wire bytes and state machine, especially APDU SELECT/GET_HELLO/AUTH, `sid` hex format, key derivation, 1..32 file chunks, encrypted `hello/ready/offer/accept/file_start/DATA/file_end/file_ok/complete/complete_ok`.
No Android gesture, Nearby, old QR `ioslink`, or Android NFC AID was modified by this branch.

## Necessary real-device tests
1. Xcode macOS compilation/signing of main app and Share Extension. Verify NFC AID and App Group provisioning.
2. S26 Ultra receiver page armed, iPhone selects 1 HEIC, NFC taps, iPhone sends, Android checks MediaStore photo and SHA-256.
3. S26 Ultra selects an MP4 via Android ShareSheet, iPhone starts receive NFC scan, verify saved original and playable video.
4. URL in both directions including Safari and YouTube (URL only, never assume raw online video).
5. Mixed batch up to 32 files, files >2GiB subject to free-space constraints, deliberately corrupt SHA-256, Wi-Fi interruption/timeout, canceled NFC.
6. Wrong HCE/AID, revoked/changed Android public-key fingerprint and replay attempt.
7. No shared Wi-Fi (should show understandable failure) and hotspot-only LAN; Android NFC/Nearby Samsung↔Samsung still works independently.
8. Large video disk usage, iOS photo add-only permission, Share Extension sources possibly re-encode.

## Known gaps
- No automatic hotspot provisioning or Wi-Fi Aware discovery.
- No interruption resume, multi-receiver or Live Photo paired-original restoration.
- iPhone does not implement system-wide Android-style air-gesture overlay or camera watching other apps.
- iOS Files picker and other app share sheets may provide derived representations; SHA-256 only guarantees the *bytes actually received* equal the *bytes supplied by the source*, not the original photo-library asset.
- Existing SwiftUI/extension code is still awaiting compilation on a real macOS/iOS toolchain. Treat NFC interoperability and end-to-end performance as unverified.
