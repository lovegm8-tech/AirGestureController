# NFC touch-to-share integration — v1.0.16 test build

The new code extends the earlier feasibility-only probe. Device testing on Galaxy S26 Ultra / S25 remains necessary.

## How to try (once a signed APK is ready)

1. Install the same signed **v1.0.16** build on both phones; keep original trusted Nearby pairing.
2. Turn on NFC and Nearby transfer for both; start the existing transfer foreground service once on each, keep both phones unlocked and allow Nearby to connect first.
3. On the S26 Ultra open a photo in Samsung Gallery, tap **Share**, then choose **「懸浮操作 · NFC 碰一碰」** (not the existing ordinary 「懸浮操作」 share target).
4. Wait for the original source file to be staged byte-for-byte and the NFC reader page to say ready.
5. Touch backs near the NFC antenna positions. The S25 (even with Samsung Gallery in front) runs a background HCE responder if the OS routes the proprietary AID. A successful contact returns a short-lived one-use nonce and the receiver's candidate ID.
6. The S26 Ultra's *already authenticated* Nearby connection confirms that nonce with the specific recipient. Only the matched peer receives an offer. S25 auto-accepts **only** the one NFC-bound offer and saves the unchanged file after its SHA-256 verification.
7. A webpage URL can use the same contact selection. Original regular Sharing and AirGrab gestures continue independently.

## Hard technical boundaries

- **Samsung Gallery must explicitly Share first.** Android offers no supported public API to read the currently displayed original image by merely tapping phones while Gallery remains foreground on the *sender*. Reader Mode works only with our app's NFC sending Activity in foreground.
- NFC AID/HCE support, Samsung wallet routing, screen-unlock rules, and background delivery must be tested on both physical devices. Build/unit tests cannot establish phone-to-phone NFC success.
- S25 can have the **App UI closed**, but the existing **transfer foreground service must still be active and connected**; cold-boot NFC cannot reliably start a camera/connected-device foreground service under current Android background limits. The HCE service rejects the request instead of falsely claiming transfer.
- Single original file/photo/video/document or one URL is supported through the new NFC Sharesheet target. Multiple files still use original AirGrab share flow; no ZIP workaround and no NFC direct file data.
- The *pre-existing* Nearby transport requires a verified, remembered peer; neither NFC location nor static ID substitutes for that cryptographic connection. The 128-bit NFC nonce is bound over the verified Nearby channel and consumed once.
- The NFC sender deliberately bypasses the optional video transcoder to guarantee source bytes are unchanged. If the recipient cannot display a native codec, it still stores the original.
- No tap-triggered file transfer occurs if NFC identity binding fails. No UI automation, OCR, inferred MediaStore picture, or silent screenshot fallback.
- No APK was part of the initial feasibility phase; v1.0.16 is a separate explicit user-requested signed test build.

---

# NFC touch-to-send feasibility: Galaxy S26 Ultra / S25

**State: exploratory code + automated unit tests only. No APK requested, built or delivered in this phase. No end-to-end on-device NFC or Samsung Gallery verification yet.**

## Requested flow and evidence

