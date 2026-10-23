# 懸浮操作 v1.0.38：直接整合手機本地 AI（Qwen3 0.6B INT4）

## 使用者要求
在既有「懸浮操作」App **直接整合免費的手機本地 AI 翻譯**，不要做獨立測試 App；保留 v1.0.37 的英文／日文語音優先、OCR 備用、最多兩行字幕／第三句上捲、NFC／Nearby／手勢、原簽章與只有按「返回桌面 · 開始使用」才退背景。

## 真正的推論架構
- LiteRT-LM 官方 Android SDK `com.google.ai.edge.litertlm:litertlm-android:0.18.0`，使用 `Engine`、`EngineConfig`、`ConversationConfig`、`conversation.sendMessage` 執行 **本機模型推論**。
- 模型：`litert-community/Qwen3-0.6B-int4` 中的 `qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm`。INT4 量化，約 347 MB、Apache 2.0，`no-think` 版本對逐句字幕更省時間。CPU 後端優先降低 GPU 跟遊戲搶資源的風險。模型**不塞進 APK**、**不呼叫付費雲端 API**。
- 使用者在主 App 的「本地 Qwen AI 中文潤飾」區域按**「下載／重新下載本地 AI 模型」**，透過 Android DownloadManager Wi-Fi 下載；下載進度可在設定頁顯示，下載完需重新啟動翻譯以使用。
- 模型存在專屬 App external files Downloads 目錄，第一次推論前用官方 Hugging Face 提供的 SHA256 校驗：`2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139`。長度需落於 320～380 MB，校驗後記住長度與修改時間，避免每次字幕都重算雜湊。
- **不強制使用**：設有直接可開關的切換；不下載模型時仍完整保留 ML Kit 原字幕、不會阻止影片播放或等待。
- 只有英文與日文 Vosk 判定的**完整句**才進本地 AI；部分句仍先由 ML Kit 翻譯並立即顯示，維持 520ms 輪詢和約 750ms 更新節奏。
- 生成提示會帶入來源語言、該句原文、ML Kit 的快速譯文和前一句中文字幕，要求一行簡短、自然的台灣繁體中文。低溫度採樣和輸出長度限制 90 token；AI 輸出經基本驗證，長篇解說、沒有中文、過長文字都被拒絕。
- 推論在獨立執行緒，**絕不卡 Android 主執行緒**；原 ML Kit 中文字幕顯示後，AI 只透過同一個 `utteranceId` 修正**已顯示的那一行**，不會把它當第三句／新句。舊句被移出兩行後，延遲回來的 AI 結果會被 `SubtitleTwoLineBuffer` 拒絕，不能污染最新字幕。
- 模型下載網路用於取得權重，推論本地，不會送出語音辨識文字到外部服務。App 離開翻譯工作階段時透過引擎執行緒關閉推論資源，保留已下載模型。
- 如果 AI SDK／模型／初始化失敗或手機負載吃緊，本地 AI 狀態會顯示錯誤，已運行的 ML Kit／Vosk／OCR 字幕不受影響。

## 版本與測試
- `versionName = 1.0.38`, `versionCode = 51`，套件 ID `tw.airgesture.transferbeta`，永續正式簽章密鑰沿用原 GitHub Secret `AIRGESTURE_SIGNING_BUNDLE_B64`。ARM64 APK，用於 Samsung S26 Ultra 和 S25。
- 新增 `SubtitleLocalAi.kt`、`SubtitleAiOutputPolicy.java`，JUnit 測試驗證模型修正不創建第三行、拒絕無中文／冗長 AI 輸出。CI 檢查 Android LiteRT-LM 依賴、真正引擎初始化、下載按鈕、ML Kit fallback、唯一使用者退背景路徑、ABI 和正確簽章。
- **實機測試**：安裝 APK 後先按模型下載，用 Wi-Fi 取得完整 347 MB。重啟懸浮翻譯，播放 YouTube 英文／日文影片，應先出現 ML Kit 初稿，再由 AI 更新同一句繁體字幕。第三句只上捲第二句與第三句。切換影片、暫停／恢復、長時間 10 分鐘、NFC／Nearby／手勢需測試。
- **尚未驗證**：此處 GitHub Actions 只能測試可編譯與 JVM 單元邏輯，不能替代 S26 Ultra 實機測試 LiteRT-LM 首次初始化、模型下載速度、某些極短句的中文品質與長時間記憶體／熱量表現。

## 官方來源
- https://developers.google.com/edge/litert-lm/android
- https://huggingface.co/litert-community/Qwen3-0.6B-int4
