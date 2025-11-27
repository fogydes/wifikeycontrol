<!-- .github/copilot-instructions.md - Guidance for AI coding agents working on this repo -->
# WiFiKeyControl — Quick AI agent instructions

Purpose: help an AI code agent become productive quickly in this repository by highlighting architecture, important files, conventions, and actionable examples.

1) Big picture
- Two connected apps: `pc-app/` (Python PyQt5 server that captures input) and `android-app/` (Android client written in Kotlin). The PC app captures keyboard/mouse and transmits events to Android over TCP/UDP discovery.
- Data flow: `InputCapture` → `ProtocolHandler.create_packet(...)` → `ConnectionManager.send_packet(...)` → Android client's `ProtocolHandler.kt`.

2) Key files and responsibilities
- `pc-app/main.py` — PyQt5 GUI + app lifecycle. Entry point used by users and developers.
- `pc-app/connection_manager.py` — Networking: TCP server, UDP discovery, handshake JSON messages, heartbeat, and `send_packet`/`send_json` APIs. Default ports: `12346` (server) and `12345` (discovery).
- `pc-app/protocol.py` — Binary packet protocol used by PC side. Important: `create_packet`, `parse_packet`, `build_packet`, `calculate_checksum`. Uses CRC16 checksum, optional zlib compression (sets high bit 0x80 on packet type), and maintains `packet_sequence` (16-bit wrap).
- `pc-app/input_capture.py` — Global keyboard/mouse hooks (referenced by `main.py`) — read before modifying input handling.
- `android-app/app/src/main/.../protocol/ProtocolHandler.kt` — The Android implementation of the same protocol; update both sides when changing packet formats.
- `pc-app/requirements.txt` — Python dependencies (PyQt5, pynput, screeninfo, psutil). Tests: `pytest` and `pytest-qt` are present.

3) Important runtime/workflow commands
- PC app (Windows PowerShell):
  - Create venv, install deps, run:
    ```powershell
    cd pc-app
    python -m venv .venv
    .\.venv\Scripts\Activate.ps1
    pip install -r requirements.txt
    python main.py
    ```
- Android build (Windows):
  - Use the wrapper on Windows: `android-app\gradlew.bat assembleDebug` or open in Android Studio.
- Tests (PC):
  - `cd pc-app; python -m pytest tests/` (repo includes pytest dev deps)
- Packaging (PC):
  - `PyInstaller` listed in `requirements.txt` — use `pyinstaller` to produce a Windows executable if needed.

4) Protocol & compatibility notes (concrete details)
- Packet header magic: `HEADER_MAGIC = 0xAABB` (see `pc-app/protocol.py`).
- Packet types: the PC side expects event `type` strings like `mouse_move`, `mouse_click`, `key_press`, `key_release`, `mouse_scroll`, `control_switch`. When unknown, it falls back to JSON packets (type `0xFF`).
- Compression: payloads > 64 bytes may be zlib-compressed and flagged by setting the high bit `0x80` on the type byte — Android must detect and decompress.
- Checksum: CRC16 over packet (excluding last 2 checksum bytes). Use `calculate_checksum` and `verify_checksum` implementations as source of truth.
- Batching: `create_batch_packet(... )` produces `0xFE` packets containing JSON arrays; keep size limits in `ProtocolHandler.batch_events`.

5) Conventions and patterns an agent must follow
- When changing packet formats: update both `pc-app/protocol.py` and `android-app/.../ProtocolHandler.kt` simultaneously; tests or code that parse packets are authoritative.
- Keep sequence semantics: `packet_sequence` is stateful and wraps at 65536 — do not reset it arbitrarily.
- Use PyQt signals declared in `ConnectionManager` (`status_changed`, `device_discovered`, `log_message`) rather than direct UI modifications — `main.py` connects to these signals.
- Discovery & handshake: `ConnectionManager` sends a JSON handshake and expects a `handshake_response` with `device_name`; preserve this JSON contract if modifying connection flow.

6) Actionable examples for editing & tests
- Example: to emit a keyboard press packet from code:
  ```python
  from protocol import ProtocolHandler
  p = ProtocolHandler()
  ev = {'type':'key_press','key_code':65,'key':'a','pressed':True,'timestamp':1620000000000}
  packet = p.create_packet(ev)
  # send via ConnectionManager.send_packet(packet)
  ```
- Example: to validate checksum behavior in a unit test, reuse `calculate_checksum` and `verify_checksum` directly.

7) Safety and merge guidance
- Changing network defaults (ports) requires docs and UI updates (`main.py` displays ports in settings). Update README and tests.
- Avoid changing compression flag semantics unless you update Android counterpart and bump protocol version in `handshake` JSON.

8) Where to look for more context
- `planning.md` — design notes referenced by protocol constants.
- `README.md` — user/developer flows and common troubleshooting.

If anything in this summary is unclear or you want more examples (e.g., common code edits, tests to add, or a migration checklist for protocol changes), tell me which section to expand and I will iterate.
