# WiFi Key Control - PC Application

The PC server component for WiFi Key Control, built with [Wails v2](https://wails.io).

## Requirements

- Go 1.21+
- Wails CLI v2
- Windows 10/11 (for native input hooks)

## Development

```powershell
# Run in development mode with hot reload
wails dev
```

The dev server runs at `http://localhost:34115` for browser debugging.

## Building

```powershell
# Build production executable
wails build
```

Output: `build/bin/pc-app.exe`

## Architecture

- `app.go` - Main Wails application controller
- `server/` - TCP server for client connections, UDP for discovery
- `input/` - Windows low-level keyboard/mouse hooks
- `protocol/` - FlatBuffers generated Go code
- `frontend/` - HTML/CSS/JS UI

## Configuration

Edit `wails.json` for project settings. See [Wails documentation](https://wails.io/docs/reference/project-config).
