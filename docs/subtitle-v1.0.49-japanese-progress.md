# v1.0.49 日語雙 AI 開發進度

## 目標
在不影響 v1.0.48 英文 Zipformer + SenseVoice 的前提下，為日語加入獨立串流辨識引擎；SenseVoice 繼續擔任日語最終確認。

## 已完成
- [x] 建立獨立 v1.0.49 開發分支，保留 v1.0.48 不受影響
- [x] 版本號更新為 1.0.49（versionCode 62）
- [x] App 內版本更新紀錄加入日語雙 AI 開發狀態
- [x] 新增「雙 AI 實驗」引擎選項；英文沿用 Zipformer＋SenseVoice，日文暫以 SenseVoice 辨識，避免尚未安裝日語模型時誤啟動英文模型

## 待開發及驗證
- [ ] 選定可在 Android sherpa-onnx 載入的日語串流模型；確認官方模型格式、授權、大小及模型來源
- [x] 日語 ReazonSpeech 官方 INT8 模型下載、解壓及本機狀態（尚未完成 SHA-256 驗證）
- [x] 日語 ReazonSpeech 離線辨識 PCM 共用接線與 SenseVoice 回退（模型屬離線分段辨識，非串流）
- [x] 日語第二引擎僅作預翻譯，SenseVoice 最終確認；待實測避免重複字幕
- [ ] ML Kit／Qwen 翻譯與長時間穩定性測試
- [ ] GitHub Actions release 簽章、S26 Ultra 實機測試

注意：目前 v1.0.49 只建立了開發基線；**已接入日語 ReazonSpeech 離線模型，仍待編譯與實機驗證**。不得把這個分支當成可交付雙 AI 日語 APK。
