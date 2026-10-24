# 懸浮操作 v1.0.43 — 本機 SenseVoice ASR 比較版

建置基線：v1.0.42。這個版本不改動 NFC、Nearby、手勢、既有 Qwen、ML Kit、OCR 優先及更新機制。請先把「隔空翻譯」停止，再調整辨識引擎。

## 體驗步驟
1. 首頁選「SenseVoice INT8 AI（英文日文韓文）」；按「下載 SenseVoice INT8 模型」下載 k2-fsa 官方約 163 MB 壓縮包。
2. Android 下載管理員顯示完成後，返回首頁按「驗證並安裝」；先核對 SHA-256，再解壓至 App 私有儲存空間。模型本體約 228 MB，安裝期間需有數百 MB 空間。
3. 設定英文，開語音優先及懸浮翻譯，播放同一段測試影片。字幕以 SenseVoice 辨識原文→ML Kit 翻譯，Qwen（若原本啟用）只做後續修正。
4. 同一部影片改回 Vosk 重播，比較現場診斷的原文、錯詞、台詞出現時間與耗電。
5. SenseVoice 模型未安裝時，英文／日文會回退 Vosk；韓文則維持 OCR。新引擎並非預設強制啟用。

## 目前的技術權衡
- SenseVoice 為 **離線非串流模型**。首版使用 2.8 秒非重疊片段，每次另加推論與 ML Kit 翻譯耗時；這可能比 Vosk 慢，先用實機診斷測準確率、吞吐與發熱。**不保證低延遲**。
- 只處理其他 App 允許擷取的媒體音訊，使用 Android MediaProjection + AudioPlaybackCapture，**不開麥克風錄環境音、不上傳音訊**。
- 音訊處理佇列有上限。若 CPU 追不上播放速度會跳過過期音訊，避免字幕越積越慢；可能造成漏句，應在實機驗收。
- v1.0.43 **沒有加入 Moonshine 第二個推論引擎**。先驗證 SenseVoice，再決定是否加入 Moonshine 英文串流模型，避免同時引入兩個未驗證的高記憶體依賴。
- Android Native sherpa-onnx AAR 使用官方 v1.13.8，建置時 SHA-256 校驗；不將外部模型權重或第三方二進位檔提交公開儲存庫。
- 模型內含第三方授權條款。對外散布模型與商用之前須另外確認上游模型權重許可。
- 沿用 `tw.airgesture.transferbeta`、正式簽章及 ARM64，版本號 v1.0.43 / versionCode 56。

## 驗收條件
- GitHub Actions 的測試／簽章流程通過，並確認安裝新版不清除資料。
- S26 Ultra 可按鈕下載、SHA 驗證、成功安裝並載入 SenseVoice。
- 相同影片比較 Vosk 與 SenseVoice 的辨識原文以及字幕延遲。
- 30 分鐘連續播放不崩潰、不異常發熱，NFC、Nearby 和手勢不回歸。
- 未在裝置驗證前，本版本僅列為 **Draft 實驗版**。
