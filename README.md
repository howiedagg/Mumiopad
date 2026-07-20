# Mumiopad - Minimalist Dual-Mode Wireless Touchpad

Control your PC with ease, right from the comfort of your couch.

**Mumiopad** is a minimalist dual-mode wireless touchpad and virtual keyboard. Supporting both **Wi-Fi local network** and **driver-free Bluetooth (Bluetooth HID)**, it turns your Android phone into a mouse and keyboard, making remote control effortless whether you are relaxing on the couch, watching a movie, or giving a presentation.

👉 **[Download Latest Release (APK / EXE)](https://github.com/howiedagg/Mumiopad/releases)**

---

## 📥 Quick Connection

### 📶 Wi-Fi Mode
1. **PC**: Launch `MumiopadServer.exe`.
2. **Phone**: Connect to the same Wi-Fi and open the app.
3. **Connect**: Tap your computer's name and click "Yes" on the pop-up window on your PC.

### ᛒ Bluetooth Mode (Driver-Free)
1. **Phone**: Open the app, switch to Bluetooth, and tap "Enable Bluetooth Pairing".
2. **PC**: Search for and pair **"Your Phone Name"** in Windows Bluetooth settings.
3. **Connect**: Once successfully paired, tap your computer in the phone's device list to start controlling.

---

## 🎮 Touchpad Gestures

| Finger Count | Action | Simulated Function |
|--------------|--------|--------------------|
| **Single Finger** | Swipe | Move cursor |
| | Tap | Left-click |
| | Hold 200ms & Drag | Select / Drag (Haptic lock vibration) |
| **Two Fingers** | Tap | Right-click |
| | Swipe Up/Down | Page scrolling (Fine scroll vibration) |
| | Swipe Left/Right | Browser Back / Forward |
| | Pinch | Windows Screen Magnifier (High-precision tick vibration) |
| **Three Fingers** | Tap | Task View (`Win + Tab`) |
| | Swipe Up/Down | **Show / Restore Desktop** (`Win + D`) |
| | Swipe Left/Right | **Toggle / Open-Close Phone Virtual Keyboard** |
| **Four Fingers** | Tap | Middle-click |
| **Physical Keys** | Volume Keys | Control PC system volume |

---

## 💡 Smart Connection

* **Location Awareness**: Automatically binds to default computers under different Wi-Fi networks.
* **One-Tap Dual-Mode**: Freely switch between Wi-Fi and Bluetooth.
* **Multi-Device Quick Switch**: Supports pairing with multiple computers, allowing seamless switching directly from the menu.

---

### Indicator Light Standards

- 🟢 Green: Connected
- 🟡 Orange: Connecting
- 🔵 Blue: Paired and available
- ⚪ Gray: Unpaired or offline

---

## 🛠️ Development & Build

### 1. PC Server Side (Python 3.10+)

    cd pc-server-python
    python -m venv .venv
    source .venv/bin/activate
    pip install -r requirements.txt
    python main.py

Package to EXE:

    pyinstaller --onefile --noconsole --name MumiopadServer main.py

### 2. Android App (Kotlin / Compose)

Open `android-app` using Android Studio.

Build APK:

    cd android-app
    ./gradlew assembleDebug

---

## 📄 License & Disclaimer

- **Non-Commercial Restriction**: Free for personal, family, and academic use only. Any commercial monetization or packaged resale is strictly prohibited.
- **Disclaimer**: This software is provided "as is", without warranty of any kind. The author assumes no responsibility for any equipment failure, system conflict, or data loss resulting from its use.