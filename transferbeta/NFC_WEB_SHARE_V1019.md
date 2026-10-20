# 懸浮操作 v1.0.19：NFC 網頁分享（S26 Ultra ↔ S25）

## 功能範圍
- 同一份 APK 可雙向傳送。支援正在顯示網頁的 Chrome、Google App 內建網頁瀏覽器、三星瀏覽器。
- 發送端必須正在顯示要傳的網頁，兩台手機均已開啟「懸浮操作」背景服務、解鎖、NFC 開啟、Nearby 完成原有信任驗證。
- 接收端可停在主畫面。手機背面靠近 NFC 天線後，接收端嘗試由 Android `ACTION_TECH_DISCOVERED` 取得來源 HCE 發出的 **一次性 NFC nonce**，再透過已驗證 Nearby 向來源端請求分享。
- 來源端確認前景屬於三種允許的瀏覽器，驗證 nonce 只能使用一次，再呼叫現有無障礙網頁分享：Chrome 用當前網頁卡片；Google App 限上方工具列 Share；三星瀏覽器讀取可驗證的完整網址或走其分享選單。
- 只傳送單一完整 HTTPS/HTTP 網址，透過既有 Nearby 傳輸管道向碰觸的已驗證接收端發送。接收端自動接收；其他已配對裝置不能收到此次分享。
- 不改動一般張掌抓取、檔案分享、NFC 照片分享、APP 簽章或信任配對。

## 測試方法
1. S26 Ultra 和 S25 安裝同版 v1.0.19，兩台均啟動懸浮操作，確認 **Nearby 已連線、NFC 已開、螢幕解鎖**。
2. S26 Ultra 在 Chrome 開啟任意一般 HTTPS 網頁，S25 在主畫面。手機背面靠近 NFC 天線數秒，查看兩端懸浮操作狀態；檢查 S25 是否收到正確網址。
3. 換成 Google App 的 **網頁內頁**（不是 Google 搜尋結果或 Discover 列表）與三星瀏覽器網頁，重複。
4. 反向：S25 開瀏覽器當來源，S26 Ultra 在主畫面，重複三種瀏覽器測試。
5. 若 NFC 已發現而沒有 URL，檢查 Chrome／Google App／三星瀏覽器版本及分享控制列；若連 NFC 都沒有反應，問題較可能是 Android/三星的 HCE / ISO-DEP 系統事件派送。

## 已知硬性限制
- 不保證 Android/One UI 會在兩支手機都只開背景 App 時，把手機對手機的 HCE ISO-DEP 接觸透過 `ACTION_TECH_DISCOVERED` 自動交給一般第三方 App；若無系統 dispatch，無法用現在的非前景 ReaderMode 解決，必須實機驗證後改設計。
- Android 第三方 ReaderMode 需要其 Activity 保持前景，不能在其他三種瀏覽器當前景時當讀卡端。
- Google App 僅支援有上方分享工具列的網頁，不支援搜尋首頁自動猜測 URL。
- URL 必須可驗證；不會截圖、不會猜測未辨識頁面、不會掃描其他 App。
- 如雙機同時都開著瀏覽器，不能安全地由接觸本身判斷發送方向，測試時應明確分出來源端（瀏覽器）及接收端（主畫面）。
- 編譯測試不是 NFC 實機測試；這是實驗版，不能宣稱已確定一碰就傳。

## 版本
- `tw.airgesture.transferbeta`、versionCode=32、versionName=1.0.19
- 發行需使用原始 GitHub Actions 安全簽章資料，才能直接覆蓋已安裝的「懸浮操作」。
