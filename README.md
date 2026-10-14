# 隔空手勢控制器 AirGestureController v0.1.1（原始碼預覽）

目標手機：Samsung S26 Ultra（Android 16），可延伸至 Android 10 以上。**目前只有原始碼及純 JVM 邏輯測試，尚未編譯 APK，也沒有做手機實測。**

## 核心操作

| 前鏡頭手勢 | 手機觸控模擬 |
| --- | --- |
| 張開手掌向上移 | 從螢幕下方滑向上方 |
| 張開手掌向下移 | 從螢幕上方滑向下方 |
| 張掌後握拳 | 點擊螢幕中央 |

不是影音專用 App；運作時可切換到瀏覽器或其他一般 App。部分安全保護畫面與系統介面不一定允許模擬手勢。

## 設計

- **無廣告、無訂閱、無 `INTERNET` 權限**；相機畫面僅在手機本機以 MediaPipe 辨識，不錄影、不上傳。
- 使用 **CameraX + Camera 前景服務**，從 App 可見時按「啟動」，再切換到其他 App。相機使用狀態會出現在 Android 系統提示與持續通知。
- **Android AccessibilityService** 對螢幕發出真實觸控等效的 `dispatchGesture()` 操作；不讀取頁面文字（`canRetrieveWindowContent=false`）。
- **誤觸保護**：需連續辨識張掌；滑動需達距離門檻；握拳必須發生於張掌之後；每次觸發後必須手暫時移出辨識區／放鬆，並遵守冷卻時間。
- **可調整設定**：低／標準／高靈敏度、冷卻時間 500~1600ms。
- **資源控制**：640×480 輸入、約 9fps 推論、CPU 推論。
- **停止方式**：App 首頁「停止」或通知列「停止辨識」。

## 重要使用限制

1. 啟動相機前景服務時 App 必須**可見**（符合 Android 14 以上相機權限的限制）；不能只靠背景排程偷偷重新打開鏡頭。
2. 其他程式若正在使用前鏡頭，可能衝突。測試時請先把 Spatial Touch 完全停止，避免同時使用前鏡頭與無障礙手勢。
3. 目前固定點擊位置為整個顯示區域中心；無虛擬游標、無自訂按鈕位置。
4. 手勢辨識實際成功率、方向、延遲、效能和長時間背景運作尚未透過實機驗證。尤其前鏡頭視野與光線不同時可能誤判，需第一次 APK 測試後調整。
5. 未啟動時不使用鏡頭；啟動狀態下會耗電發熱。鎖定螢幕／其他 App 同時佔用相機可能使辨識中斷。
6. Android 13+ 如果拒絕通知權限，前景服務通知可能不在一般通知匣完整顯示，但系統任務管理仍可能顯示；建議允許通知。

## 工程內容

- `app/src/main/java/tw/airgesture/controller/MainActivity.kt`：設定與啟停操作。
- `app/src/main/java/tw/airgesture/controller/core/GestureDecisionEngine.kt`：不依賴 Android 的手勢狀態機。
- `app/src/main/java/tw/airgesture/controller/vision/GestureCameraAnalyzer.kt`：CameraX RGBA 影格 + MediaPipe 模型。
- `app/src/main/java/tw/airgesture/controller/service/CameraGestureService.kt`：前鏡頭前景服務與常駐通知。
- `app/src/main/java/tw/airgesture/controller/service/GestureAccessibilityService.kt`：全域模擬滑動／點擊。
- `app/src/test/`：JVM 單元測試；`dev/SmokeTest.kt`：可用 `kotlinc` 獨立執行的快速測試。

## 未來編譯步驟（GitHub Actions 額度恢復後才做）

1. 將整個專案放進新的 GitHub 儲存庫；建議私有儲存庫以便日後管理版本。
2. 在 GitHub Actions 手動觸發 **Build test APK (manual only)**。該 workflow **只接受 `workflow_dispatch`，不會因為 push 自動消耗額度**。
3. Workflow 使用 Java 17、Gradle 8.9、Android SDK 35 建置 `assembleDebug`，上傳 debug APK 作為 artifact。
4. Gradle `preBuild` 會從 Google 官方模型位置下載 `gesture_recognizer.task` 至資源檔後打包；**執行中的 App 不下載模型，也不需要網路權限**。
5. 手機安裝並授權無障礙和相機，從 App 內啟動後切換至瀏覽器／影音 App 進行實測。

> 目前 Actions 的 debug keystore 通常每個新的 CI runner 生成不同簽名。後續要長期直接覆蓋更新 APK 時，應先設置**固定簽章金鑰**，並以 GitHub Secrets 保存，不要把金鑰放入儲存庫。此處暫不宣稱可以直接覆蓋更新。

## 官方依據與模型來源

- Google MediaPipe Gesture Recognizer Android: https://ai.google.dev/edge/mediapipe/solutions/vision/gesture_recognizer/android
- Google Android foreground camera services: https://developer.android.com/about/versions/14/changes/fgs-types-required
- Google Android Accessibility gesture dispatch: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#dispatchGesture(android.accessibilityservice.GestureDescription,android.accessibilityservice.AccessibilityService.GestureResultCallback,android.os.Handler)
- Model: https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task

## 待實機測試的驗收項目

- 開鏡頭後切換到 Chrome / TikTok / YouTube 可維持辨識（相機權限與背景限制）。
- 張掌上移、下移每次只觸發一個滑動，不會因重新收手而意外反方向滑。
- 張掌握拳點擊畫面中心，長時間握拳不連點。
- 手遮擋、低光線、手部潮濕、手機直橫切換時的穩定性。
- 20~30 分鐘使用期間的電量、發熱與溫度限制。
