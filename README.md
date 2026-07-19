# Mumiopad - Minimalist Wireless Touchpad & Virtual Keyboard

🌐 **[繁體中文](README_zh.md)**

Mumiopad is a lightweight open-source application that transforms your Android phone into a wireless touchpad and virtual keyboard for Windows PCs.

It provides low-latency cursor control and keyboard input over a local Wi-Fi connection without requiring manual IP configuration.

---

## 📥 Quick Start

Please visit 👉 **[the Mumiopad Latest Downloads page](https://github.com/howiedagg/Mumiopad/issues)** to download the latest version.

### 1. Computer Setup (Windows)

1. Download `MumiopadServer-vx.x.x.exe`
2. Double-click to run
3. The server stays in the Windows system tray and runs silently in the background

### 2. Android Setup

1. Install `Mumiopad-vx.x.x.apk`
2. Connect your phone and PC to the same Wi-Fi network
3. Open the app
4. Select your computer from the discovered devices list
5. Click "Yes" on the pairing dialog shown on Windows

---

## 🎮 Touchpad Gestures & Features

| Fingers | Gesture | Function |
|----------|----------|----------|
| **1 Finger** | Move | Cursor Movement |
| | Tap | Left Click |
| | Hold 200ms & Drag | Drag & Drop |
| **2 Fingers** | Tap | Right Click |
| | Vertical Slide | Scroll |
| | Horizontal Swipe | Browser Back / Forward |
| | Pinch Zoom | Windows Magnifier Zoom |
| **3 Fingers**| Tap Once | Task View (`Win + Tab`) |
| | Slide Down/Up | **Minimize All / Restore Windows** (`Win + D`) |
| | Slide Left / Right | **Toggle Virtual Keyboard** |
| **4 Fingers** | Tap | Mouse Middle Click |
| **Hardware Buttons** | Volume Keys | PC System Volume Control |

---

## ⚙️ Minimalist Device Manager

Tap the status capsule to open the device manager.

### Status Indicators

- 🟢 Green: Connected device
- 🔵 Blue: Paired and online
- ⚪ Gray: Paired but offline
- 🟡 Orange: Newly discovered device

### Gestures

- Tap: Connect / Switch device
- Long Press Green Row: Disconnect
- Swipe Left: Unpair device

### Manual Refresh 🔄

- Automatic discovery runs for 5 seconds
- A 🔄 icon appears after scanning stops
- Tap it to start a new scan

---

## 🛠️ Build from Source

### 1. PC Server (Python 3.10+)

    cd pc-server-python
    python -m venv .venv
    source .venv/bin/activate
    pip install -r requirements.txt
    python server.py

Build standalone executable:

    pyinstaller --onefile --noconsole --name MumiopadServer server.py

### 2. Android App (Kotlin / Compose)

Open `android-app` using Android Studio.

Build APK:

    cd android-app
    ./gradlew assembleDebug

APK output:

    android-app/app/build/outputs/apk/debug/app-debug.apk

---

## 📄 License & Disclaimer

### Non-Commercial Use Only

- Free for personal, educational and non-commercial use
- Commercial redistribution, resale, paid unlocking or bundling is prohibited

### Disclaimer

This software is provided "as is" without warranty of any kind. The author is not responsible for any damage, system conflicts or data loss resulting from its use.