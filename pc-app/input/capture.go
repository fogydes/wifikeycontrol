package input

import (
	"sync"
	"unsafe"

	"github.com/moutend/go-hook/pkg/keyboard"
	"github.com/moutend/go-hook/pkg/mouse"
	"github.com/moutend/go-hook/pkg/types"
	"golang.org/x/sys/windows"
)

var (
	user32           = windows.NewLazySystemDLL("user32.dll")
	getSystemMetrics = user32.NewProc("GetSystemMetrics")
	setCursorPos     = user32.NewProc("SetCursorPos")
	getCursorPos     = user32.NewProc("GetCursorPos")
)

const (
	SM_CXSCREEN = 0
	SM_CYSCREEN = 1
)

// Windows mouse messages (not in go-hook/types)
const (
	WM_MOUSEMOVE   types.Message = 0x0200
	WM_LBUTTONDOWN types.Message = 0x0201
	WM_LBUTTONUP   types.Message = 0x0202
	WM_RBUTTONDOWN types.Message = 0x0204
	WM_RBUTTONUP   types.Message = 0x0205
	WM_MBUTTONDOWN types.Message = 0x0207
	WM_MBUTTONUP   types.Message = 0x0208
	WM_MOUSEWHEEL  types.Message = 0x020A
)

// InputEvent represents a captured input event
type InputEvent struct {
	Type      string
	DX        int16
	DY        int16
	Button    string
	Pressed   bool
	Key       string
	KeyCode   int32
	Modifiers byte
	Edge      string
}

// Capture handles keyboard and mouse input capture
type Capture struct {
	screenWidth   int
	screenHeight  int
	centerX       int
	centerY       int
	edgeThreshold int

	active      bool
	controlMode bool // true = sending to Android
	mu          sync.Mutex

	keyboardChan chan types.KeyboardEvent
	mouseChan    chan types.MouseEvent

	onEvent func(InputEvent)
	onLog   func(string)

	suppressNextMove bool
	lastX, lastY     int
}

// NewCapture creates a new input capture instance
func NewCapture() *Capture {
	c := &Capture{
		edgeThreshold: 5,
		keyboardChan:  make(chan types.KeyboardEvent, 100),
		mouseChan:     make(chan types.MouseEvent, 100),
	}
	c.updateScreenDimensions()
	return c
}

// SetCallbacks sets event callbacks
func (c *Capture) SetCallbacks(onEvent func(InputEvent), onLog func(string)) {
	c.onEvent = onEvent
	c.onLog = onLog
}

// SetEdgeThreshold sets the edge detection threshold in pixels
func (c *Capture) SetEdgeThreshold(threshold int) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if threshold < 1 {
		threshold = 1
	}
	if threshold > 50 {
		threshold = 50
	}
	c.edgeThreshold = threshold
}

// GetEdgeThreshold returns the current edge detection threshold
func (c *Capture) GetEdgeThreshold() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.edgeThreshold
}

func (c *Capture) log(msg string) {
	if c.onLog != nil {
		c.onLog(msg)
	}
}

func (c *Capture) updateScreenDimensions() {
	w, _, _ := getSystemMetrics.Call(SM_CXSCREEN)
	h, _, _ := getSystemMetrics.Call(SM_CYSCREEN)

	c.screenWidth = int(w)
	c.screenHeight = int(h)
	c.centerX = c.screenWidth / 2
	c.centerY = c.screenHeight / 2
}

// Start begins capturing input
func (c *Capture) Start() error {
	c.mu.Lock()
	defer c.mu.Unlock()

	if c.active {
		return nil
	}

	c.active = true
	c.controlMode = false

	// Install keyboard hook
	if err := keyboard.Install(nil, c.keyboardChan); err != nil {
		return err
	}

	// Install mouse hook
	if err := mouse.Install(nil, c.mouseChan); err != nil {
		keyboard.Uninstall()
		return err
	}

	go c.processEvents()
	c.log("Input capture started")
	return nil
}

// Stop stops capturing input
func (c *Capture) Stop() {
	c.mu.Lock()
	defer c.mu.Unlock()

	if !c.active {
		return
	}

	c.active = false
	c.controlMode = false
	keyboard.Uninstall()
	mouse.Uninstall()
	c.log("Input capture stopped")
}

// IsActive returns whether capture is active
func (c *Capture) IsActive() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.active
}

// IsControlMode returns whether in Android control mode
func (c *Capture) IsControlMode() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.controlMode
}

// SwitchToAndroidMode enables Android control mode
func (c *Capture) SwitchToAndroidMode() {
	c.mu.Lock()
	c.controlMode = true
	c.suppressNextMove = true
	c.mu.Unlock()

	// Trap cursor to center
	setCursorPos.Call(uintptr(c.centerX), uintptr(c.centerY))
	c.log("Switched to Android control mode")
}

// SwitchToPCMode returns to PC control mode
func (c *Capture) SwitchToPCMode() {
	c.mu.Lock()
	c.controlMode = false
	c.mu.Unlock()
	c.log("Switched to PC control mode")
}

func (c *Capture) processEvents() {
	for c.active {
		select {
		case kbEvent := <-c.keyboardChan:
			c.handleKeyboard(kbEvent)
		case mEvent := <-c.mouseChan:
			c.handleMouse(mEvent)
		}
	}
}

