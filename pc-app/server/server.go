package server

import (
	"encoding/binary"
	"encoding/json"
	"fmt"
	"net"
	"sync"
	"time"

	flatbuffers "github.com/google/flatbuffers/go"
	"pc-app/protocol/wifikeycontrol"
)

const (
	TCPPort         = 12346
	UDPPort         = 12345
	HeartbeatPeriod = 5 * time.Second
)

// Client represents a connected Android device
type Client struct {
	Conn       net.Conn
	Name       string
	ScreenW    int
	ScreenH    int
	Connected  bool
	LastActive time.Time
}

// Server manages connections to Android devices
type Server struct {
	listener    net.Listener
	client      *Client
	clientMu    sync.RWMutex
	running     bool
	runMu       sync.Mutex
	onConnect   func(name string)
	onDisconnect func()
	onLog       func(msg string)
}

// NewServer creates a new server instance
func NewServer() *Server {
	return &Server{}
}

// SetCallbacks sets event callbacks
func (s *Server) SetCallbacks(onConnect func(string), onDisconnect func(), onLog func(string)) {
	s.onConnect = onConnect
	s.onDisconnect = onDisconnect
	s.onLog = onLog
}

func (s *Server) log(msg string) {
	if s.onLog != nil {
		s.onLog(msg)
	}
}

// Start begins listening for connections
func (s *Server) Start() error {
	s.runMu.Lock()
	defer s.runMu.Unlock()

	if s.running {
		return fmt.Errorf("server already running")
	}

	listener, err := net.Listen("tcp", fmt.Sprintf(":%d", TCPPort))
	if err != nil {
		return fmt.Errorf("failed to listen on port %d: %w", TCPPort, err)
	}

	s.listener = listener
	s.running = true
	s.log(fmt.Sprintf("Server started on port %d", TCPPort))

	go s.acceptLoop()
	return nil
}

// Stop stops the server
func (s *Server) Stop() {
	s.runMu.Lock()
	defer s.runMu.Unlock()

	if !s.running {
		return
	}

	s.running = false
	if s.listener != nil {
		s.listener.Close()
	}
	s.disconnectClient()
	s.log("Server stopped")
}

// IsRunning returns whether server is running
func (s *Server) IsRunning() bool {
	s.runMu.Lock()
	defer s.runMu.Unlock()
	return s.running
}

// IsConnected returns whether a client is connected
func (s *Server) IsConnected() bool {
	s.clientMu.RLock()
	defer s.clientMu.RUnlock()
	return s.client != nil && s.client.Connected
}

// GetClientName returns connected client name
func (s *Server) GetClientName() string {
	s.clientMu.RLock()
	defer s.clientMu.RUnlock()
	if s.client != nil {
		return s.client.Name
	}
	return ""
}

func (s *Server) acceptLoop() {
	for s.running {
		conn, err := s.listener.Accept()
		if err != nil {
			if s.running {
				s.log(fmt.Sprintf("Accept error: %v", err))
			}
			continue
		}

		// Only allow one client at a time
		s.clientMu.Lock()
		if s.client != nil && s.client.Connected {
			conn.Close()
			s.log("Rejected connection: client already connected")
			s.clientMu.Unlock()
			continue
		}
		s.clientMu.Unlock()

		go s.handleClient(conn)
	}
}

func (s *Server) handleClient(conn net.Conn) {
	s.log(fmt.Sprintf("New connection from %s", conn.RemoteAddr()))

	// Perform handshake
	client, err := s.doHandshake(conn)
	if err != nil {
		s.log(fmt.Sprintf("Handshake failed: %v", err))
		conn.Close()
		return
	}

	s.clientMu.Lock()
	s.client = client
	s.clientMu.Unlock()

	if s.onConnect != nil {
		s.onConnect(client.Name)
	}

	s.log(fmt.Sprintf("Connected to %s (screen: %dx%d)", client.Name, client.ScreenW, client.ScreenH))

	// Start heartbeat
	go s.heartbeatLoop()

	// Read loop
	s.readLoop(conn)

	s.disconnectClient()
}

