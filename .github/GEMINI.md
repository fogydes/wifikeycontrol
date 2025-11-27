# Gemini AI Agent Instructions for WiFiKeyControl

This guide helps AI agents understand the WiFiKeyControl codebase to be productive quickly.

## 1. Big Picture

This project lets you control an Android device with a PC's keyboard and mouse over a network.

-   **`pc-app/`**: A Python server that runs on a PC. It captures keyboard/mouse input and sends it to the Android device. It uses PyQt5 for its UI.
-   **`android-app/`**: A native Android client (Kotlin) that receives input data and simulates touch and key-press events using an Accessibility Service and a custom Input Method.

The core data flow is:
`pc-app/input_capture.py` -> `pc-app/protocol.py` -> `pc-app/connection_manager.py` --(TCP/IP)--> `android-app/.../ConnectionService.kt` -> `android-app/.../protocol/ProtocolHandler.kt` -> `android-app/.../InputSimulatorService.kt`

## 2. Key Files & Responsibilities

### PC App (`pc-app/`)

-   `main.py`: Entry point. Manages the PyQt5 GUI, app lifecycle, and connects UI signals to the backend logic.
-   `connection_manager.py`: Handles all networking. Manages the TCP server for commands, UDP for device discovery, and the connection lifecycle (handshake, heartbeat, etc.).
-   `protocol.py`: Defines the binary communication protocol. Handles packet creation, parsing, checksums, and compression. **This is critical.**
-   `input_capture.py`: Uses `pynput` for global keyboard and mouse hooks to capture input events.
-   `requirements.txt`: Lists Python dependencies. Install using `pip install -r requirements.txt`.

### Android App (`android-app/`)

-   `app/src/main/.../MainActivity.kt`: Main UI for starting/stopping the service and configuring the connection.
-   `app/src/main/.../services/ConnectionService.kt`: Manages the TCP connection to the PC server, runs in the background.
-   `app/src/main/.../services/InputSimulatorService.kt`: An `AccessibilityService` that simulates gestures (taps, swipes) based on received mouse events.
-   `app/src/main/.../services/KeyboardService.kt`: An `InputMethodService` that simulates keyboard input.
-   `app/src/main/.../protocol/ProtocolHandler.kt`: The Kotlin implementation of the communication protocol. **Must be kept in sync with the Python `protocol.py`**.

## 3. Developer Workflows

### PC App

1.  **Setup (Powershell):**
    ```powershell
    cd pc-app
    python -m venv venv
    .\venv\Scripts\Activate.ps1
    pip install -r requirements.txt
    ```
2.  **Run:**
    ```powershell
    python main.py
    ```
3.  **Test:**
    ```powershell
    python -m pytest tests/
    ```

### Android App

1.  **Build:** Open the `android-app` directory in Android Studio or use Gradle from the command line.
    ```bash
    cd android-app
    ./gradlew assembleDebug
    ```
2.  **Install:**
    ```bash
    adb install app/build/outputs/apk/debug/app-debug.apk
    ```
3.  **Debug Logs:**
    ```bash
    adb logcat -s WiFiKeyControl
    ```

## 4. Communication Protocol

The PC and Android apps communicate over a custom stateful binary protocol defined in `protocol.py` and `ProtocolHandler.kt`.

-   **Magic Number**: Packets start with `0xAABB`.
-   **Packet Structure**: `[Magic][Type][Size][Sequence][Payload][Checksum]`
-   **Packet Types**: Differentiated by a type byte. Examples: `mouse_move`, `key_press`. `0xFF` is for JSON, `0xFE` is for batched events.
-   **Compression**: Payloads > 64 bytes may be zlib-compressed. This is flagged by setting the high bit (`0x80`) on the packet type byte. The client **must** check for this and decompress.
-   **Checksum**: A CRC16 checksum is used to ensure packet integrity.
-   **Stateful**: The protocol uses a sequence number that increments and wraps.

**IMPORTANT**: Any change to the protocol **must** be implemented on both the Python (server) and Kotlin (client) sides. Refer to `protocol.py` as the primary source of truth.

## 5. Conventions

-   **Protocol Changes**: When modifying the protocol, update both `pc-app/protocol.py` and `android-app/app/src/main/java/com/wifikeycontrol/protocol/ProtocolHandler.kt`. Also, update any relevant tests.
-   **PC App UI**: Do not modify the UI directly from background threads (`ConnectionManager`, `InputCapture`). Instead, emit PyQt signals (e.g., `status_changed`, `log_message`) and connect to them in `main.py`.
-   **Android Services**: The Android app relies heavily on background services (`ConnectionService`) and system-level permissions (Accessibility, Input Method). Be mindful of their lifecycles and thread safety.
-   **Networking**: The default TCP port is `12346` and the UDP discovery port is `12345`.
