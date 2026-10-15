# 隔空抓取｜完整開發與審查紀錄（原始碼階段）

**分支**：\`experiment/global-grab-code\`  
**日期**：2026-10-16  
**嚴格限制**：只允許 \`:app:testDebugUnitTest\`；沒有 \`assemble\`、打包、簽章、上架或產生 APK。

## 使用體驗

1. 兩支 Android 安裝已授權的同一 App；首次啟動先授予相機、附近裝置和（選用）懸浮視窗／無障礙權限。
2. 必須先**從可見的 App 畫面**啟動前景攝影機服務，系統通知持續顯示。背景不能任意重新啟動受 while-in-use 限制的攝影機服務。
3. 切換到相簿、網頁、YouTube、社群軟體或 Office App，不需保持隔空抓取 App 在螢幕前景。
4. 張掌→握拳時才讀取目前 App 可存取的畫面樹：
   - 支援的瀏覽器：若能讀到**完整且可信**的 http/https 網址就直接傳網址。
   - 其他已列入白名單的 App：僅在分享按鈕**唯一且可辨識**時，嘗試透過 AccessibilityService 點擊原 App 的分享流程，再辨識系統 Sharesheet 中我們的 App；無法唯一判定立即停止。文件優先使用可編輯檔案副本。
   - 分享選單給出 \`ACTION_SEND.EXTRA_STREAM\`：從系統授權 URI 複製檔案後用 Nearby 原檔傳輸。
   - 分享選單給出 \`ACTION_SEND.EXTRA_TEXT\`：只接受**唯一可驗證網址**，不根據標題猜測 YouTube／社群連結。
   - 前兩種失敗時，**不自動截圖**；只有使用者主動點擊持續通知「手動截圖備援」，才截取目前畫面並明確標註「截圖，不是原檔」。
5. 接收端握拳→張掌開始接收。收到網址時通知使用者開啟（Android 的背景 Activity 啟動可能被限制）；收到檔案時校驗 SHA-256 及檔案大小後儲存在 Downloads。
6. 配對必須兩台都人工比對 Nearby 的驗證數字；**沒有**實作持久裝置公鑰釘選，因此斷線重新配對仍需人工確認，不會根據裝置名稱自動信任。

## 支援類別、已編寫策略、限制

| 類別 | 範例 | 已編寫的邏輯 | 必須實機驗證 |
|---|---|---|---|
| 相片 | Samsung Gallery、Google Photos | 尋找唯一分享入口，取得有授權的 URI | 相簿分享選單、原圖品質與權限 |
| 檔案 | Samsung My Files、DocumentsUI | 官方分享 URI，再傳送位元組原檔 | 單選／多選行為，檔案大小 |
| PDF | Acrobat 等 | 嘗試分享副本；SHA-256 驗證 | 分享副本的功能名稱及位置 |
| Office | Word、Excel、PowerPoint | 優先尋找分享副本 | 部分版本可能只給連結；不能保證自動匯出 |
| Google 雲端文件 | Docs／Sheets／Slides | 先找「分享與匯出」，再找「傳送副本」 | 原生匯出格式選擇需要各 App 版本的 UI 測試 |
| 瀏覽器 | Chrome、Samsung Internet 等 | 有效網址列優先；找不到則嘗試官方分享 | 網址列可能只顯示縮寫網址 |
| 影片 | YouTube、TikTok | 嘗試觸發官方分享，提取唯一網址 | 原生 App 控制元件可能不暴露、可能跳自訂 UI |
| 社群 | Facebook、Instagram | 嘗試分享並解析唯一官方連結 | 私密貼文、帳號權限及自訂分享 UI |

**不保證可一抓就拿到其他 App 的原始檔案**；Android 並未向第三方 App 提供可跨任意 App 直接讀取前景原檔的通用權限。使用 ShareSheet 是否能交出原檔，完全取決於原 App。

## 背景服務與低耗電

- \`GlobalGestureService\`：攝影機 Foreground Service。切至其他 App 後維持手勢辨識；熄屏解除相機分析綁定。
- \`BackgroundTransferManager\`：Nearby 連線、配對、待接收請求、FILE / BYTES 訊息、驗證與錯誤處理都由服務擁有，而不是 Activity。
- \`AdaptivePowerPolicy\`：ECO 模式最多約 1 次／秒推論；見到手後進入約 8.7 次／秒 ACTIVE 模式；4.5 秒後恢復。
- 相機要求 320×240 分析，只有裝置宣告支援 <=15fps 的 AE 模式時，才嘗試要求該範圍。
- 無配對裝置時 Nearby 採約 18 秒搜尋、12 秒暫停的循環；已連線時停止搜尋並維持連線。
- \`GestureStatusOverlay\`：選擇性「顯示在其他 App 上方」授權；只顯示短暫、不攔截觸控的抓取與傳送提示。
- **重要**：低頻 AI 推論不代表相機感光元件低耗電，也不等於續航通過。Android 可能因系統限制停止服務，不能保證全天不間斷運行。

## 安全與隱私

- 僅手勢觸發後短時間檢視前景視窗；不持續擷取畫面或錄影。
- 白名單 + 唯一分享按鈕才點擊，不對未知 App 嘗試任意坐標點擊。
- 不主動調整 Google Drive／OneDrive 的雲端分享權限。
- URL 必須是可驗證的 http/https，分享文字中只能有唯一連結；不能透過猜測 YouTube 標題組成 URL。
- 接收檔案上限 256 MB，驗證 SHA-256 與位元組長度；內容不相符不能顯示成功。
- Nearby 配對時需人工比對安全驗證碼，沒有假裝已完成信任裝置記憶／公鑰固定。
- 畫面截圖需明確操作，且要有無障礙權限；不能宣稱截圖等於原始檔。
- 背景網址不強行繞過 Android 的背景啟動畫面限制，只顯示「點擊開啟」通知。

## 原始碼測試

- GitHub Actions **Global Grab Code Tests Only (No APK)** 只執行 \`:app:testDebugUnitTest\`，會編譯 Kotlin／Java 測試所需類別，但**不打包或產生 APK**。
- 目前有 5 組共 **26 個 JUnit \`@Test\` 案例**（GestureSequence、GlobalGrabCore、ShareAutomationPolicy、SharedTextPolicy、TransferPolicy）。
- 編譯與單元測試通過不代表實機功能已驗證；S25／S26 Ultra 的前鏡頭穩定度、權限系統、背景攝影機存活率、Office 匯出和 YouTube 分享都需後續實測。

## 在編譯 APK 之前仍需確認

1. 先依照使用者要求審查本分支原始碼；不要將其合併進既有主分支或生成 APK。
2. 本地 Android 官方 API／無障礙合規審查（特別是 Android 16 三星機背景相機）。
3. 設定兩支測試手機，分別檢查 S25 和 S26 Ultra 的張掌握拳辨識、短暫漏幀。
4. 個別測試 Samsung Gallery、My Files、Chrome、YouTube、Google Docs／Sheets／Slides、Office、Facebook、Instagram、TikTok。
5. 實測待機發熱、耗電量、長時間錄製資源競爭、熄屏／亮屏恢復。
6. 設計穩定、可驗證的長期裝置公鑰釘選；目前每次新配對都需人工比對。
7. 使用者同意後才執行 APK 編譯、原機安裝測試、簽章驗證。