| Requirement | Feasibility | Verification |
| --- | --- | --- |
| Detect S26 Ultra physically touching S25 over NFC | Testable, not yet tested on hardware | `NfcProbeActivity` NFC-A ReaderMode SELECT custom AID -> `NfcTapCardService` HCE response; JUnit protocol tests |
| With S26 Ultra **Samsung Gallery** in foreground, app directly receives phone-to-phone NFC tap | **Not available through standard third-party ReaderMode or foreground dispatch** | Android `NfcAdapter.enableReaderMode(Activity,...)` only operates while that Activity is foreground; NFC foreground dispatch is foreground Activity scoped. Android Beam push is removed. |
| S25 in Gallery foreground acts as NFC target/card | Testable, not yet tested on S25 | Android HCE supports a background `HostApduService` when a different phone runs an NFC reader; availability depends on device/NFC/wallet routing/unlock |
| Determine which full-resolution photo is currently displayed in Samsung Gallery without user sharing | **No supported public inter-app contract; unverified Samsung-private behavior must not be assumed** | Android has no official Gallery-current-media URI API. No guessing last image, Accessibility tapping, screenshots, OCR or inferred MediaStore selection. |
| Obtain the **original** bytes | Already available when Gallery explicitly shares an authorized `content://` URI via `ACTION_SEND` | `ShareReceiverActivity.receive`: authoritative `EXTRA_STREAM`, `ShareEntryPolicy.chooseSingleOriginal`, `openInputStream` and byte-preserving staging; existing transfer architecture. |
| NFC device identification and automatic reception | **Not implemented** | NFC HCE demo marker is NOT a trustworthy unique identity; pair-specific cryptographic binding to verified Nearby endpoint is still required. |
| No loss of hand gesture, AirGrab, Nearby pairing | Preserved | New NFC feature isolated in separate optional Activity/Service and no changes to `GlobalGestureService` or `MultiPeerTransferManager` |
| NFC file transmission | Not appropriate | NFC conveys only short control exchanges; actual files should use existing secure Nearby transport. |

Sources:
- Android ReaderMode API: https://developer.android.com/reference/android/nfc/NfcAdapter#enableReaderMode(android.app.Activity,android.nfc.NfcAdapter.ReaderCallback,int,android.os.Bundle)
- Android NFC HCE background service: https://developer.android.com/develop/connectivity/nfc/hce
- Android Beam removed: https://developer.android.com/reference/android/nfc/NfcAdapter.CreateNdefMessageCallback
- Android photo permission via user selection: https://developer.android.com/training/data-storage/shared/photo-picker
- Android Gallery photo URI-sharing behavior must be verified via the actual app; no Samsung privileged APIs are used.

## What the experiment implements

- Optional NFC and HCE features / NFC permission; Android permissions and existing package/signing unchanged.
- Registered AID `F041495247524142` in non-payment category, `NfcTapCardService` with unlock required.
- Explicit advanced-settings NFC diagnostic UI. S26 has this Activity **foreground** in NFC reader mode; S25 stays unlocked in Gallery (or another foreground app), responds via HCE.
- A deterministic `SELECT AID` / `AIRGRAB:NFC-PROBE:1, 9000` reply and protocol tests.
- No device identities, filenames, contents, or transferable user data in APDU.
- No automatic acceptance, no Nearby peer selection, no screenshots, no automatic Gallery UI operations, no new package/build.
- HCE response can be spoofed; the marker proves only APDU protocol response, **never device trust**.

## Proposed on-device verification when user approves a diagnostic build

1. Enable NFC on S26 Ultra and S25, install the **same future signed diagnostic version** on both (not available in this phase).
2. Open NFC diagnostic from S26 app's Advanced Settings, keep this diagnostic Activity visible.
3. Open Samsung Gallery on S25, keep S25 unlocked with NFC on, then touch backs together, searching around the NFC antenna positions; verify `NFC 接觸成功` on S26.
4. Reverse phone roles; verify NFC HCE support and any One UI foreground behavior.
5. Return **S26** to Samsung Gallery. The diagnostic reader activity must pause and reader mode must stop. No NFC-to-file association is attempted. Validate this Android API boundary rather than treating an OS notification as successful third-party photo capture.
6. Independently verify explicit Gallery Share -> 懸浮操作 still transfers source bytes and cryptographic checksum as before.

## Development decision gate

Do **not** auto-send or auto-receive based on the static NFC proof. Further file-handoff work requires BOTH (a) a defensible source of the *specific* original photo URI (e.g. explicit Gallery share) AND (b) authenticated binding of NFC contact to a trusted verified Nearby endpoint. The strict user-requested no-share, Samsung-Gallery-foreground flow remains blocked by normal public API access, not a missing gesture tuning parameter.

Android API 37 includes `getGestureExchangeAid()` for Tap to Share, but it requires `android.permission.PERFORM_GESTURE_EXCHANGE`, and the project targets API 35; this is not a publicly usable workaround for this regular app.
