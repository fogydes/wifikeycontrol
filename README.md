# WiFi Key Control

Control your Android phone using your PC's keyboard and mouse, either wirelessly (Wi-Fi/LAN) or wired (USB/OTG). The goal is to make the phone behave exactly as if a physical keyboard and mouse were connected via an OTG adapter — but done wirelessly or through a lightweight wired bridge.

## Overview

This project consists of **two connected applications**:

### 🖥️ PC Application (Server)
- **Platform:** Windows and Linux
- **Purpose:** Capture keyboard & mouse input and transmit it to the Android device
- **Language:** Python with PyQt5 GUI

### 📱 Android Application (Client)
- **Platform:** Android (SDK 24+)
- **Purpose:** Receive input data from PC and simulate touch, scroll, and typing events
- **Language:** Kotlin

## Features

- **Wireless Connection:** Connect PC and Android over Wi-Fi/LAN
- **USB/OTG Connection:** Wired connection support (Phase 2)
- **Screen Edge Switching:** Mouse movements at screen edges automatically transfer control to Android
- **Low Latency:** Optimized for smooth input response (< 50ms)
- **Auto-Discovery:** Automatic device discovery over local network
- **Security:** Optional PIN pairing for secure connections
- **Cross-Platform:** Works on Windows and Linux

## Quick Start

### Prerequisites

**PC Requirements:**
- Python 3.8 or higher
- Windows 10/11 or Linux (Ubuntu 18.04+)
- Wi-Fi or Ethernet connection

**Android Requirements:**
- Android 7.0 (SDK 24) or higher
- Wi-Fi connection
- Accessibility permissions
- Input method permissions

### Installation

#### 1. PC Application Setup

```bash
# Clone the repository
git clone <repository-url>
cd wifikeycontrol/pc-app

# Install Python dependencies
pip install -r requirements.txt

# Run the PC application
python main.py
```

#### 2. Android Application Setup

```bash
# Build the Android app using Android Studio
# or build via command line:
cd android-app
./gradlew assembleDebug

# Install the APK on your Android device
adb install app/build/outputs/apk/debug/app-debug.apk
```

### First-Time Setup

#### Android Permissions

1. **Accessibility Permission:**
   - Open Settings → Accessibility → WiFi Key Control
   - Enable the accessibility service
   - This allows the app to simulate touch input

2. **Input Method Permission:**
   - Open Settings → System → Languages & input → On-screen keyboard → WiFi Key Control
   - Enable the keyboard
   - This allows the app to simulate typing

3. **Overlay Permission:**
   - Grant overlay permission when prompted
   - This allows the app to display control status

4. **Battery Optimization:**
   - Disable battery optimization for the app
   - This ensures stable background operation

#### Connection Setup

1. **Start PC Server:**
   - Launch the PC application
   - Click "Start Server"
   - Note the server IP address displayed

2. **Start Android Service:**
   - Launch the Android app
   - Click "Start Service"
   - Enter the PC server IP address
   - Click "Connect"

3. **Auto-Discovery (Alternative):**
   - Click "Discover Devices" on the PC
   - The app should automatically find your Android device

### Usage

1. **Start Input Capture:**
   - On the PC app, click "Start Input Capture"
   - Move your mouse to the edge of your PC screen
   - Control will automatically transfer to your Android device

2. **Control Your Phone:**
   - Use your PC mouse to click and tap on your phone
   - Use your PC keyboard to type on your phone
   - Scroll with your mouse wheel

3. **Return Control to PC:**
   - Move mouse back to PC screen edge, or
   - Press the hotkey (Ctrl + Shift + Space) to toggle control

## Project Structure

```
wifikeycontrol/
├── pc-app/                    # Python PC application
│   ├── main.py               # PyQt5 application entry point
│   ├── input_capture.py      # Global keyboard/mouse hooks
│   ├── connection_manager.py # Wi-Fi discovery and connection logic
│   ├── protocol.py           # Communication packet handling
│   ├── gui/                  # UI components (future)
│   └── requirements.txt      # Python dependencies
├── android-app/              # Android Studio project
│   ├── app/src/main/java/    # Kotlin source code
│   │   └── com/wifikeycontrol/
│   │       ├── MainActivity.kt
│   │       ├── services/
│   │       │   ├── ConnectionService.kt
│   │       │   ├── InputSimulatorService.kt
│   │       │   └── KeyboardService.kt
│   │       └── protocol/
│   │           └── ProtocolHandler.kt
│   ├── app/src/main/res/     # Android resources
│   └── build.gradle         # Android build configuration
└── README.md                # This file
```

## Configuration

### PC Application Settings

- **Server Port:** Default 12346 (configurable)
- **Discovery Port:** Default 12345 (for auto-discovery)
- **Edge Detection:** Configurable screen edge sensitivity

### Android Application Settings

- **Auto-connect:** Automatically connect to known PC
- **Auto-start:** Start service on device boot
- **Keep Alive:** Maintain connection even when app is in background

## Troubleshooting

### Common Issues

1. **Can't Connect:**
   - Ensure both devices are on the same Wi-Fi network
   - Check firewall settings on PC
   - Verify IP address and port settings

2. **Input Not Working:**
   - Make sure accessibility service is enabled
   - Check that keyboard input method is selected
   - Restart both applications

3. **Connection Drops:**
   - Check Wi-Fi signal strength
   - Disable battery optimization on Android
   - Ensure PC application is running

### Debug Mode

Enable debug logging:
- **PC:** Check the log panel in the PC application
- **Android:** Use `adb logcat -s WiFiKeyControl`

## Development

### Building from Source

**PC Application:**
```bash
cd pc-app
pip install -r requirements.txt
python main.py
```

**Android Application:**
```bash
cd android-app
./gradlew assembleDebug
```

### Testing

The project includes automated tests for:
- Protocol handling
- Network connectivity
- Input simulation

Run tests:
```bash
# PC tests
cd pc-app
python -m pytest tests/

# Android tests
cd android-app
./gradlew test
```

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests for new functionality
5. Submit a pull request

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Changelog

### Version 1.0.0 (Current)
- ✅ Basic Wi-Fi connection between PC and Android
- ✅ Mouse movement and click simulation
- ✅ Keyboard input simulation
- ✅ Screen edge control switching
- ✅ Auto-discovery functionality
- ✅ Basic security (PIN pairing framework)

### Planned Features (Future Versions)
- 🔄 USB/OTG connection support
- 🔄 Multi-device support (1 PC → multiple phones)
- 🔄 Clipboard sharing
- 🔄 Advanced gestures and multi-touch
- 🔄 Enhanced security with QR code pairing
- 🔄 Performance monitoring and optimization

## Support

For support, please:
1. Check the troubleshooting section above
2. Search existing issues on GitHub
3. Create a new issue with detailed information about your problem

---

**Note:** This is currently in development. Some features may be incomplete or experimental.
