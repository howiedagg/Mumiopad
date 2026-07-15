# VR Touchpad (Mumiopad) 📱💻

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Platform: Android](https://img.shields.io/badge/Platform-Android_8.0+-green.svg)](#)
[![Python: 3.9+](https://img.shields.io/badge/Python-3.9+-blue.svg)](#)

VR Touchpad 是一款專為高響應性設計的無線觸控板解決方案。透過 Wi-Fi 網路，將您的 Android 手機瞬間轉化為電腦的高精度滑鼠、多指觸控板與虛擬鍵盤。

專案採用 **極簡零配置（Zero-Config）** 設計，無需手動輸入 IP 或繁瑣的 6 位數配對碼，即可在數秒內完成流暢、安全的自動連線。

---

## ✨ 核心特色

- **零配置自動探索**：利用 mDNS (Multicast DNS) 技術，手機開啟即可自動搜尋同網域下的電腦。
- **一鍵安全授權**：捨棄傳統密碼輸入，採用「手機端發起 -> 電腦端彈窗確認」的極簡安全配對機制。
- **極致超低延遲**：
  - **1ms 系統精度**：Windows 端啟用高精度計時器 `timeBeginPeriod(1)`。
  - **TCP No-Delay**：強制關閉 Nagle 演算法，消除高頻率小封包的週期性微卡頓。
  - **零垃圾回收卡頓**：Android 端捨棄 JSON 動態序列化，改用原生字串模板，徹底避免高頻觸控觸發 GC 幀延遲。
- **平滑滾動阻尼與慣性**：獨創雙指滾動「死區補償平滑器（Scroll Catchup Smoother）」，保留 32dp 精確防誤觸門檻的同時，完全補回啟動位移。
- **現代輸入法完全相容**：虛擬鍵盤支援**語音輸入（Speech-to-Text）**、**滑行輸入（Swipe Typing）** 與自動校正。

---

## 🛠️ 運作架構

```text
+-----------------------+              Wi-Fi (WebSockets)            +-----------------------+
|  Android Phone (App)  | <========================================> |     PC (Server)       |
|  - Jetpack Compose UI |        - TCP_NODELAY (No Latency)          |  - Python asyncio     |
|  - Gesture Engine     |        - Zero-Config mDNS Broadcast        |  - Win32 API Control  |
+-----------------------+                                            +-----------------------+