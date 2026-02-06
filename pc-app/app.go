package main

import (
	"context"
	"fmt"
	"net"
	"os/exec"
	"sync"

	"pc-app/input"
	"pc-app/protocol/wifikeycontrol"
	"pc-app/server"

	"github.com/wailsapp/wails/v2/pkg/runtime"
)

// App struct - main application controller
type App struct {
	ctx       context.Context
	server    *server.Server
	discovery *server.Discovery
	capture   *input.Capture

	logs    []string
	logsMu  sync.Mutex
	maxLogs int
}

// NewApp creates a new App application struct
func NewApp() *App {
	app := &App{
		server:    server.NewServer(),
		discovery: server.NewDiscovery(),
		capture:   input.NewCapture(),
		maxLogs:   100,
	}

	return app
}

// startup is called when the app starts
func (a *App) startup(ctx context.Context) {
	a.ctx = ctx

	// Set up callbacks
	a.server.SetCallbacks(a.onConnect, a.onDisconnect, a.addLog, a.onControlReturn)
	a.discovery.SetCallbacks(a.onDeviceDiscovered, a.addLog)
	a.capture.SetCallbacks(a.onInputEvent, a.addLog)
}

// shutdown is called when the app is closing
func (a *App) shutdown(ctx context.Context) {
	a.capture.Stop()
	a.server.Stop()
	a.discovery.Stop()
}

// ============================================
// Server Controls (exposed to frontend)
// ============================================

// StartServer starts the TCP server
func (a *App) StartServer() error {
	if err := a.server.Start(); err != nil {
		return err
	}
	a.discovery.Start()
	runtime.EventsEmit(a.ctx, "server:started")
	return nil
}

// StopServer stops the TCP server
func (a *App) StopServer() {
	a.capture.Stop()
	a.server.Stop()
	a.discovery.Stop()
	runtime.EventsEmit(a.ctx, "server:stopped")
}

// IsServerRunning returns server status
func (a *App) IsServerRunning() bool {
	return a.server.IsRunning()
}

// IsConnected returns connection status
func (a *App) IsConnected() bool {
	return a.server.IsConnected()
}

// GetConnectedDevice returns the connected device name
func (a *App) GetConnectedDevice() string {
	return a.server.GetClientName()
}

// GetLocalIP returns the PC's local private network IP address
func (a *App) GetLocalIP() string {
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return "Unknown"
	}

	var fallback string
	for _, addr := range addrs {
		if ipnet, ok := addr.(*net.IPNet); ok && !ipnet.IP.IsLoopback() {
			ip4 := ipnet.IP.To4()
			if ip4 == nil {
				continue // Skip IPv6
			}
			// 10.x.x.x
			if ip4[0] == 10 {
				return ip4.String()
			}
			// 172.x.x.x
			if ip4[0] == 172 && ip4[1] >= 16 && ip4[1] <= 31 {
				if fallback == "" {
					fallback = ip4.String()
				}
			}
			// 192.168.x.x
			if ip4[0] == 192 && ip4[1] == 168 {
				if fallback == "" {
					fallback = ip4.String()
				}
			}
		}
	}
	if fallback != "" {
		return fallback
	}
	return "No LAN found"
}

// EnableUSBMode runs adb reverse to forward port 12346 for USB connections
func (a *App) EnableUSBMode() error {
	cmd := exec.Command("adb", "reverse", "tcp:12346", "tcp:12346")
	output, err := cmd.CombinedOutput()
	if err != nil {
		a.addLog(fmt.Sprintf("ADB reverse failed: %v - %s", err, string(output)))
		return fmt.Errorf("ADB reverse failed: %w", err)
	}
	a.addLog("USB Mode enabled - Android can connect to 127.0.0.1:12346")
	runtime.EventsEmit(a.ctx, "usb:enabled")
	return nil
}

// ============================================
// Input Capture Controls
// ============================================

// StartCapture starts input capture
func (a *App) StartCapture() error {
	if !a.server.IsConnected() {
		return fmt.Errorf("no device connected")
	}
	return a.capture.Start()
}

// StopCapture stops input capture
func (a *App) StopCapture() {
	a.capture.Stop()
}

