# 懸浮操作：更新發布與 FCM 推播（尚未啟用、未產生 APK）

## 發布規則（優先）
- 平常修改程式碼只跑 `Floating Control Code Tests Only (No APK)`。
- 使用者**明確要求「發布更新」**時，才更新 `versionCode/versionName` 並在 GitHub Actions 手動執行 `Manually Publish AirGesture Update and Send FCM`，輸入 `PUBLISH`。
- **測試機：** `v1.0.8-beta.1` 這類 pre-release → FCM topic `airgesture-test`。
- **正式機：** `v1.0.8` 這類 stable release → FCM topic `airgesture-stable`。
- 自動檢查/下載**預設關閉**；必須每台裝置自行選擇頻道並同意。
- GitHub Actions artifact 不是更新來源；GitHub Releases 的 `app-release.apk` 才是。每個 release tag 只允許發布一次，不覆寫。

## 需要使用者在 Firebase / GitHub 設定一次
1. 建立 Firebase 專案，加入 Android App：`tw.airgesture.transferbeta`。
2. 在 Firebase Android `google-services.json` 找到**公開的客戶端識別資訊**，設定 Repository Variables：
   - `FIREBASE_ANDROID_APP_ID`：`mobilesdk_app_id`，範例 `1:123:android:abcdef`
   - `FIREBASE_ANDROID_API_KEY`：`current_key`，並妥善限制 API key 使用範圍
   - `FIREBASE_SENDER_ID`：`project_number`
   - `FIREBASE_PROJECT_ID`：`project_id`
3. 啟用 Google Cloud 的 **Firebase Cloud Messaging API**，在 Firebase/Google Cloud 建立有最小 FCM 發送權限的服務帳戶（Firebase Cloud Messaging API Admin）。將**服務帳戶 JSON**加入 Repository Secret `FIREBASE_SERVICE_ACCOUNT_JSON`，**不能放進 App、APK、程式碼、聊天或公開 repo**。
4. 確認原本 `AIRGESTURE_SIGNING_BUNDLE_B64` GitHub Secret 仍有效；若簽章金鑰改變，舊 App 不可直接覆蓋安裝。
5. 以上不完整時，不可啟動自動推播發布工作；缺少客戶端設定的測試建置仍能編譯，但無 FCM 功能。

## 手機上一次性的設定
- 首次裝上包含更新器的新 APK，進入「懸浮操作 → 進階設定」。
- S26 Ultra：選「自動更新 → 測試機」；太太 S25：選「自動更新 → 正式機」。
- App 以 FCM 發送**只包含 `action=check_update`、`channel` 的提醒**，不接受推播內網址，避免任意程式下載。
- App 只在非計量網路執行 WorkManager 更新檢查與下載，每 24 小時一次；漏掉推播時補查。
- 下載後仍驗證官方 GitHub Releases 來源、SHA-256、套件名稱、原本 APK 簽章、versionCode 遞增。
- 驗證成功才通知「點擊確認安裝」。Android 可能要求允許從此來源安裝，且仍可能要求確認。
- **不能保證完全靜默安裝**；不使用無障礙權限代替使用者批准。
- 無 Google Play services 或被 Android 強制停止 App 時，FCM 不一定能送達；手動開 App 可再檢查。
- 這是公開 GitHub repository；**Releases/測試版 APK 也是公開可下載**。如果要只允許私人裝置下載，必須改為有權限控管的私有分發，而不是公開 GitHub Releases。
- Topic 訂閱不是裝置身分驗證，請勿經由此管道推送機密資料。

## 驗收
- 程式碼測試成功後才請使用者安裝一次帶更新器的版本。
- 測試「先只推測試機」、驗證下載與 Android 安裝確認、離線/行動數據/螢幕關閉條件。
- 正式機版**須等測試完成，由使用者明確要求發正式更新**才推送。
