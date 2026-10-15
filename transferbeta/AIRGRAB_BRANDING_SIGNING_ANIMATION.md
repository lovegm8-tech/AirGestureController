# AirGrab｜圖示、動畫、簽章（只準備程式碼，尚不編 APK）

## 固定簽章與更新
- 應用程式名稱：AirGrab 隔空抓取。
- applicationId 維持 `tw.airgesture.transferbeta`；目前原始碼 `versionCode=4`，高於已發行 v0.1.2 的版本碼 3。
- 沿用原本 GitHub Secret `AIRGESTURE_SIGNING_BUNDLE_B64` 與 alias `airgesture`，不新建金鑰、不上傳 keystore 或密碼。
- 預期簽章 SHA-256：`05E77197D2AD73AC4A255E4C40B868A5C26C539B9AEA3C1ABD53100FF484F19E`。
- `.github/workflows/airgrab-signed-release.yml` 僅能手動觸發，且必須輸入 `BUILD_AIRGRAB_APK`；**此階段不執行**。
- v0.1.2 固定簽章版未來可在同套件名稱／同憑證／較高版本碼下更新。v0.1.1 臨時 Debug 簽章不能直接更新。

## Android App 圖示
Android Adaptive Icon：深藍底、藍色無線弧線、青色手掌、照片和文件；附單色版供支援系統主題的桌面顯示。

## 動畫標準
所有動畫均使用短暫、不攔截觸控的系統懸浮視窗，只有事件發生才播放，不設常駐旋轉動畫。

| 事件 | 色彩與呈現 | 條件 |
|---|---|---|
| CAPTURING | 綠色呼吸圓環「正在抓取」 | 完整抓取手勢已辨識 |
| CONTENT_READY | 綠色勾號由中心放大「已抓取」 | 實際已取得有效網址或 SHA-256 雜湊完成的檔案 |
| OFFER_RECEIVED | 藍色訊號「對方等待接收」 | 對方要求有效且已連線 |
| RECEIVING | 藍色向下箭頭＋檔案進度條 | 接收手勢確認、檔案資料傳入 |
| VERIFYING | 藍色驗證圓環 | 下載完成後開始檢查位元組與 SHA-256 |
| RECEIVE_COMPLETE | 藍色勾號展開「接收成功」 | 文件儲存並比對成功，或網址確認接收 |
| SEND_COMPLETE | 綠色勾號「對方已收到」 | 收到對端的驗證成功回覆 |
| FAILURE | 紅色警示，不顯示成功 | 逾時／拒絕／讀取或驗證失敗 |

淡入 260ms，縮放 420ms；已抓取提示約 1.4 秒，接收成功約 1.9 秒。接收中進度條在數據更新時維持。沒有其他 App 上層顯示權限時不會強行顯示。

點擊分享按鈕僅屬於「抓取中」，**絕不等於已抓取**。真正能否取得原始檔／網址仍要實機逐 App 測試；耗電與背景相機穩定度也尚未實測。