// IsCapturing returns capture status
func (a *App) IsCapturing() bool {
	return a.capture.IsActive()
}

// IsControllingAndroid returns whether input is being sent to Android
func (a *App) IsControllingAndroid() bool {
	return a.capture.IsControlMode()
}

// ReturnToPC forces control back to PC
func (a *App) ReturnToPC() {
	a.capture.ReturnToPC()
}

// SetEdgeThreshold sets the edge detection threshold (1-50 pixels)
func (a *App) SetEdgeThreshold(threshold int) {
	a.capture.SetEdgeThreshold(threshold)
	a.addLog(fmt.Sprintf("Edge threshold set to %d pixels", threshold))
}

// GetEdgeThreshold returns the current edge threshold
func (a *App) GetEdgeThreshold() int {
	return a.capture.GetEdgeThreshold()
}

// ============================================
// Logs
// ============================================

// GetLogs returns recent log messages
func (a *App) GetLogs() []string {
	a.logsMu.Lock()
	defer a.logsMu.Unlock()
	return append([]string{}, a.logs...)
}

// ClearLogs clears all logs
func (a *App) ClearLogs() {
	a.logsMu.Lock()
	defer a.logsMu.Unlock()
	a.logs = nil
}

// ============================================
// Internal callbacks
// ============================================

func (a *App) addLog(msg string) {
	a.logsMu.Lock()
	a.logs = append(a.logs, msg)
	if len(a.logs) > a.maxLogs {
		a.logs = a.logs[1:]
	}
	a.logsMu.Unlock()

	runtime.EventsEmit(a.ctx, "log", msg)
}

func (a *App) onConnect(deviceName string) {
	a.addLog(fmt.Sprintf("Connected to %s", deviceName))
	runtime.EventsEmit(a.ctx, "device:connected", deviceName)

	// Auto-start capture when device connects
	a.capture.Start()
}

func (a *App) onDisconnect() {
	a.capture.Stop()
	runtime.EventsEmit(a.ctx, "device:disconnected")
}

func (a *App) onControlReturn() {
	a.addLog("Control returned from Android")
	a.capture.SwitchToPCMode()
	runtime.EventsEmit(a.ctx, "control:returned")
}

func (a *App) onDeviceDiscovered(ip, name string) {
	runtime.EventsEmit(a.ctx, "device:discovered", map[string]string{
		"ip":   ip,
		"name": name,
	})
}

func (a *App) onInputEvent(event input.InputEvent) {
	if !a.server.IsConnected() {
		return
	}

	var err error

	switch event.Type {
	case "mouse_move":
		err = a.server.SendMouseMove(event.DX, event.DY)

	case "mouse_click":
		button := buttonStringToEnum(event.Button)
		err = a.server.SendMouseClick(button, event.Pressed)

	case "mouse_scroll":
		err = a.server.SendMouseScroll(event.DX, event.DY)

	case "key":
		err = a.server.SendKeyPress(event.KeyCode, event.Key, event.Modifiers, event.Pressed)

	case "control_switch":
		edge := edgeStringToEnum(event.Edge)
		err = a.server.SendControlSwitch(edge)
		runtime.EventsEmit(a.ctx, "control:switched", event.Edge)
	}

	if err != nil {
		a.addLog(fmt.Sprintf("Send error: %v", err))
	}
}

func buttonStringToEnum(button string) wifikeycontrol.MouseButton {
	switch button {
	case "left":
		return wifikeycontrol.MouseButtonLeft
	case "right":
		return wifikeycontrol.MouseButtonRight
	case "middle":
		return wifikeycontrol.MouseButtonMiddle
	default:
		return wifikeycontrol.MouseButtonNone
	}
}

func edgeStringToEnum(edge string) wifikeycontrol.Edge {
	switch edge {
	case "left":
		return wifikeycontrol.EdgeLeft
	case "right":
		return wifikeycontrol.EdgeRight
	case "top":
		return wifikeycontrol.EdgeTop
	case "bottom":
		return wifikeycontrol.EdgeBottom
	case "return_to_pc":
		return wifikeycontrol.EdgeReturnToPC
	default:
		return wifikeycontrol.EdgeNone
	}
}
