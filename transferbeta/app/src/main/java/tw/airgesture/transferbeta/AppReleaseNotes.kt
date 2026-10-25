package tw.airgesture.transferbeta

/**
 * Built-in release notes for offline use. Every signed build must include an
 * entry for its exact BuildConfig.VERSION_NAME; enforced by JUnit + CI.
 *
 * The entries describe what was IMPLEMENTED in each test release; they are
 * not a claim that each feature has passed Samsung S26 Ultra device testing.
 */
internal object AppReleaseNotes {
    internal data class Entry(val version: String, val summary: String)

    val entries: List<Entry> = listOf(
        Entry("1.0.53", "NLLB 離線翻譯開發版：加入 ONNX Runtime 模型載入檢查與 SHA-256 模型檔驗證，保留原有 NFC／Nearby／手勢及 ML Kit 翻譯。NLLB Tokenizer、實際推論與字幕整合尚未完成。"),
        Entry("1.0.52", "修正 Zipformer 長句持續累積，過濾日語孤立詞尾與標點；英日雙 AI 自動連動沿用 v1.0.51。"),
        Entry("1.0.51", "主畫面選英文自動啟用 Zipformer＋SenseVoice＋本地 Qwen；選日語自動啟用 ReazonSpeech＋SenseVoice＋本地 Qwen，保留韓語設定。未安裝模型顯示狀態，仍需 S26 Ultra 實機驗證。"),
        Entry("1.0.50", "字幕診斷文字可長按選取或一鍵複製全部，方便禁止截圖的影片回報；日語 ReazonSpeech 離線雙 AI 改善音訊佇列與分段記憶體上限，保留 SenseVoice 最終字幕及英文/NFC/手勢功能，待實機驗證。"),
        Entry("1.0.49", "日語雙 AI 測試版：加入 ReazonSpeech 日語 INT8 模型下載／安裝／狀態、日語離線辨識與 SenseVoice 共用音訊，SenseVoice 保留最終字幕權限；英文雙 AI 維持不變，仍待編譯與實機驗證。"),
        Entry("1.0.48", "雙 AI 測試進度：已加入 Zipformer 英文模型下載／安裝／狀態檢查，與 SenseVoice 共用播放音訊；Zipformer 預翻譯與 SenseVoice 最終確認可依相同原文重用結果，加入 4.5 秒有效期限、過期結果防護及 1.2 秒預翻譯節流。保留 ML Kit／Qwen、NFC／Nearby／手勢；雙引擎模型啟動、字幕品質與 S26 Ultra 實機效果仍待驗證。"),
        Entry("1.0.47", "新增英文 Zipformer 串流辨識實驗及共用 SenseVoice 音訊管線，模型需自行安裝；雙 AI 尚待實機驗證。"),
        Entry("1.0.46", "語音部分辨識先在背景預翻譯，確認語句後立即顯示匹配的中文結果；歷史字幕降低不透明度，SenseVoice 最長切段縮短至 2.4 秒。"),
        Entry("1.0.45", "SenseVoice 停止時保留有效音訊尾段並處理待辨識佇列，加入停止期限及關閉後字幕回傳防護；尚待 S26 Ultra 實測。"),
        Entry("1.0.44", "SenseVoice 改為停頓感知的有界切段，降低固定切句；阻止過期 Qwen 修正覆蓋新字幕，修正延遲診斷說明。"),
        Entry("1.0.43", "新增可切換 Vosk／SenseVoice INT8 離線語音辨識，模型下載經 SHA-256 驗證；保留舊引擎回退，英日韓分段語音翻譯進入實測。"),
        Entry("1.0.42", "新增翻譯逐階段診斷紀錄，可信的畫面原文字幕可優先翻譯；Qwen 過期結果不得蓋掉新字幕。"),
        Entry("1.0.41", "輕量偵測播放音訊停頓，輔助語音片語定稿與字幕換行；Qwen 最短 7 秒接受一次新潤飾，減少持續高負載。"),
        Entry("1.0.40", "語音長段落提前切成可讀片語，改善舊字幕卡住及翻譯延遲；OCR 與語音共用本地 Qwen 校對、兩行字幕修正更穩。"),
        Entry("1.0.39", "本地 AI 模型下載支援手機熱點與行動數據；新增從手機檔案離線匯入、模型檢查和下載錯誤提示。"),
        Entry("1.0.38", "整合離線 Qwen3 0.6B AI 中文潤飾，App 內可下載約 347 MB 模型；ML Kit 先顯示，AI 再修正完整句，保留快速翻譯備援。"),
        Entry("1.0.37", "中文字幕固定最多兩行：新句進來舊句上捲；同一句的暫時與最終翻譯更新原行，不再重複佔行。"),
        Entry("1.0.36", "英文／日文 Vosk 穩定部分辨識改約每 520 毫秒檢查；翻譯忙碌時保留最新候選，改善漏句與顯示延遲。"),
        Entry("1.0.35", "英文／日文預設語音優先翻譯，無語音結果時以 OCR 備援；啟動翻譯不再強制先框選字幕。"),
        Entry("1.0.34", "徹底取消 App 自動縮小；只有手動按「返回桌面 · 開始使用」才退至背景。"),
        Entry("1.0.33", "改善 App 自動縮小問題並新增影片 OCR 即時裁切影像預覽，協助定位辨識不到字幕的原因。"),
        Entry("1.0.32", "新增畫面擷取／OCR 診斷，改善小字放大與框選邊界；ARM64 精簡 APK，縮短字幕掃描間隔。"),
        Entry("1.0.31", "在 OCR 字幕翻譯外，實驗加入英文與日文播放音訊離線語音補位，保留授權擷取與手動辨識範圍。"),
        Entry("1.0.30", "新增可拖曳的常駐「譯」小球，支援重新框選、暫停／恢復翻譯與停止翻譯。"),
        Entry("1.0.29", "新增影片上手動拖曳及縮放字幕 OCR 框；直向、橫向分開記住辨識位置。"),
        Entry("1.0.28", "改善直向影片字幕定位，避免誤讀搜尋結果與推薦資訊；強化字幕區 OCR。"),
        Entry("1.0.27", "依直向與橫向影片實測調整辨識候選，減少翻譯推薦標題與漏字。"),
        Entry("1.0.26", "改善直向 OCR 漏字、跨區塊合併句子，並新增原文／中文翻譯診斷。"),
        Entry("1.0.25", "OCR 預設搜尋全螢幕，中文字幕依原字幕位置顯示在上方或下方；仍可手動拖曳。"),
        Entry("1.0.24", "修正橫向字幕停留舊內容、OCR 誤抓文字，新增多種辨識區域設定。"),
        Entry("1.0.23", "新增獨立懸浮翻譯開關、英／日／韓文來源選擇，使用 ML Kit OCR 與離線翻譯顯示繁體中文字幕。"),
        Entry("1.0.22", "碰一碰前重建 NFC 讀取模式、逾時重試與本機診斷；碰觸後確認 Nearby 連線、斷線自動重搜與解鎖後重連。"),
        Entry("1.0.21", "NFC 碰一碰選擇 2～32 個原始檔案，配合 Nearby 逐檔無損傳輸、SHA-256 核對與指定接收者。"),
        Entry("1.0.20", "移除不按分享即 NFC 傳送及網頁待命實驗，保留手動分享後碰一碰傳送。"),
        Entry("1.0.19", "實驗免按分享的 NFC 網頁碰觸（後續版本已移除）。"),
        Entry("1.0.18", "NFC 背景網頁接收實驗原型（後續版本已移除）。"),
        Entry("1.0.17", "修正三星相簿雙分享入口造成卡住；手勢抓取與 NFC 碰一碰分享分流。"),
        Entry("1.0.16", "三星相簿新增 NFC 碰一碰獨立分享入口，完成一次性驗證後傳送給指定信任裝置。"),
        Entry("1.0.15", "修正 S26 Ultra 前鏡頭掌心、手背與左右鏡像判斷，降低手勢誤判。"),
        Entry("1.0.14", "首頁獨立開關前鏡頭手勢與 Nearby 傳檔；純傳檔模式不需啟用相機或無障礙服務。"),
        Entry("1.0.13", "掌心朝向鏡頭才能啟動滑動，畫面右側提示起滑狀態。"),
        Entry("1.0.12", "恢復上滑判定，新增手掌位置與軌跡，減少幽靈辨識提示。"),
        Entry("1.0.11", "改善首次手勢啟動及下緣上滑、避開三星返回手勢，移除自動更新。"),
        Entry("1.0.10", "改善快速張掌滑動的追蹤及方向判定。"),
        Entry("1.0.9", "新增張掌左滑、靠右辨識提示、逐檔傳輸與遊戲自動暫停，握拳點擊改為 1.5 秒。"),
        Entry("1.0.8", "整合隔空操作與隔空抓取，保留單檔傳輸與多人接收。")
    )

    val latestVersion: String get() = entries.first().version

    fun displayText(): String = entries.joinToString(separator = "\n\n") {
        "v${it.version}｜${it.summary}"
    }

    fun containsVersion(version: String): Boolean = entries.any { it.version == version }
}
