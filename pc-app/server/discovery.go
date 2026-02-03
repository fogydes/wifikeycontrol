package server

import (
	"encoding/json"
	"fmt"
	"net"
	"sync"
	"time"
)

const (
	discoveryMessage = "WIFIKEYCONTROL_DISCOVER"
	responsePrefix   = "WIFIKEYCONTROL_SERVER:"
)

// Discovery handles UDP broadcast for device discovery
type Discovery struct {
	conn     *net.UDPConn
	running  bool
	mu       sync.Mutex
	onDevice func(ip string, name string)
	onLog    func(msg string)
}

// NewDiscovery creates a new discovery service
func NewDiscovery() *Discovery {
	return &Discovery{}
}

// SetCallbacks sets event callbacks
func (d *Discovery) SetCallbacks(onDevice func(string, string), onLog func(string)) {
	d.onDevice = onDevice
	d.onLog = onLog
}

func (d *Discovery) log(msg string) {
	if d.onLog != nil {
		d.onLog(msg)
	}
}

// Start begins broadcasting discovery and listening for responses
func (d *Discovery) Start() error {
	d.mu.Lock()
	defer d.mu.Unlock()

	if d.running {
		return nil
	}

	// Listen on UDP port for responses
	addr := &net.UDPAddr{Port: UDPPort}
	conn, err := net.ListenUDP("udp", addr)
	if err != nil {
		return fmt.Errorf("failed to bind UDP port %d: %w", UDPPort, err)
	}

	d.conn = conn
	d.running = true
	d.log(fmt.Sprintf("Discovery started on UDP port %d", UDPPort))

	go d.listenLoop()
	go d.broadcastLoop()

	return nil
}

// Stop stops the discovery service
func (d *Discovery) Stop() {
	d.mu.Lock()
	defer d.mu.Unlock()

	if !d.running {
		return
	}

	d.running = false
	if d.conn != nil {
		d.conn.Close()
	}
	d.log("Discovery stopped")
}

func (d *Discovery) listenLoop() {
	buf := make([]byte, 1024)
	for d.running {
		d.conn.SetReadDeadline(time.Now().Add(time.Second))
		n, addr, err := d.conn.ReadFromUDP(buf)
		if err != nil {
			if netErr, ok := err.(net.Error); ok && netErr.Timeout() {
				continue
			}
			if d.running {
				d.log(fmt.Sprintf("UDP read error: %v", err))
			}
			continue
		}

		msg := string(buf[:n])
		d.handleMessage(msg, addr)
	}
}

func (d *Discovery) handleMessage(msg string, addr *net.UDPAddr) {
	// Check if it's a discovery response
	if len(msg) <= len(responsePrefix) {
		return
	}

	if msg[:len(responsePrefix)] != responsePrefix {
		return
	}

	// Parse JSON payload
	payload := msg[len(responsePrefix):]
	var data map[string]interface{}
	if err := json.Unmarshal([]byte(payload), &data); err != nil {
		return
	}

	name := "Unknown Device"
	if n, ok := data["name"].(string); ok {
		name = n
	}

	d.log(fmt.Sprintf("Discovered device: %s at %s", name, addr.IP.String()))
	if d.onDevice != nil {
		d.onDevice(addr.IP.String(), name)
	}
}

func (d *Discovery) broadcastLoop() {
	ticker := time.NewTicker(3 * time.Second)
	defer ticker.Stop()

	// Send initial broadcast
	d.sendBroadcast()

	for range ticker.C {
		if !d.running {
			return
		}
		d.sendBroadcast()
	}
}

func (d *Discovery) sendBroadcast() {
	// Broadcast to all network interfaces
	broadcastAddr := &net.UDPAddr{
		IP:   net.IPv4bcast,
		Port: UDPPort,
	}

	conn, err := net.DialUDP("udp", nil, broadcastAddr)
	if err != nil {
		return
	}
	defer conn.Close()

	conn.Write([]byte(discoveryMessage))
}
