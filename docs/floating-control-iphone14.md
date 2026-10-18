# 懸浮操作 ↔ iPhone 14 跨平台無損傳輸（來源碼階段）

## 正確開發目標與位置
- Android 不是 \`app/\` 裡的舊 AirGestureController，而是 \`transferbeta/app/\`。
- Android 套件 \`tw.airgesture.transferbeta\`，現有程式來自 \`experiment/floating-control-integration\`，包含手勢與隔空抓取。
- 新的 iPhone LAN adapter 放於 \`transferbeta/app/src/main/java/tw/airgesture/transferbeta/ioslink/\`。
- iPhone 端放在 \`ios/\`；需 XcodeGen + Xcode + Apple App Groups 簽章設定。
- 此功能在獨立開發分支，尚未合併舊版或編譯任何 APK/IPA。

## 流程
1. S26 Ultra 開啟現有「懸浮操作」App → 進階選單 → iPhone 14 配對/無損傳輸。
2. 雙方保持同一 Wi-Fi 或 iPhone 已加入 Android 熱點；iPhone 安裝獨立 iOS app 掃碼。
3. Android 在可見的配對頁面按「啟動 iPhone 雙向傳輸服務」；iOS 不啟用隔空控制。
4. Android 手勢張掌→握拳，在照片/文件/網頁/YouTube/Facebook 時沿用已有的 GrabAccessibilityService、ShareReceiverActivity、MultiPeerTransferManager。若 iPhone LAN 傳輸服務啟用，offer 同時進 LAN 暫存；若仍連線 Android 朋友，Nearby 仍照原本方式傳。
5. iPhone 前景進入「從 Android 接收」，更新 30 秒內的共享項目後點擊下載。
6. iPhone 從 Photos、Files、Chrome、YouTube、Facebook 透過系統分享延伸功能，先將來源提供的檔案或 URL 放入 iPhone App 私有分享箱，前景點「傳送」。
7. S26 Ultra 會把驗證過的圖片/影片放入 Android MediaStore，文件放入下載資料夾；由 iPhone 傳來的網址則出現在 Android 通知欄，使用者點擊後才開啟。

## Android 保留的既有功能
- MediaPipe 前鏡頭手勢、上下滑／點擊／返回與省電參數不變。
- 原 Google Nearby Connections 的多裝置配對、30秒多人接收流程保留。
- 不改動舊 Nearby 認證與更新簽章。
- 新 iPhone 網路服務**必須**由使用者在可見的配對畫面啟動；無手勢背景偷開服務。

## 新 LAN 協定
- TCP 48773、首次 QR 配對提供 256-bit AES-GCM 金鑰。
- 每一 frame 為 4-byte big-endian 長度 + 12-byte nonce + AES-256-GCM 密文及 16-byte tag。
- JSON 控制(type=1)、binary chunk(type=2，256 KiB)。照片、影片、檔案不轉碼。
- iOS/Android 使用 SHA-256 驗證完整檔案，傳輸端只在驗證成功後存放成品。
- iOS \`put_link\` 傳純 URL，Android 檢查後顯示可點開的通知。
- Android→iOS 與 Android→Android 路線不共用暫存物件，iOS 另存獨立副本，避免既有 Nearby 清理中斷傳輸。

## 尚待驗證／不可宣稱完成
- 本次沒有編譯 Android、iOS，沒有雙機速度/穩定性實測。
- 尚未加入斷點續傳；中斷後必須重送。
- iPhone App 必須保持前景，第一版僅 LAN/熱點、沒有不靠 Wi-Fi 的跨平台發現。
- Share Sheet 來源可能只提供網頁網址，不能把 YouTube/Facebook 線上影片當作原始影片檔案。
- iPhone Live Photo 需額外實作 PHAssetResourceManager 才能確保照片＋動態影片雙資源及原始 metadata；目前只能保證「提供者實際交付的檔案」位元相同。
- iOS Share Extension 跨 App 相容性、Xcode 簽章、Swift/Kotlin 編譯問題須另外驗證。
- LAN 複製大型檔案會佔用額外儲存空間；傳送超過當前 2GiB 限制或不足空間可能失敗。
- 必須在實機上測試鎖屏、切 App、連線中斷、多台接收，以及安全性與 URL 開啟行為；未完成前不要併入現有版本。
