# Mumiopad - Wireless Touchpad & Virtual Keyboard over Wi-Fi

🌐 **[繁體中文](README_zh.md)**

Mumiopad is a lightweight, open-source tool that turns your Android device into a Wi-Fi remote controller for your PC. It is designed for casual remote control scenarios—such as smart TV navigation, media center control (HTPC), wireless presentations, or simply relaxing on your sofa.

By establishing a direct local connection, Mumiopad delivers low-latency input emulation and smooth cursor tracking without requiring complex manual IP configurations.

---

## 📥 Quick Start (How to Download & Connect)

Please head to the 👉 **[Mumiopad Latest Releases Page](https://github.com/howiedagg/Mumiopad/releases)** to download the pre-built files:

### 1. Computer Setup (Windows)
*   On the releases page, click and download `MumiopadServer-vx.x.x.exe`.
*   **Double-click the downloaded `.exe` file to run it.**
*   No installation or setup is required. The server launches silently and resides in your Windows system tray (bottom-right corner) without cluttering your desktop.

### 2. Mobile Setup (Android)
*   On the releases page, click and download `Mumiopad-vx.x.x.apk` to your phone and install it.
*   Make sure both your phone and PC are connected to the **same Wi-Fi local network**.
*   Open the mobile app; it will automatically discover your computer. Tap your computer's name, click "Yes" on the authorization popup on your PC screen, and you are ready to go!

---

## 🎮 Touchpad Gestures & Features

Mumiopad supports standard Windows Precision Touchpad gestures complemented by customized Android haptic feedback:

*   **One-Finger Gestures**:
    *   `Cursor Movement`: Slide your finger on the touchpad area.
    *   `Left Click`: Tap once.
    *   `Tap and Drag`: Hold still for 200ms to trigger a simulated tactile click vibration, then drag windows or select text easily. Lifting your finger triggers a release haptic pulse.
*   **Two-Finger Gestures**:
    *   `Right Click`: Tap with two fingers.
    *   `Page Scroll`: Slide two fingers vertically (scroll direction and speed can be customized in settings).
    *   `Browser Back/Forward`: Slide two fingers horizontally anywhere on the trackpad to navigate pages in web browsers.
*   **Three-Finger Gestures**:
    *   `Task View (Win+Tab)`: Tap with three fingers.
    *   `Minimize All Windows`: Slide three fingers down.
    *   `Restore Windows`: Slide three fingers up.
*   **Four-Finger Gestures**:
    *   `Toggle Keyboard`: Tap with four fingers to quickly open or close the local on-screen keyboard on your phone to type text.
*   **Physical Volume Buttons**:
    *   While connected, use your phone's hardware volume keys to directly adjust your PC's master volume. (Automatically reverts to controlling phone volume when disconnected).

---

## ⚙️ Settings Customization
You can tap the Gear icon in the top-right corner of the mobile app to customize:
*   **Cursor Speed** (0.3x ~ 3.0x sensitivity adjustment).
*   **Scrolling Speed**.
*   **Reverse Scroll (Natural Scrolling)** toggle.

---

## 🛠️ Build from Source

If you are a developer or want to modify and compile this project yourself, follow the steps below:

### 1. PC Server (Python)
Ensure Python 3.10+ is installed on your machine:
```bash
cd pc-server-python
python -m venv .venv
source .venv/bin/activate  # On Windows run: .venv\Scripts\activate
pip install -r requirements.txt
python server.py
```
To compile the standalone `.exe` without console yourself:
```bash
pyinstaller --onefile --noconsole --name MumiopadServer server.py
```

### 2. Android App (Kotlin/Compose)
1. Open the `android-app` folder with **Android Studio**.
2. Ensure JDK 17 is configured.
3. Build the APK using the terminal:
```bash
cd android-app
./gradlew assembleDebug
```
The compiled APK will be located at `android-app/app/build/outputs/apk/debug/app-debug.apk`.

---

## 📄 License & Disclaimer

*   **Non-Commercial Use Only**:
    *   The source code of this project and all compiled binaries (APK and EXE files) are free to use for **personal, educational, and non-commercial purposes only**.
    *   **Any commercial use, resale, paid unlocking, or commercial bundling of this software is strictly prohibited.**
*   **Safety Notice**: Since this software simulates mouse and keyboard inputs, we highly recommend using it only within trusted local Wi-Fi networks.
*   **Disclaimer**: This software is provided "as is" without warranty of any kind. The author is not responsible for any damage, system conflicts, or data loss caused by using this application.

## 💬 Feedback

If you encounter any bugs or have feature suggestions, please feel free to open an issue at [GitHub Issues](https://github.com/howiedagg/Mumiopad/issues). Your feedback is highly appreciated!