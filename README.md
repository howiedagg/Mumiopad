# Mumiopad - Simple Wireless Touchpad

Control your PC, even while lying down.

🌐 **[繁體中文](README_zh.md)**

**Mumiopad** is a simple dual-mode wireless touchpad and virtual keyboard. Supporting **local Wi-Fi** and **Bluetooth HID (driverless)**, it turns your Android phone into a mouse and keyboard—giving you effortless remote control from your sofa, bed, or during presentations.

👉 **[Download Latest Version (APK / EXE)](https://github.com/howiedagg/Mumiopad/releases)**

---

## 📥 Quick Start

### 📶 Wi-Fi Mode
1. **PC**: Run `MumiopadServer.exe`.
2. **Android**: Connect to the same Wi-Fi and open the app.
3. **Connect**: Tap your PC, and click "Yes" on the Windows dialog.

### ᛒ Bluetooth Mode (Driverless)
1. **Phone**: Open the app, switch to Bluetooth, and tap "Make Discoverable".
2. **PC**: Search and pair with **"your phone's Bluetooth name"** in settings.
3. **Connect**: Tap your PC in the app list to start control.

---

## 🎮 Touchpad Gestures

| Fingers | Action | Simulated Function |
|----------|----------|-------------------|
| **1 Finger** | Move | Cursor Movement |
| | Tap | Left Click |
| | Hold 200ms & Drag | Drag & Drop (Tactile Haptic Lock) |
| **2 Fingers** | Tap | Right Click |
| | Vertical Slide | Scroll (Friction Haptic Feedback) |
| | Horizontal Swipe | Browser Back / Forward |
| | Pinch Zoom | Windows Magnifier (Precision Haptic Tick) |
| **3 Fingers**| Tap | Task View (`Win + Tab`) |
| | Slide Down / Up | **Minimize All / Restore Windows** (`Win + D`) |
| | Slide Left / Right | **Toggle Virtual Keyboard** |
| **4 Fingers** | Tap | Mouse Middle Click |
| **Hardware Buttons** | Volume Keys | PC System Volume Control |

---

## 💡 Smart Connection

* **Location Awareness**: Automatically binds default PCs to specific Wi-Fi networks (BSSIDs).
* **One-Tap Dual-Mode**: Switch freely between Wi-Fi and Bluetooth.
* **Multi-Device**: Supports pairing multiple PCs; switch control seamlessly with a single tap.

---

### Unified Status Indicators

- 🟢 Green: Connected
- 🟡 Orange: Connecting
- 🔵 Blue: Paired and ready
- ⚪ Gray: Unpaired or offline

---

## 🛠️ Build from Source

### 1. PC Server (Python 3.10+)

    cd pc-server-python
    python -m venv .venv
    source .venv/bin/activate
    pip install -r requirements.txt
    python main.py

Build Standalone EXE:

    pyinstaller --onefile --noconsole --name MumiopadServer main.py

### 2. Android App (Kotlin / Compose)

Open `android-app` using Android Studio.

Build APK:

    cd android-app
    ./gradlew assembleDebug

---

## 📄 License & Disclaimer

- **Non-Commercial Use Only**: Free for personal, family, and educational use. Commercial redistribution, resale, paid unlocking, or bundling is prohibited.
- **Disclaimer**: This software is provided "as is" without warranty of any kind. The author is not liable for any equipment failure, system conflicts, or data loss resulting from its use.