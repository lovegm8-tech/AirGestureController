# 隔空抓取純程式碼測試報告

**原始碼分支**：experiment/global-grab-code

測試工作流程為 \`:app:testDebugUnitTest --no-daemon --stacktrace\`。它會編譯測試必需的 Android / Kotlin 類別，但不會呼叫 \`assembleDebug\`、\`assembleRelease\`、APK 簽章或發佈。

**共 33 個 JUnit 測試，分成 6 個測試檔案：**

| 測試組 | 案例數 | 檢查內容 |
|---|---:|---|
| GestureSequenceTest | 5 | 張掌握拳、握拳張掌、誤觸、單一手勢及漏幀 |
| GlobalGrabCoreTest | 4 | ECO/ACTIVE、熄屏、瀏覽器網址、拒絕不安全 URL |
| ShareAutomationPolicyTest | 11 | Office、Google 文件、影片社群、分享候選唯一性、可信分享選單 |
| SharedTextPolicyTest | 3 | 分享文案提取唯一網址、拒絕多網址、拒絕根據標題猜測 |
| TransferPolicyTest | 4 | 檔名安全、大小/雜湊驗證、拒絕非 HTTPS/HTTP 內容 |
| TransferAnimationPolicyTest | 6 | 抓取成功、接收完成、接收中進度不可誤報成功、發送與接收動畫區分 |
| **合計** | **33** | 純邏輯與 Android 程式碼編譯測試 |

測試不涵蓋實體攝影機、前景服務在三星系統中的存活、Nearby 實際裝置連線與身分釘選、Android 分享選單實際點擊、每款 App 的檔案匯出，以及電池實測。這些仍是未驗證事項。

GitHub Actions 限定在測試分支，原本的隔空操作專案和穩定版簽章均未更動。
