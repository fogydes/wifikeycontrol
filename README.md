# WiFi Key Control

Control your Android phone using your PC's keyboard and mouse with ultra-low latency. This version is a ground-up refactor focusing on performance, maintainability, and a modern tech stack.

## 🚀 The Refactor (Version 2.0)

This project has been upgraded from a Python/PyQt5 implementation to a high-performance **Go + Wails + FlatBuffers** architecture.

### 🖥️ PC Application (Server)

- **Framework:** [Wails v2](https://wails.io) (Go + Svelte/Vite)
- **Backend:** Go (for high-speed networking and low-level input hooks)
- **Frontend:** HTML/CSS/JS (Modern Dark Mode UI)
- **Architecture:** Concurrent TCP server with non-blocking I/O.

### 📱 Android Application (Client)

- **Platform:** Android (SDK 24+)
- **Language:** Kotlin
- **Networking:** Refactored `ConnectionServiceV2` using coroutines and FlatBuffers.
- **Input Simulation:** Uses Accessibility Services for gestures and Input Method Services for typing.

### ⚡ Protocol (Shared)

- **Format:** [FlatBuffers](https://google.github.io/flatbuffers/)
- **Schema:** `shared/messages.fbs`
- **Advantages:** Zero-copy deserialization, type safety, and ultra-low overhead (< 1ms parsing).

## Features

- **Dynamic Edge Switching:** Seamlessly move your mouse off your PC screen to enter your phone.
- **Zero-Latency Protocol:** FlatBuffers-based binary stream for smooth cursor movement.
- **Modern UI:** Sleek, dark-themed dashboard with real-time status and logs.
- **Auto-Discovery:** UDP-based discovery finds your device instantly.
- **Multi-Touch Support:** (In Progress) Support for complex gestures.

## Quick Start

### 1. Build PC Application

You need [Go](https://go.dev/dl/) and [Wails](https://wails.io/docs/gettingstarted/installation) installed.

```powershell
cd pc-app
# Run in development mode
wails dev

# Build production binary
wails build
```

### 2. Build Android Application

Requires Android SDK 34 and JDK 21.

```powershell
cd android-app
.\gradlew.bat assembleDebug
```

The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Project Structure

```
wifikeycontrol/
├── shared/                 # FlatBuffers Schema definitions
├── pc-app/                 # Go + Wails PC Application
│   ├── main.go             # Application entry
│   ├── app.go              # Wails high-level controller
│   ├── server/             # Go TCP/UDP Server logic
│   ├── input/              # Windows Native Input Hooks
│   ├── protocol/           # Generated FlatBuffers code (Go)
│   └── frontend/           # Svelte/Vite UI
├── android-app/            # Kotlin Android Client
│   ├── app/src/main/java/  # Kotlin source code
│   │   └── wifikeycontrol/ # Generated FlatBuffers code (Kotlin)
│   │   └── com/wifikeycontrol/
│   │       ├── services/   # ConnectionV2 & Simulator services
│   │       └── protocol/   # FlatBuffers Handler
└── README.md
```

## Setup & Permissions

1. **Accessibility Service**: Enable `WiFi Key Control` in Android settings to allow mouse/touch simulation.
2. **Input Method**: Enable the `WiFi Key Control` keyboard to allow text input.
3. **Network**: Ensure both devices are on the same Wi-Fi network.

## License

Distributed under the GNU General Public License v3.0 (GPL-3.0). See `LICENSE` for more information.

---

**Note:** This project is part of a major performance overhaul. Legacy Python code has been archived.
