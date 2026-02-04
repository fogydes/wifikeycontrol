# WiFi Key Control

Control your Android phone using your PC's keyboard and mouse over a local network. Move your cursor off the edge of your PC screen and it seamlessly appears on your phone.

## Overview

WiFi Key Control enables seamless input sharing between a Windows PC and an Android device. The PC acts as a server that captures keyboard and mouse input, while the Android app receives and simulates these inputs on the device.

### Key Capabilities

- **Cursor Transition**: Move your mouse to the edge of your PC screen to seamlessly control your Android device
- **Mouse Simulation**: Full mouse control including movement, clicks, and scrolling
- **Keyboard Input**: Type on your PC keyboard to input text on Android
- **Low Latency Protocol**: FlatBuffers-based binary protocol for minimal input delay
- **Network Discovery**: Automatic device discovery via UDP broadcast

## Architecture

### PC Application (Server)

Built with [Wails v2](https://wails.io), combining a Go backend with an HTML/JS frontend.

- **Backend**: Go handles TCP/UDP networking and native Windows input hooks
- **Frontend**: Modern web-based dashboard for connection management
- **Protocol**: Generates and sends FlatBuffers messages for input events

### Android Application (Client)

Native Kotlin application utilizing Android system services.

- **Accessibility Service**: Enables gesture and touch simulation
- **Input Method Service**: Allows keyboard input injection
- **FlatBuffers Handler**: Parses incoming input events with minimal overhead

### Communication Protocol

The protocol uses [FlatBuffers](https://google.github.io/flatbuffers/) for serialization, providing:

- Zero-copy deserialization
- Type-safe message definitions
- Sub-millisecond parsing times

Schema definitions are located in `shared/messages.fbs`.

## Getting Started

### Prerequisites

**PC Application:**

- Go 1.21 or later
- Wails CLI v2 ([Installation Guide](https://wails.io/docs/gettingstarted/installation))
- Windows 10/11

**Android Application:**

- Android SDK 34
- JDK 21
- Android device running SDK 24 or later

### Building the PC Application

```powershell
cd pc-app

# Development mode with hot reload
wails dev

# Production build
wails build
```

### Building the Android Application

```powershell
cd android-app
.\gradlew.bat assembleDebug
```

The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

### Installation

```powershell
adb install app\build\outputs\apk\debug\app-debug.apk
```

## Configuration

### Required Android Permissions

1. **Accessibility Service**: Navigate to Settings > Accessibility > WiFi Key Control and enable the service
2. **Overlay Permission**: Grant permission when prompted on first connection
3. **Input Method** (for keyboard): Settings > System > Languages & Input > On-screen keyboard > Enable WiFi Key Control

### Connection Methods

**WiFi Connection:**

1. Ensure both devices are on the same network
2. Start the server on PC
3. Enter the displayed IP address in the Android app

**USB Connection:**

1. Enable USB debugging on Android
2. Connect via USB
3. Click "Enable USB Mode" on PC (executes `adb reverse tcp:12346 tcp:12346`)
4. Connect to `127.0.0.1:12346` on Android

## Project Structure

```
wifikeycontrol/
├── shared/                     # FlatBuffers schema definitions
│   └── messages.fbs
├── pc-app/                     # PC application (Go + Wails)
│   ├── main.go                 # Application entry point
│   ├── app.go                  # Wails application controller
│   ├── server/                 # TCP/UDP server implementation
│   ├── input/                  # Windows input capture hooks
│   ├── protocol/               # Generated FlatBuffers code (Go)
│   └── frontend/               # Web UI source
└── android-app/                # Android application (Kotlin)
    └── app/src/main/
        ├── java/com/wifikeycontrol/
        │   ├── MainActivity.kt
        │   ├── services/       # Connection and input services
        │   └── protocol/       # FlatBuffers handler
        └── res/                # Android resources
```

## License

This project is licensed under the GNU General Public License v3.0. See the [LICENSE](LICENSE) file for details.
