# Mumiopad - Android Virtual Touchpad & Wireless Keyboard over Wi-Fi

🌐 **[繁體中文](README_zh.md)**

---

Mumiopad is an open-source, lightweight **wireless touchpad, virtual mouse, and remote keyboard app** that turns any Android device into a Wi-Fi remote controller for Windows PCs. It is designed for casual remote control scenarios—such as smart TV navigation, media center control (HTPC), wireless office presentations, or living room PC control.

Using high-performance local **WebSockets** and **mDNS auto-discovery**, Mumiopad delivers low-latency input emulation and smooth mouse tracking without requiring complex network configurations.

### 💡 Supported Touchpad Gestures
Mumiopad supports standard Windows Precision Touchpad gestures complemented by customized Android haptic feedback:

*   **One-finger movement**: Smooth mouse cursor tracking.
*   **One-finger Tap**: Left click.
*   **One-finger Long Press (Hold to Drag)**: Hold still for 200ms to trigger a simulated tactile click vibration, letting you drag windows or select text easily. Lifting your finger triggers a release haptic pulse.
*   **Two-finger Scroll**: Vertical page scrolling (supports native reverse/natural scrolling).
*   **Two-finger Swipe (Full Area)**: Slide horizontally anywhere on the trackpad to go "Back" (swipe right) and "Forward" (swipe left) in web browsers (Chrome, Edge, Firefox). Features a solid haptic confirmation.
*   **Two-finger Tap**: Right click.
*   **Three-finger Tap**: Open Windows Task View / Multitasking layout (Win + Tab).
*   **Three-finger Swipe Down**: Show Desktop (minimizes all active windows).
*   **Three-finger Swipe Up**: Restore minimized windows.
*   **Four-finger Tap**: Tap anywhere on the touchpad to toggle the local Android on-screen keyboard for text input, accompanied by a quick haptic feedback.
*   **Physical Volume Buttons**: Directly adjust your PC's master volume using your Android device's hardware volume keys while connected.

---

### 📥 How to Turn Android Phone into Wireless Mouse & Keyboard?

Please head to the **[Releases Page](https://github.com/您的GitHub帳號/您的專案名稱/releases)** to download the latest pre-built assets.

#### Step 1: Run the PC WebSocket Server
1. Download `pc-server-vx.x.x.zip` from the release assets and extract it.
2. Double-click `start.bat`. The server runs in the background and places an icon in the system tray.
3. *Note: Ensure Python 3 is installed on your computer.*

#### Step 2: Set up the Android Wi-Fi Touchpad App
1. Download and install `Mumiopad-vx.x.x.apk` on your Android device.
2. Make sure your phone and PC are connected to the **same Wi-Fi local network**.
3. The app will automatically discover nearby PCs. Tap your computer name and click "Yes" on the PC authorization popup.

---

### 🛠 Technical Architecture for Developers

#### Technology Stack
*   **Android App**: Kotlin, Jetpack Compose UI, OkHttp (WebSocket client), mDNS (Android NsdManager).
*   **PC Server**: Python, websockets, pynput (OS-level input emulation), pystray (system tray interface).

#### Decoupled Codebase Structure
The project is built with modularity and extensibility in mind:
*   `GestureEngine.kt` is a pure Kotlin gesture state machine independent of Android UI components. It dispatches gesture instructions and haptic triggers via clean callbacks, making it unit-testable.
*   `ScrollCatchupSmoother.kt` distributes the initial dead-zone slop delta over smooth ticks to eliminate scrolling latency.
*   `WifiPerformanceManager.kt` acquires a full low-latency Wi-Fi lock (`WIFI_MODE_FULL_LOW_LATENCY`) during active connections to prevent micro-stuttering caused by mobile chipsets entering power-saving modes.

#### Manual Server Environment Setup:
    cd pc-server-python
    python -m venv .venv
    source .venv/bin/activate  # On Windows run: .venv\Scripts\activate
    pip install -r requirements.txt
    python server.py

---

*This project was developed by the Gemini 3.5 Flash model.*