# WiFi Key Control

Control your Android phone using your PC's keyboard and mouse over a local network. Move your cursor off the edge of your PC screen and it seamlessly appears on your phone!

## ✅ Current Status

**Working Features:**

- ✅ **Mouse Control** - Move cursor from PC to Android seamlessly
- ✅ **Click Simulation** - Left-click works with accurate positioning
- ✅ **Edge Detection** - Cursor transitions when reaching PC screen edge
- ✅ **Return to PC** - Move cursor to Android's left edge to return
- ✅ **USB Mode** - Connect via ADB reverse for wired connection
- ✅ **Auto-Discovery** - UDP broadcast finds devices on network

**Known Limitations:**

- ⚠️ Keyboard input requires enabling the custom keyboard in Android settings

## 🏗️ Architecture

### PC Application (Server)

- **Framework:** [Wails v2](https://wails.io) (Go + HTML/JS)
- **Backend:** Go (high-speed networking, native input hooks)
- **Frontend:** Modern dark-themed dashboard

### Android Application (Client)

- **Platform:** Android SDK 24+
- **Language:** Kotlin
- **Services:** Accessibility Service (gestures) + Input Method (typing)

### Protocol

- **Format:** [FlatBuffers](https://google.github.io/flatbuffers/) for ultra-low latency
- **Schema:** `shared/messages.fbs`

## Quick Start

### 1. Build PC Application

Requires [Go](https://go.dev/dl/) and [Wails](https://wails.io/docs/gettingstarted/installation).

```powershell
cd pc-app
wails dev     # Development mode
wails build   # Production build
```

### 2. Build Android Application

Requires Android SDK 34 and JDK 21.

```powershell
cd android-app
.\gradlew.bat assembleDebug
adb install app\build\outputs\apk\debug\app-debug.apk
```

## Setup

### Required Permissions

1. **Accessibility Service**: Settings → Accessibility → WiFi Key Control → Enable
2. **Overlay Permission**: Automatically requested on first use
3. **Keyboard** (optional): Settings → System → Languages & Input → Enable WiFi Key Control

### Connection Methods

**WiFi Mode:**

1. Connect PC and Android to same network
2. Start server on PC, app shows local IP
3. Enter IP on Android app and connect

**USB Mode:**

1. Connect Android via USB with ADB debugging enabled
2. Click "Enable USB Mode" on PC app (runs `adb reverse`)
3. Connect to `127.0.0.1:12346` on Android

## Usage

1. Start the PC application and press "Start Server"
2. Connect your Android device
3. Press "Start Capture" to begin input capture
4. **Move cursor to the right edge** of PC screen → cursor appears on Android
5. **Move cursor to the left edge** of Android screen → returns to PC

## Project Structure

```
wifikeycontrol/
├── shared/                 # FlatBuffers schema
├── pc-app/                 # Go + Wails PC application
│   ├── app.go              # Main application controller
│   ├── server/             # TCP/UDP server
│   ├── input/              # Windows input capture
│   └── frontend/           # Web UI
└── android-app/            # Kotlin Android client
    └── app/src/main/java/com/wifikeycontrol/
        ├── services/       # Connection & input services
        └── protocol/       # FlatBuffers handler
```

## License

GPL-3.0 License. See `LICENSE` for details.
