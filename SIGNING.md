# Fixed release signing

The permanent Android signing key is intentionally **not** in this public repository.
The owner keeps the original keystore, password, and off-GitHub backup. To enable APK signing:

1. Open **Settings → Secrets and variables → Actions** for this repository.
2. Add a **New repository secret** named `AIRGESTURE_SIGNING_BUNDLE_B64`.
3. Paste the **entire single-line secret** from the privately supplied `GITHUB_SECRET_AIRGESTURE_SIGNING_BUNDLE_B64.txt`. Never paste that text into any source file, issue, pull request, chat screenshot or build log.
4. Open **Actions → Android APK - permanently signed release → Run workflow**.
5. Select `signed-release` and run on `main`.
6. Once the run succeeds, download the `AirGesture-v0.2.0-permanent-signed` artifact and extract the APK.

**Public verification fingerprint (SHA-256)**:
`05e77197d2ad73ac4a255e4c40b868a5c26c539b9aea3c1abd53100ff484f19e`

The workflow verifies both the pinned release signing certificate and absence of the `android.permission.INTERNET` permission. The keystore is written only to the runner temporary folder and deleted once signed. This project intentionally has **no ads or analytics SDKs**.

**Do not lose your signing backup.** Future versionCodes can be installed as updates only if the APKs are signed by the same key. The presence of a signing key alone does **not** imply an APK has been compiled or device-tested.
