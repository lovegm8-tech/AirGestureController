# 安全與隱私規範

- **相機影像只供裝置本地手勢推論**；目前程式不包含照片、影片持久化儲存或網路上傳功能。
- App Manifest 不申請 `android.permission.INTERNET`，CI 會檢查最終 APK 的權限。
- AccessibilityService 只發送使用者要求的觸控手勢，`canRetrieveWindowContent=false`；授權應只在信任本 App 時啟用。
- 不允許廣告 SDK、分析 SDK 或跨 App 內容擷取功能進入原始碼；程式修改應由儲存庫擁有者審核。
- 簽章金鑰不得放入 Git、Issues、PR、Actions log 或聊天紀錄；機密應由擁有者於 GitHub Secrets 設定，並保存**離線備份**。
- Github Secrets 加密保存但並非萬無一失：**任何可修改並執行受信任工作流程的人**都可能對機密構成風險，因此限制儲存庫寫入權限，且僅由 main 分支手動執行。
- `debug` APK 每次 CI 可能重新簽署，不能用作長期更新；需固定私有的 release keystore。
- 公開不等於 App 自動上架、不等於開放他人直接修改正式程式，也不代表安全審核已完成。
- 尚未執行 Android 完整建置與實機權限驗證；第三方模型與依賴仍待進一步供應鏈審查。

- 懸浮指示燈使用無障礙服務的 TYPE_ACCESSIBILITY_OVERLAY，不新增 SYSTEM_ALERT_WINDOW 權限，也不攔截觸控。
- 尚未驗證 Samsung S25 等不同 Android 裝置的前鏡頭跨 App 運作與懸浮顯示。