func (s *Server) doHandshake(conn net.Conn) (*Client, error) {
	// Send hello
	hello := map[string]interface{}{
		"type":    "hello",
		"version": 1,
		"name":    "PC",
	}
	helloData, _ := json.Marshal(hello)
	helloData = append(helloData, '\n')
	
	conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if _, err := conn.Write(helloData); err != nil {
		return nil, fmt.Errorf("failed to send hello: %w", err)
	}

	// Read hello_ack
	conn.SetReadDeadline(time.Now().Add(10 * time.Second))
	buf := make([]byte, 4096)
	n, err := conn.Read(buf)
	if err != nil {
		return nil, fmt.Errorf("failed to read hello_ack: %w", err)
	}

	var ack map[string]interface{}
	if err := json.Unmarshal(buf[:n], &ack); err != nil {
		return nil, fmt.Errorf("invalid hello_ack: %w", err)
	}

	if ack["type"] != "hello_ack" {
		return nil, fmt.Errorf("unexpected message type: %v", ack["type"])
	}

	// Parse screen dimensions
	screenW, screenH := 1080, 1920
	if screen, ok := ack["screen"].([]interface{}); ok && len(screen) >= 2 {
		if w, ok := screen[0].(float64); ok {
			screenW = int(w)
		}
		if h, ok := screen[1].(float64); ok {
			screenH = int(h)
		}
	}

	conn.SetReadDeadline(time.Time{})
	conn.SetWriteDeadline(time.Time{})

	name := "Android Device"
	if n, ok := ack["name"].(string); ok {
		name = n
	}

	return &Client{
		Conn:       conn,
		Name:       name,
		ScreenW:    screenW,
		ScreenH:    screenH,
		Connected:  true,
		LastActive: time.Now(),
	}, nil
}

func (s *Server) readLoop(conn net.Conn) {
	buf := make([]byte, 4096)
	for {
		n, err := conn.Read(buf)
		if err != nil {
			s.log(fmt.Sprintf("Read error: %v", err))
			return
		}

		s.clientMu.Lock()
		if s.client != nil {
			s.client.LastActive = time.Now()
		}
		s.clientMu.Unlock()

		// Process incoming messages (heartbeat acks, etc.)
		_ = n // TODO: parse incoming FlatBuffer messages
	}
}

func (s *Server) heartbeatLoop() {
	ticker := time.NewTicker(HeartbeatPeriod)
	defer ticker.Stop()

	for range ticker.C {
		s.clientMu.RLock()
		client := s.client
		s.clientMu.RUnlock()

		if client == nil || !client.Connected {
			return
		}

		// Check for timeout
		if time.Since(client.LastActive) > HeartbeatPeriod*3 {
			s.log("Client heartbeat timeout")
			s.disconnectClient()
			return
		}

		// Send heartbeat
		if err := s.SendHeartbeat(); err != nil {
			s.log(fmt.Sprintf("Heartbeat send failed: %v", err))
			return
		}
	}
}

func (s *Server) disconnectClient() {
	s.clientMu.Lock()
	defer s.clientMu.Unlock()

	if s.client == nil {
		return
	}

	if s.client.Conn != nil {
		s.client.Conn.Close()
	}
	s.client.Connected = false
	s.client = nil

	if s.onDisconnect != nil {
		s.onDisconnect()
	}
	s.log("Client disconnected")
}

// SendEvent sends an input event to the connected client
func (s *Server) SendEvent(builder *flatbuffers.Builder) error {
	s.clientMu.RLock()
	client := s.client
	s.clientMu.RUnlock()

	if client == nil || !client.Connected {
		return fmt.Errorf("no client connected")
	}

	buf := builder.FinishedBytes()
	
	// Write length prefix (4 bytes, little endian) + data
	lenBuf := make([]byte, 4)
	binary.LittleEndian.PutUint32(lenBuf, uint32(len(buf)))
	
	client.Conn.SetWriteDeadline(time.Now().Add(100 * time.Millisecond))
	if _, err := client.Conn.Write(lenBuf); err != nil {
		return err
	}
	if _, err := client.Conn.Write(buf); err != nil {
		return err
	}
	
	return nil
}

// SendHeartbeat sends a heartbeat to the client
func (s *Server) SendHeartbeat() error {
	builder := flatbuffers.NewBuilder(64)
	
	wifikeycontrol.InputEventStart(builder)
	wifikeycontrol.InputEventAddType(builder, wifikeycontrol.EventTypeHeartbeat)
	wifikeycontrol.InputEventAddTimestamp(builder, uint64(time.Now().UnixMilli()))
	event := wifikeycontrol.InputEventEnd(builder)
	builder.Finish(event)
	
	return s.SendEvent(builder)
}

