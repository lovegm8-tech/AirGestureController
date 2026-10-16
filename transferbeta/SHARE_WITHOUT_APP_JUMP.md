# AirGrab｜傳送時不跳回主畫面（原始碼修正）

## 使用者回報
Samsung A9+／S26 Ultra 使用全域抓取時，一旦原 App 的分享流程把照片／文件交給 AirGrab，畫面會跳回 AirGrab 首頁。

## 已查明的原因
原 AndroidManifest 將 `ACTION_SEND` 分享入口和桌面 `MAIN/LAUNCHER` 放在同一個 `TransferActivity`，所以系統 Sharesheet 直接啟動主畫面。這與全域隔空抓取「在來源 App 裡完成」的 UX 相反。

## 已修改
- `TransferActivity` **只提供桌面入口**；不再接受 Android Sharesheet 的普通分享。
- 新增 `ShareReceiverActivity`：獨立空 task affinity、透明無標題視窗、不進最近使用清單。接到 `ACTION_SEND` 時不顯示 AirGrab 首頁。
- 可讀取的單一 `content://` 原始檔在接收權限有效期間複製到 App 私有暫存資料夾（最大 256MB），再交由背景中的 `GlobalGestureService.transfer.offerFile` 發送。完成讀取後立即關閉透明接收頁面，讓來源 App 保持在前景。
- 單一有效網址經 `SharedTextPolicy.onlyWebLink` 確認後，直接透過背景服務送出，不需啟動主畫面。
- 分享時若服務沒有啟用或裝置未連線，短暫顯示提示，避免誤認成功。
- 對舊版 ShareSheet 可能留存的 `TransferActivity` 明確入口，加上一個立即轉接到新接收頁面的相容路徑。
- 新增 `ShareEntryPolicyTest`：檢驗分享 action、檔案 URI scheme 以及僅單檔案的限制。

## 仍待兩台 Android 裝置確認
- 系統 Sharesheet 可能短暫顯示；這是 Android 分享流程的一部分，不代表 AirGrab 主畫面被打開。
- 廠牌分享選單及原 App 的 task 恢復行為可能不同，尚待 A9+ 和 S26 Ultra 實機驗證。
- 對雲端檔／需長時間下載的內容，透明接收頁面會等原始檔複製到私有暫存資料夾才關閉，不會偷換成螢幕截圖。
- 若原 App 沒有提供有效原始檔 URI 或網頁連結，仍會回報不支援，不擅自猜測來源。

**本次只修改原始碼並執行 Kotlin／Java 單元測試；沒有產生 APK。**
成功的 Actions 紀錄：https://github.com/lovegm8-tech/AirGestureController/actions/runs/38854059328
