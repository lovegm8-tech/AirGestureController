# 懸浮操作 v1.0.48｜雙 AI 翻譯開發進度

> 此文件記錄程式碼狀態，不代表已通過 Samsung S26 Ultra 實機測試。版本進度也會顯示於 App「查看版本更新紀錄／更新進度」。

## 已實作（待完整驗證）
- [x] Zipformer 英文模型下載、安裝與狀態按鈕
- [x] SenseVoice 與 Zipformer 共用播放音訊 PCM，不額外開啟麥克風
- [x] Zipformer 串流英文預辨識，SenseVoice 作為最終字幕依據
- [x] ML Kit 預翻譯結果可依相同原文跨引擎重用，設 4.5 秒有效期限
- [x] 最終確認後丟棄較舊的預翻譯結果
- [x] Zipformer 預翻譯最短間隔 1.2 秒，降低佇列壅塞
- [x] 沿用本地 Qwen 中文潤飾、原有 OCR／語音回退路徑
- [x] 未修改 NFC、Nearby、前鏡頭手勢的既有邏輯
- [x] 固定簽章 GitHub Actions 編譯流程及版本更新紀錄

## 尚待確認／實測
- [ ] 官方 Zipformer 模型壓縮包在手機端下載、解壓及載入成功
- [ ] Zipformer／SenseVoice 長時間並行時字幕不重複、不漏句、不覆蓋
- [ ] 英文影片翻譯品質、端到端延遲及字幕分句實測
- [ ] 日文 SenseVoice 與原有 NFC／Nearby／手勢回歸測試
- [ ] 最新提交的單元測試、release 編譯、固定簽章與可覆蓋安裝確認

## 驗證與交付規則
GitHub Actions 的成功只代表對應提交的自動測試及編譯通過，不能替代手機實機測試。完成最新提交的簽章 APK 並確認版本、簽章及可下載後，才標示為「可交付測試版」。

[GitHub Actions｜v1.0.48](https://github.com/lovegm8-tech/AirGestureController/actions/workflows/subtitle-v148-signed.yml)