// SendMouseMove sends a relative mouse move event
func (s *Server) SendMouseMove(dx, dy int16) error {
	builder := flatbuffers.NewBuilder(128)
	
	// Create mouse move event
	wifikeycontrol.MouseMoveEventStart(builder)
	wifikeycontrol.MouseMoveEventAddDx(builder, dx)
	wifikeycontrol.MouseMoveEventAddDy(builder, dy)
	mouseMove := wifikeycontrol.MouseMoveEventEnd(builder)
	
	// Create input event
	wifikeycontrol.InputEventStart(builder)
	wifikeycontrol.InputEventAddType(builder, wifikeycontrol.EventTypeMouseMoveRel)
	wifikeycontrol.InputEventAddTimestamp(builder, uint64(time.Now().UnixMilli()))
	wifikeycontrol.InputEventAddMouseMove(builder, mouseMove)
	event := wifikeycontrol.InputEventEnd(builder)
	builder.Finish(event)
	
	return s.SendEvent(builder)
}

// SendMouseClick sends a mouse click event
func (s *Server) SendMouseClick(button wifikeycontrol.MouseButton, pressed bool) error {
	builder := flatbuffers.NewBuilder(128)
	
	wifikeycontrol.MouseClickEventStart(builder)
	wifikeycontrol.MouseClickEventAddButton(builder, button)
	wifikeycontrol.MouseClickEventAddPressed(builder, pressed)
	mouseClick := wifikeycontrol.MouseClickEventEnd(builder)
	
	wifikeycontrol.InputEventStart(builder)
	wifikeycontrol.InputEventAddType(builder, wifikeycontrol.EventTypeMouseClick)
	wifikeycontrol.InputEventAddTimestamp(builder, uint64(time.Now().UnixMilli()))
	wifikeycontrol.InputEventAddMouseClick(builder, mouseClick)
	event := wifikeycontrol.InputEventEnd(builder)
	builder.Finish(event)
	
	return s.SendEvent(builder)
}

// SendMouseScroll sends a scroll event
func (s *Server) SendMouseScroll(dx, dy int16) error {
	builder := flatbuffers.NewBuilder(128)
	
	wifikeycontrol.MouseScrollEventStart(builder)
	wifikeycontrol.MouseScrollEventAddDx(builder, dx)
	wifikeycontrol.MouseScrollEventAddDy(builder, dy)
	mouseScroll := wifikeycontrol.MouseScrollEventEnd(builder)
	
	wifikeycontrol.InputEventStart(builder)
	wifikeycontrol.InputEventAddType(builder, wifikeycontrol.EventTypeMouseScroll)
	wifikeycontrol.InputEventAddTimestamp(builder, uint64(time.Now().UnixMilli()))
	wifikeycontrol.InputEventAddMouseScroll(builder, mouseScroll)
	event := wifikeycontrol.InputEventEnd(builder)
	builder.Finish(event)
	
	return s.SendEvent(builder)
}

// SendKeyPress sends a key event
func (s *Server) SendKeyPress(keycode int32, key string, modifiers byte, pressed bool) error {
	builder := flatbuffers.NewBuilder(256)
	
	keyStr := builder.CreateString(key)
	
	wifikeycontrol.KeyEventStart(builder)
	wifikeycontrol.KeyEventAddKeycode(builder, keycode)
	wifikeycontrol.KeyEventAddKey(builder, keyStr)
	wifikeycontrol.KeyEventAddModifiers(builder, modifiers)
	keyEvent := wifikeycontrol.KeyEventEnd(builder)
	
	eventType := wifikeycontrol.EventTypeKeyPress
	if !pressed {
		eventType = wifikeycontrol.EventTypeKeyRelease
	}
	
	wifikeycontrol.InputEventStart(builder)
	wifikeycontrol.InputEventAddType(builder, eventType)
	wifikeycontrol.InputEventAddTimestamp(builder, uint64(time.Now().UnixMilli()))
	wifikeycontrol.InputEventAddKey(builder, keyEvent)
	event := wifikeycontrol.InputEventEnd(builder)
	builder.Finish(event)
	
	return s.SendEvent(builder)
}

// SendControlSwitch sends a control switch event
func (s *Server) SendControlSwitch(edge wifikeycontrol.Edge) error {
	builder := flatbuffers.NewBuilder(128)
	
	wifikeycontrol.ControlSwitchEventStart(builder)
	wifikeycontrol.ControlSwitchEventAddEdge(builder, edge)
	controlSwitch := wifikeycontrol.ControlSwitchEventEnd(builder)
	
	wifikeycontrol.InputEventStart(builder)
	wifikeycontrol.InputEventAddType(builder, wifikeycontrol.EventTypeControlSwitch)
	wifikeycontrol.InputEventAddTimestamp(builder, uint64(time.Now().UnixMilli()))
	wifikeycontrol.InputEventAddControlSwitch(builder, controlSwitch)
	event := wifikeycontrol.InputEventEnd(builder)
	builder.Finish(event)
	
	return s.SendEvent(builder)
}
