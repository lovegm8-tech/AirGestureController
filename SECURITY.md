# Security and privacy

- Camera frames are processed locally; no video or photos are saved or uploaded.
- The manifest does not request INTERNET permission. CI checks the final APK for it.
- AccessibilityService injects touch gestures only; canRetrieveWindowContent is false.
- No advertising or analytics SDK is permitted.
- Never commit a keystore, private signing key, signing password or secret to this public repository or chat.
- Signing material must be created by the owner, backed up privately and supplied using GitHub Actions Secrets for a trusted manual build.
- Signed builds must use the same release key to allow APK updates.
- Until Android full build and device tests are complete, all functionality is experimental.
- CI third-party dependencies and external model supply chain require additional verification before a production release.
