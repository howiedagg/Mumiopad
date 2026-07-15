# Mumiopad - Android 無線觸控板與虛擬鍵盤

🌐 **[English Version](README.md)**

---

Mumiopad 是一個可以將 Android 手機變成電腦無線觸控板與鍵盤的工具。適合您在沙發上放鬆、觀賞影音、簡報，或是需要輕量遠端控制電腦的日常場景。

透過高效率的本地端 **WebSockets** 與 **mDNS 自動搜尋**，Mumiopad 提供流暢的游標追蹤與低延遲的輸入模擬，且完全不需要繁瑣的網路 IP 設定。

### 💡 主要功能與手勢
我們設計了符合日常直覺的操作方式，並加入觸覺震動來輔助無視覺盲操：

*   **單指操作**：移動游標、單擊（滑鼠左鍵）。
*   **單指長按並拖曳**：在觸控板靜止按住 200ms，手機會發出一次類似滑鼠壓下的 Click 震動，即可開始拖曳視窗或選取文字。放開手指時會伴隨釋放震動。
*   **雙指上下滑動**：垂直滾動網頁或文件（支援在設定中開啟反向/自然滾動）。
*   **雙指左右滑動（全區）**：在觸控板任何位置雙指左右刷動，即可切換瀏覽器的「上一頁」與「下一頁」，觸發時會有明確的實體鍵盤震動確認。
*   **雙指輕點**：滑鼠右鍵。
*   **三指輕點**：開啟多工作業工作檢視（Win + Tab）。
*   **三指往下滑動**：顯示桌面（最小化所有視窗）。
*   **三指往上滑動**：還原視窗。
*   **四指輕點**：在觸控板任何地方點擊，即可開關手機端的虛擬鍵盤，輸入中英文，觸發時伴隨震動提示。
*   **實體音量鍵**：連線時，按手機側邊的實體音量加減鍵，可直接調整電腦系統音量；未連線時自動恢復為調整手機音量。

---

### 📥 給使用者：如何開始使用？

請前往本專案的 **[Releases 頁面](https://github.com/您的GitHub帳號/您的專案名稱/releases)** 下載最新版本的檔案。

#### 第一步：準備電腦端（PC Server）
1.  在發布頁面下載 `pc-server-vx.x.x.zip` 並解壓縮。
2.  點擊執行資料夾中的 `start.bat`。它會在背景執行，並在 Windows 右下角系統列出現圖示。
3.  *註：電腦需預先安裝 Python 3。若首次執行無法啟動，請參閱下方「手動建置伺服器環境」安裝相依套件。*

#### 第二步：準備手機端（Android App）
1.  在發布頁面下載 `Mumiopad-vx.x.x.apk` 並安裝至您的 Android 手機。
2.  確保手機與電腦連線至**同一個 Wi-Fi 網路**。
3.  App 會自動搜尋網路內的電腦。點擊您的電腦名稱，此時電腦螢幕會跳出配對詢問視窗，點擊「是」即可完成連線。

---

### 🛠 給開發人員：如何編譯與調整？

#### 技術棧
*   **Android 端**：Kotlin, Jetpack Compose, OkHttp (WebSocket), mDNS (NsdManager).
*   **PC 伺服器端**：Python, websockets, pynput (輸入模擬), pystray (系統列選單).

#### 專案架構
為了便於後續擴充與維護，專案採用低耦合設計：
*   `GestureEngine.kt` 為純 Kotlin 寫成的手勢狀態機，不依賴 Android UI 類別，透過 callback 將手勢指令與本地回饋傳遞出去，極易進行單元測試與手感微調。
*   `ScrollCatchupSmoother.kt` 將跨越死區時的位移分批補償，消除雙指滾動初期的遲滯感。
*   `WifiPerformanceManager.kt` 在連線期間持有 WiFi 低延遲鎖（Low Latency Lock），防止手機晶片因進入省電模式而產生週期性微卡頓。

#### 手動建置伺服器環境指令：
    cd pc-server-python
    python -m venv .venv
    source .venv/bin/activate  # Windows 請執行 .venv\Scripts\activate
    pip install -r requirements.txt
    python server.py

---

*本專案由 Gemini 3.5 Flash 模型開發。*