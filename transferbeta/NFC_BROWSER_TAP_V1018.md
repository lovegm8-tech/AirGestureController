# 懸浮操作 v1.0.18 — Chrome 雙向 NFC 觸碰分享實驗

## 新增
- 兩台手機安裝同一個 v1.0.18 (versionCode 31) APK，沿用 `tw.airgesture.transferbeta` 原簽章。
- **發送端**：保持 Chrome 正在顯示要分享的網頁，啟動懸浮操作背景服務並連到信任裝置。
- **接收端**：啟動同一 App 的傳輸背景服務，Nearby 保持驗證連線；螢幕解鎖並開啟 NFC，不需要進 App 首頁。
- 發送端 Android HCE 背景模擬 NFC 卡片；接收端 Android TECH_DISCOVERED 嘗試接管 NFC ISO-DEP 讀卡事件。
- 真正碰觸時，使用 NFC 實體接觸短期 nonce、既有 Nearby 驗證身份、單次指定對象的 URL 送出與自動接收。
- 遠端拉取 URL 時走既有 Chrome 無障礙分享流程：Chrome 地址列 → 當前網頁卡片分享 → 系統分享「懸浮操作」。不要求使用者先手動按分享；分享自動化是否在該 One UI/Chrome 版本運作，仍須實機驗證。
- NFC 不傳網址全文，資訊在經驗證的 Nearby 通道傳輸；既有手勢抓取、檔案分享及 NFC 手動分享不變。

## 重要限制（不得宣稱已實測）
1. 在 Android 中，第三方 NFC ReaderMode 不能背景啟用。本實驗改用 `ACTION_TECH_DISCOVERED` 系統派送，不保證 Samsung One UI 一定派送 HCE ISO-DEP 事件給我們的 Activity。系統可能出現 App 選擇或完全不觸發。
2. **手機角色取決於實際 NFC 讀卡/模擬分配**。若兩台都打開 Chrome，無法只靠 NFC 判斷誰要傳網址。建議測試時一次只有發送端開 Chrome；接收端維持首頁。
3. 目前**只針對 Chrome**啟用此背景發送功能；Samsung Internet 和 Google App 使用原本手勢分享功能。
4. 偵測到 Chrome 不等於已取得網址；既有 Chrome 分享自動化若遇到不可辨識介面，必須失敗而不是猜連結。
5. 手機必須解鎖、NFC 開啟、服務正在運行且 Nearby 已連線；HCE 卡片、Samsung Wallet 優先權、天線位置和跳出 chooser 均待實機測試。
6. 此流程不截圖、不持續監看 Chrome 內容、不取得私人網頁內容，必須有 NFC 讀卡接觸和信任裝置的 nonce 驗證。
7. 沒有 APK 實機通過之前，這是技術實驗而非穩定版。

## 雙向驗收
- 第一輪：S26 Ultra Chrome 開普通 https 網頁，S25 在主畫面。兩台都解鎖、NFC 開啟、懸浮操作背景服務保持啟動且互相驗證連線，背面靠近 NFC 位置。
- 第二輪：角色完全交換，S25 開 Chrome，S26 Ultra 在主畫面。
- 查看兩台「懸浮操作」的傳輸狀態：NFC 已碰觸 → 正在要求 Chrome 分享 → 正在傳給指定接收者 → 接收端收到網址。
- 任何情況不要將未見到 NFC 成功視為傳輸成功。若兩機完全沒反應，優先調查 TECH_DISCOVERED 派送與 HCE，而不是 Chrome 網址擷取。
