# WiFiKeyControl - Project Map

> **Source of Truth** for project state, data schemas, and behavioral rules.

---

## 🎯 North Star

**Input control transfer from PC to Android phone** — seamless switching like multi-monitor cursor transitions. Target latency: **<50ms**. Supports WiFi/LAN and USB/ADB connections.

---

## 📡 Integrations

| Component   | Status      | Technology                               |
| ----------- | ----------- | ---------------------------------------- |
| PC App      | 🔄 Refactor | **Go + Wails** (recommended)             |
| Android App | 🔄 Refactor | **Kotlin** (Accessibility + InputMethod) |
| Protocol    | 🔄 Refactor | **FlatBuffers** (shared schema)          |

---

## 🔧 Behavioral Rules

| Rule                | Value                                      |
| ------------------- | ------------------------------------------ |
| Latency target      | <50ms (as fast as possible)                |
| Auto-reconnect      | Optional (off by default)                  |
| Data storage        | Local only (future: subscription features) |
| No external servers | ✅                                         |

---

## 📊 Data Schema (FlatBuffers)

### Input Events

```fbs
// messages.fbs - Shared protocol schema
namespace wifikeycontrol;

enum EventType : byte {
  MouseMoveRel = 1,
  MouseClick,
  MouseScroll,
  KeyPress,
  KeyRelease,
  ControlSwitch,
  Heartbeat
}

enum MouseButton : byte { Left = 1, Right, Middle, X1, X2 }
enum Edge : byte { Left = 1, Right, Top, Bottom, Hotkey, ReturnToPC }

table MouseMoveEvent {
  dx: short;
  dy: short;
}

table MouseClickEvent {
  button: MouseButton;
  pressed: bool;
}

table MouseScrollEvent {
  dx: short;
  dy: short;
}

table KeyEvent {
  keycode: int;
  key: string;
  modifiers: ubyte;
}

table ControlSwitchEvent {
  edge: Edge;
}

table InputEvent {
  type: EventType;
  timestamp: ulong;
  mouse_move: MouseMoveEvent;
  mouse_click: MouseClickEvent;
  mouse_scroll: MouseScrollEvent;
  key: KeyEvent;
  control_switch: ControlSwitchEvent;
}

root_type InputEvent;
```

### Connection Handshake (JSON over TCP)

```json
// PC → Android
{ "type": "hello", "version": 1, "name": "PC-Name" }

// Android → PC
{ "type": "hello_ack", "version": 1, "name": "Phone-Name", "screen": [1080, 2400] }
```

---

## 📁 Project Structure

```
wifikeycontrol/
├── gemini.md              # This file - Project Map
├── architecture/          # Layer 1: SOPs
│   ├── protocol.md        # Protocol specification
│   ├── pc-app.md          # PC app architecture
│   └── android-app.md     # Android app architecture
├── shared/
│   └── messages.fbs       # FlatBuffers schema (single source)
├── pc-app/                # Go + Wails application
└── android-app/           # Kotlin application
```

---

## 📝 Context Handoff Log

| Date       | Change                                  | Next Step                        |
| ---------- | --------------------------------------- | -------------------------------- |
| 2026-02-03 | Initialized project map                 | Await discovery answers          |
| 2026-02-03 | Discovery complete, defined schema      | Create implementation plan       |
| 2026-02-03 | **PC app refactor complete (Go+Wails)** | Refactor Android for FlatBuffers |

---

## ✅ Discovery Status: COMPLETE