func (c *Capture) handleKeyboard(event types.KeyboardEvent) {
	c.mu.Lock()
	controlMode := c.controlMode
	c.mu.Unlock()

	if !controlMode {
		return
	}

	pressed := event.Message == types.WM_KEYDOWN || event.Message == types.WM_SYSKEYDOWN

	inputEvent := InputEvent{
		Type:    "key",
		Pressed: pressed,
		KeyCode: int32(event.VKCode),
		Key:     vkCodeToString(event.VKCode),
	}

	if c.onEvent != nil {
		c.onEvent(inputEvent)
	}
}

func (c *Capture) handleMouse(event types.MouseEvent) {
	c.mu.Lock()
	controlMode := c.controlMode
	suppressNext := c.suppressNextMove
	c.mu.Unlock()

	switch event.Message {
	case WM_MOUSEMOVE:
		c.handleMouseMove(int(event.X), int(event.Y), controlMode, suppressNext)

	case WM_LBUTTONDOWN, WM_LBUTTONUP:
		if controlMode {
			c.handleMouseClick("left", event.Message == WM_LBUTTONDOWN)
		}

	case WM_RBUTTONDOWN, WM_RBUTTONUP:
		if controlMode {
			c.handleMouseClick("right", event.Message == WM_RBUTTONDOWN)
		}

	case WM_MBUTTONDOWN, WM_MBUTTONUP:
		if controlMode {
			c.handleMouseClick("middle", event.Message == WM_MBUTTONDOWN)
		}

	case WM_MOUSEWHEEL:
		if controlMode {
			// High word of mouseData contains wheel delta
			delta := int16(event.MouseData >> 16)
			c.handleMouseScroll(0, delta)
		}
	}
}

func (c *Capture) handleMouseMove(x, y int, controlMode bool, suppressNext bool) {
	if suppressNext {
		c.mu.Lock()
		c.suppressNextMove = false
		c.mu.Unlock()
		return
	}

	if !controlMode {
		// Check for edge entry
		edge := c.checkEdge(x, y)
		if edge != "" {
			c.SwitchToAndroidMode()
			if c.onEvent != nil {
				c.onEvent(InputEvent{Type: "control_switch", Edge: edge})
			}
		}
		c.lastX = x
		c.lastY = y
		return
	}

	// Calculate relative movement from center
	dx := x - c.centerX
	dy := y - c.centerY

	if dx == 0 && dy == 0 {
		return
	}

	if c.onEvent != nil {
		c.onEvent(InputEvent{
			Type: "mouse_move",
			DX:   int16(dx),
			DY:   int16(dy),
		})
	}

	// Re-trap to center
	c.mu.Lock()
	c.suppressNextMove = true
	c.mu.Unlock()
	setCursorPos.Call(uintptr(c.centerX), uintptr(c.centerY))
}

func (c *Capture) handleMouseClick(button string, pressed bool) {
	if c.onEvent != nil {
		c.onEvent(InputEvent{
			Type:    "mouse_click",
			Button:  button,
			Pressed: pressed,
		})
	}
}

func (c *Capture) handleMouseScroll(dx, dy int16) {
	if c.onEvent != nil {
		c.onEvent(InputEvent{
			Type: "mouse_scroll",
			DX:   dx,
			DY:   dy,
		})
	}
}

func (c *Capture) checkEdge(x, y int) string {
	if x <= c.edgeThreshold {
		return "left"
	}
	if x >= c.screenWidth-c.edgeThreshold {
		return "right"
	}
	if y <= c.edgeThreshold {
		return "top"
	}
	if y >= c.screenHeight-c.edgeThreshold {
		return "bottom"
	}
	return ""
}

// ReturnToPC forces control back to PC
func (c *Capture) ReturnToPC() {
	if c.IsControlMode() {
		c.SwitchToPCMode()
		if c.onEvent != nil {
			c.onEvent(InputEvent{Type: "control_switch", Edge: "return_to_pc"})
		}
	}
}

func vkCodeToString(vk types.VKCode) string {
	// Common virtual key codes
	keyMap := map[types.VKCode]string{
		types.VK_BACK:    "backspace",
		types.VK_TAB:     "tab",
		types.VK_RETURN:  "enter",
		types.VK_SHIFT:   "shift",
		types.VK_CONTROL: "ctrl",
		types.VK_MENU:    "alt",
		types.VK_CAPITAL: "capslock",
		types.VK_ESCAPE:  "escape",
		types.VK_SPACE:   "space",
		types.VK_PRIOR:   "pageup",
		types.VK_NEXT:    "pagedown",
		types.VK_END:     "end",
		types.VK_HOME:    "home",
		types.VK_LEFT:    "left",
		types.VK_UP:      "up",
		types.VK_RIGHT:   "right",
		types.VK_DOWN:    "down",
		types.VK_INSERT:  "insert",
		types.VK_DELETE:  "delete",
	}

	if name, ok := keyMap[vk]; ok {
		return name
	}

	// A-Z
	if vk >= types.VK_A && vk <= types.VK_Z {
		return string(rune('a' + int(vk-types.VK_A)))
	}

	// 0-9
	if vk >= types.VK_0 && vk <= types.VK_9 {
		return string(rune('0' + int(vk-types.VK_0)))
	}

	// F1-F12
	if vk >= types.VK_F1 && vk <= types.VK_F12 {
		return "f" + string(rune('1'+int(vk-types.VK_F1)))
	}

	return ""
}

// POINT struct for GetCursorPos
type point struct {
	X, Y int32
}

func getCursorPosition() (int, int) {
	var pt point
	getCursorPos.Call(uintptr(unsafe.Pointer(&pt)))
	return int(pt.X), int(pt.Y)
}
