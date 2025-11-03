#!/usr/bin/env python3

import socket
import threading
import time
import json
import struct
import hashlib
from PyQt5.QtCore import QObject, pyqtSignal
from typing import Optional, Dict, List

class ConnectionManager(QObject):
    # Signals
    status_changed = pyqtSignal(bool, str)
    device_discovered = pyqtSignal(dict)
    log_message = pyqtSignal(str)

    def __init__(self):
        super().__init__()
        self.server_socket = None
        self.discovery_socket = None
        self.client_socket = None
        self.client_address = None
        self.client_thread = None
        self.discovery_thread = None
        self.heartbeat_thread = None

        self.server_running = False
        self.connected = False
        self.device_name = ""

        self.server_port = 12346
        self.discovery_port = 12345

        # Protocol constants
        self.DISCOVERY_MESSAGE = b"WIFIKEY_DISCOVERY"
        self.DISCOVERY_RESPONSE = b"WIFIKEY_RESPONSE"
        self.HEARTBEAT_INTERVAL = 5.0  # seconds
        self.CONNECTION_TIMEOUT = 10.0  # seconds

        self.lock = threading.Lock()

    def start_server(self, server_port: int = 12346, discovery_port: int = 12345) -> bool:
        """Start the TCP server and UDP discovery"""
        with self.lock:
            if self.server_running:
                return True

            self.server_port = server_port
            self.discovery_port = discovery_port

            try:
                # Start TCP server
                self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                self.server_socket.bind(('0.0.0.0', self.server_port))
                self.server_socket.listen(1)
                self.server_socket.settimeout(1.0)

                # Start UDP discovery
                self.discovery_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                self.discovery_socket.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
                self.discovery_socket.bind(('0.0.0.0', self.discovery_port))
                self.discovery_socket.settimeout(1.0)

                self.server_running = True
                self.log_message.emit(f"Server started on port {self.server_port}")
                self.log_message.emit(f"Discovery listening on port {self.discovery_port}")

                # Start client acceptance thread
                self.client_thread = threading.Thread(target=self.accept_clients, daemon=True)
                self.client_thread.start()

                # Start discovery broadcast thread
                self.discovery_thread = threading.Thread(target=self.broadcast_discovery, daemon=True)
                self.discovery_thread.start()

                # Start heartbeat thread
                self.heartbeat_thread = threading.Thread(target=self.heartbeat_monitor, daemon=True)
                self.heartbeat_thread.start()

                return True

            except Exception as e:
                self.log_message.emit(f"Failed to start server: {e}")
                self.cleanup()
                return False

    def stop_server(self):
        """Stop the server and disconnect clients"""
        with self.lock:
            if not self.server_running:
                return

            self.server_running = False
            self.log_message.emit("Stopping server...")

        self.disconnect_client()
        self.cleanup()

    def cleanup(self):
        """Clean up sockets and threads"""
        try:
            if self.server_socket:
                self.server_socket.close()
                self.server_socket = None

            if self.discovery_socket:
                self.discovery_socket.close()
                self.discovery_socket = None
        except Exception as e:
            self.log_message.emit(f"Error during cleanup: {e}")

    def accept_clients(self):
        """Accept incoming client connections"""
        while self.server_running:
            try:
                client_socket, client_address = self.server_socket.accept()
                self.log_message.emit(f"Connection attempt from {client_address}")

                # Handle the client connection
                self.handle_client_connection(client_socket, client_address)

            except socket.timeout:
                continue
            except Exception as e:
                if self.server_running:
                    self.log_message.emit(f"Error accepting client: {e}")
                break

    def handle_client_connection(self, client_socket: socket.socket, client_address: tuple):
        """Handle a new client connection"""
        try:
            # Send handshake request
            handshake = {
                'type': 'handshake',
                'version': '1.0',
                'timestamp': time.time()
            }
            self.send_json(client_socket, handshake)

            # Wait for handshake response
            response = self.receive_json(client_socket, timeout=5.0)
            if response and response.get('type') == 'handshake_response':
                device_name = response.get('device_name', 'Unknown Device')

                # If we already have a connected client, disconnect it
                if self.client_socket:
                    self.disconnect_client()

                # Accept the new client
                with self.lock:
                    self.client_socket = client_socket
                    self.client_address = client_address
                    self.device_name = device_name
                    self.connected = True

                self.log_message.emit(f"Connected to {device_name} at {client_address}")
                self.status_changed.emit(True, device_name)

                # Start client handling thread
                client_handler_thread = threading.Thread(
                    target=self.handle_client_data,
                    args=(client_socket,),
                    daemon=True
                )
                client_handler_thread.start()

            else:
                client_socket.close()
                self.log_message.emit(f"Invalid handshake from {client_address}")

        except Exception as e:
            self.log_message.emit(f"Error handling client connection: {e}")
            try:
                client_socket.close()
            except:
                pass

    def handle_client_data(self, client_socket: socket.socket):
        """Handle data from connected client"""
        while self.server_running and self.connected:
            try:
                data = client_socket.recv(1024)
                if not data:
                    break

                # Parse client messages (control commands, status updates, etc.)
                self.process_client_message(data)

            except socket.timeout:
                continue
            except Exception as e:
                self.log_message.emit(f"Error receiving client data: {e}")
                break

        self.disconnect_client()

    def process_client_message(self, data: bytes):
        """Process messages from the Android client"""
        try:
            # Try to parse as JSON first
            message = json.loads(data.decode('utf-8'))

            if message.get('type') == 'status':
                self.log_message.emit(f"Client status: {message.get('message', '')}")
            elif message.get('type') == 'control_return':
                # Client is returning control to PC
                self.log_message.emit("Control returned to PC by client")

        except json.JSONDecodeError:
            # Handle binary messages
            self.log_message.emit(f"Received binary data: {len(data)} bytes")
        except Exception as e:
            self.log_message.emit(f"Error processing client message: {e}")

    def broadcast_discovery(self):
        """Broadcast discovery messages periodically"""
        while self.server_running:
            try:
                # Send broadcast message
                message = self.DISCOVERY_MESSAGE
                self.discovery_socket.sendto(message, ('<broadcast>', self.discovery_port))
                self.log_message.emit("Discovery broadcast sent")

            except Exception as e:
                self.log_message.emit(f"Error broadcasting discovery: {e}")

            # Wait before next broadcast
            for _ in range(10):  # Check every 0.5 seconds for 5 seconds total
                if not self.server_running:
                    break
                time.sleep(0.5)

    def listen_for_discovery_responses(self):
        """Listen for discovery responses from Android devices"""
        while self.server_running:
            try:
                data, addr = self.discovery_socket.recvfrom(1024)
                if data.startswith(self.DISCOVERY_RESPONSE):
                    # Parse device information
                    try:
                        device_info = json.loads(data[len(self.DISCOVERY_RESPONSE):].decode('utf-8'))
                        device_info['ip'] = addr[0]
                        self.device_discovered.emit(device_info)
                        self.log_message.emit(f"Device discovered: {device_info.get('name', 'Unknown')} at {addr[0]}")
                    except json.JSONDecodeError:
                        # Simple response without device info
                        device_info = {
                            'name': f'Android Device ({addr[0]})',
                            'ip': addr[0],
                            'port': self.server_port
                        }
                        self.device_discovered.emit(device_info)
                        self.log_message.emit(f"Device discovered at {addr[0]}")

            except socket.timeout:
                continue
            except Exception as e:
                if self.server_running:
                    self.log_message.emit(f"Error listening for discovery: {e}")
                break

    def discover_devices(self):
        """Discover Android devices on the network"""
        if not self.server_running:
            self.log_message.emit("Server must be started first")
            return

        self.log_message.emit("Starting device discovery...")

        # Start listening for responses in a separate thread
        discovery_listener = threading.Thread(target=self.listen_for_discovery_responses, daemon=True)
        discovery_listener.start()

        # Send immediate broadcast
        try:
            message = self.DISCOVERY_MESSAGE
            self.discovery_socket.sendto(message, ('<broadcast>', self.discovery_port))
        except Exception as e:
            self.log_message.emit(f"Error sending discovery broadcast: {e}")

    def send_packet(self, packet: bytes) -> bool:
        """Send a packet to the connected client"""
        with self.lock:
            if not self.connected or not self.client_socket:
                return False

            try:
                self.client_socket.send(packet)
                return True
            except Exception as e:
                self.log_message.emit(f"Error sending packet: {e}")
                self.disconnect_client()
                return False

    def send_json(self, sock: socket.socket, data: dict) -> bool:
        """Send JSON data over socket"""
        try:
            json_data = json.dumps(data) + '\n'
            sock.send(json_data.encode('utf-8'))
            return True
        except Exception as e:
            self.log_message.emit(f"Error sending JSON: {e}")
            return False

    def receive_json(self, sock: socket.socket, timeout: float = 5.0) -> Optional[dict]:
        """Receive JSON data from socket"""
        try:
            sock.settimeout(timeout)
            data = sock.recv(1024).decode('utf-8').strip()
            if data:
                return json.loads(data)
        except socket.timeout:
            self.log_message.emit("Timeout waiting for response")
        except json.JSONDecodeError as e:
            self.log_message.emit(f"Invalid JSON received: {e}")
        except Exception as e:
            self.log_message.emit(f"Error receiving JSON: {e}")
        return None

    def disconnect_client(self):
        """Disconnect the current client"""
        with self.lock:
            if self.client_socket:
                try:
                    self.client_socket.close()
                except:
                    pass
                self.client_socket = None

            self.connected = False
            old_device_name = self.device_name
            self.device_name = ""
            self.client_address = None

        if old_device_name:
            self.log_message.emit(f"Disconnected from {old_device_name}")
            self.status_changed.emit(False, "")

    def heartbeat_monitor(self):
        """Monitor connection health with heartbeat messages"""
        while self.server_running:
            time.sleep(self.HEARTBEAT_INTERVAL)

            if self.connected:
                try:
                    # Send heartbeat
                    heartbeat = {
                        'type': 'heartbeat',
                        'timestamp': time.time()
                    }
                    if not self.send_json(self.client_socket, heartbeat):
                        self.disconnect_client()

                except Exception as e:
                    self.log_message.emit(f"Heartbeat error: {e}")
                    self.disconnect_client()

    def is_server_running(self) -> bool:
        """Check if the server is running"""
        with self.lock:
            return self.server_running

    def is_connected(self) -> bool:
        """Check if a client is connected"""
        with self.lock:
            return self.connected

    def get_status(self) -> dict:
        """Get current connection status"""
        with self.lock:
            return {
                'server_running': self.server_running,
                'connected': self.connected,
                'device_name': self.device_name,
                'device_address': self.client_address,
                'server_port': self.server_port,
                'discovery_port': self.discovery_port
            }