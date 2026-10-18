# 隔空抓取 — Android ↔ iPhone 14 (v1 source-only preview)

## Scope

**Android:** Existing AirGestureController Android app gains a separate, opt-in transfer screen,
ACTION_SEND / ACTION_SEND_MULTIPLE share target, foreground LAN receiver, and QR pairing.
The camera / accessibility / YouTube gesture decision code has not been changed.

**iPhone 14:** A separate native SwiftUI app and iOS Share Extension. No camera-based gesture
control. Select media/files through the Files importer or receive items from other apps'
system share sheets, then explicitly tap to upload. Keep the iPhone app foreground while transferring.

**Important repository discovery:** The connected GitHub repository is **AirGestureController**
and does not contain the earlier Android-only "隔空抓取" transport code.
This feature branch adds a new transport module; it does **not** automatically integrate
the separate earlier grab gesture into the newly written share queue. That integration
requires the prior transfer source or an explicitly verified gesture callback.

## Protocol and safety

- Socket transport, TCP port 48773, same LAN or manually joined phone hotspot.
  iOS connects to Android IPv4 advertised by the initial QR code (not Wi-Fi Aware).
- QR code contains host, port, and a random 256-bit AES key created on Android.
  **Never post QR screenshots**. A trusted peer can read all pending offers and upload files.
- Each frame: 4-byte big-endian length; 12-byte random AES-GCM nonce,
  encrypted payload + 16-byte tag; plaintext consists of 1-byte frame type
  (1 = UTF-8 JSON control, 2 = binary), followed by the actual bytes.
- Frame payload max 512 KiB; file chunk 256 KiB; no base64 for file contents.
- Commands after pairing:
  - request {"op":"list"} -> {"offers":[{"id", "name", "mime", "size"}]}.
  - request {"op":"get","id":"..."} -> {"status":"file","name", "mime", "size", "sha256"},
    encrypted data frames, {"status":"end"}.
  - request {"op":"put","name", "size", "sha256"} -> {"status":"ready"},
    encrypted data frames, {"status":"end"} -> {"status":"ok"} or error.
- SHA-256 of exact offered payload is verified **before publishing**, no re-encoding of
  transferred payload. Uploaded Android files are published to Pictures/AirGrab,
  Movies/AirGrab, or Download/AirGrab through MediaStore.
  iPhone downloaded files are saved under its app Documents/Received folder.
- Android offers expire after 10 minutes. Foreground service starts only on explicit
  opening or Android share action; users stop it manually. System restrictions can
  still terminate the service.
- **v1 does not yet resume an interrupted transfer.** Interrupted/failed inbound
  files are discarded and must be resent.
- Photo/Live Photo caveat: byte-identical transfer applies to the raw file representation
  exported by the source App/Photos share sheet, not necessarily the original asset.
  Photos may export a derived JPEG/HEIC or only one Live Photo resource.
  Future Photos PHAssetResourceManager support is needed for full Live Photo pairs.

## Sharing behaviour (two-way)

| Source | What can be transferred |
|---|---|
| Samsung Gallery / iPhone Photos | File representation supplied by OS |
| Google and Chrome | URL/text; images or files if source shares actual attachment |
| YouTube and Facebook | Usually a page/video/post URL or text, **not platform video bytes** |
| Android file managers / iOS Files | User-selected raw file bytes |

## iPhone build setup (not run)

1. On a Mac, install Xcode and XcodeGen; in ios/ run xcodegen generate.
2. Configure an Apple development team and enable the matching App Group capability
   in BOTH AirGrabIOS and AirGrabShare target (currently group.tw.airgrab.transfer).
3. If using a different team/bundle identifiers, update project.yml, both entitlements,
   and SharedInbox.group together.
4. Run on a real iPhone 14 with iOS 16 or later, grant local network and QR camera
   permissions. Simulator cannot establish the same real-world LAN conditions.
5. Enable Android opt-in receiver and scan QR. Android share -> iPhone list/get.
   iPhone share -> Share Extension stage -> foreground app -> send.
6. This branch intentionally **does not** contain an APK/IPA or a CI build trigger.

## Acceptance tests before merge

- Verify iOS share extension appears in Chrome, Google (if installed), Photos,
  YouTube and Facebook, and note the actual item types each source exposes.
- Same 10MB JPEG/HEIC, 500MB MOV, 2GB video, PDF and a shared URL across both directions;
  compare SHA-256 at source and destination.
- Repeated consecutive sends, wrong key, one device switching Wi-Fi, screen off,
  app backgrounded, failed writes, insufficient storage, malformed frames, multiple devices.
- Measure pairing/search time, MB/s, total completion time and success rate over at least
  three runs. Check whether an iPhone photo supplied by the share sheet is truly original.
- Check Android release CI: current signed-release workflow deliberately asserts
  no INTERNET permission. It MUST be adapted deliberately after feature approval;
  the present feature branch modifies the manifest, but does not touch production/main.
- Review UI, security, performance, signing, iOS bundle provisioning, and any
  backward compatibility with the separate prior Android grab implementation.

## Status

Code committed on an **isolated feature branch only**; Android/iOS build and real-device
interop have **not** been run or claimed. It must not be described as a working APK/IPA.
