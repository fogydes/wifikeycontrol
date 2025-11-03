#!/usr/bin/env python3

import struct
import time
import zlib
from typing import Dict, Optional, List
import json

class ProtocolHandler:
    """Binary packet protocol for PC-Android communication"""

    # Protocol constants from planning.md
    HEADER_MAGIC = 0xAABB
    PACKET_TYPES = {
        'MOUSE_MOVEMENT': 0x01,
        'MOUSE_CLICK': 0x02,
        'KEYBOARD_KEY': 0x03,
        'SCROLL_EVENT': 0x04,
        'GESTURE_START': 0x05,
        'GESTURE_END': 0x06,
        'CONTROL_SWITCH': 0x07,
        'HEARTBEAT': 0x08
    }

    # Mouse buttons
    MOUSE_BUTTONS = {
        'left': 0x01,
        'right': 0x02,
        'middle': 0x03,
        'x1': 0x04,
        'x2': 0x05
    }

    # Control switch edges
    CONTROL_EDGES = {
        'left': 0x01,
        'right': 0x02,
        'top': 0x03,
        'bottom': 0x04,
        'hotkey': 0x05,
        'return_to_pc': 0x06
    }

    def __init__(self):
        self.packet_sequence = 0
        self.compression_enabled = True
        self.max_packet_size = 1024

    def create_packet(self, event_data: Dict) -> bytes:
        """Create a binary packet from event data"""
        packet_type = event_data.get('type', '')
        timestamp = int(event_data.get('timestamp', time.time() * 1000))

        # Get packet type byte
        type_byte = self.PACKET_TYPES.get(packet_type, 0x00)
        if type_byte == 0x00:
            return self.create_json_packet(event_data)  # Fallback to JSON

        # Create packet based on type
        if packet_type == 'mouse_move':
            packet = self.create_mouse_movement_packet(event_data, timestamp)
        elif packet_type == 'mouse_click':
            packet = self.create_mouse_click_packet(event_data, timestamp)
        elif packet_type in ['key_press', 'key_release']:
            packet = self.create_keyboard_packet(event_data, timestamp)
        elif packet_type == 'mouse_scroll':
            packet = self.create_scroll_packet(event_data, timestamp)
        elif packet_type == 'control_switch':
            packet = self.create_control_switch_packet(event_data, timestamp)
        else:
            return self.create_json_packet(event_data)  # Fallback to JSON

        return packet

    def create_mouse_movement_packet(self, event_data: Dict, timestamp: int) -> bytes:
        """Create a mouse movement packet"""
        x = int(event_data.get('x', 0))
        y = int(event_data.get('y', 0))

        # Packet structure: [HEADER:2][TYPE:1][SEQ:2][X:4][Y:4][TIMESTAMP:8][CHECKSUM:2]
        payload = struct.pack(
            '<HHIIQ',
            self.packet_sequence,  # Sequence number
            x,  # X coordinate
            y,  # Y coordinate
            timestamp  # Timestamp
        )

        return self.build_packet(0x01, payload)

    def create_mouse_click_packet(self, event_data: Dict, timestamp: int) -> bytes:
        """Create a mouse click packet"""
        x = int(event_data.get('x', 0))
        y = int(event_data.get('y', 0))
        button = event_data.get('button', 'left')
        pressed = event_data.get('pressed', False)

        button_byte = self.MOUSE_BUTTONS.get(button, 0x01)
        state_byte = 0x01 if pressed else 0x00

        # Packet structure: [HEADER:2][TYPE:1][SEQ:2][X:4][Y:4][BUTTON:1][STATE:1][TIMESTAMP:8][CHECKSUM:2]
        payload = struct.pack(
            '<HIIIBBQ',
            self.packet_sequence,  # Sequence number
            x,  # X coordinate
            y,  # Y coordinate
            button_byte,  # Button
            state_byte,  # Pressed state
            timestamp  # Timestamp
        )

        return self.build_packet(0x02, payload)

    def create_keyboard_packet(self, event_data: Dict, timestamp: int) -> bytes:
        """Create a keyboard packet"""
        key_code = int(event_data.get('key_code', 0))
        pressed = event_data.get('pressed', False)
        key_str = event_data.get('key', '')

        # Convert key string to bytes (max 16 bytes)
        key_bytes = key_str.encode('utf-8')[:16]
        key_bytes_padded = key_bytes.ljust(16, b'\x00')

        state_byte = 0x01 if pressed else 0x00
        modifiers_byte = 0x00  # TODO: Implement modifier detection

        # Packet structure: [HEADER:2][TYPE:1][SEQ:2][KEYCODE:4][STATE:1][MODIFIERS:1][KEY:16][TIMESTAMP:8][CHECKSUM:2]
        payload = struct.pack(
            '<HIBBB16sQ',
            self.packet_sequence,  # Sequence number
            key_code,  # Key code
            state_byte,  # Pressed state
            modifiers_byte,  # Modifiers
            key_bytes_padded,  # Key string
            timestamp  # Timestamp
        )

        return self.build_packet(0x03, payload)

    def create_scroll_packet(self, event_data: Dict, timestamp: int) -> bytes:
        """Create a scroll event packet"""
        x = int(event_data.get('x', 0))
        y = int(event_data.get('y', 0))
        dx = int(event_data.get('dx', 0))
        dy = int(event_data.get('dy', 0))

        # Packet structure: [HEADER:2][TYPE:1][SEQ:2][X:4][Y:4][DX:2][DY:2][TIMESTAMP:8][CHECKSUM:2]
        payload = struct.pack(
            '<HIIHHHQ',
            self.packet_sequence,  # Sequence number
            x,  # X coordinate
            y,  # Y coordinate
            dx,  # Scroll X delta
            dy,  # Scroll Y delta
            timestamp  # Timestamp
        )

        return self.build_packet(0x04, payload)

    def create_control_switch_packet(self, event_data: Dict, timestamp: int) -> bytes:
        """Create a control switch packet"""
        edge = event_data.get('edge', 'left')
        edge_byte = self.CONTROL_EDGES.get(edge, 0x01)

        # Packet structure: [HEADER:2][TYPE:1][SEQ:2][EDGE:1][RESERVED:3][TIMESTAMP:8][CHECKSUM:2]
        payload = struct.pack(
            '<HBBBQ',
            self.packet_sequence,  # Sequence number
            edge_byte,  # Edge
            0x00,  # Reserved
            0x00,  # Reserved
            0x00,  # Reserved
            timestamp  # Timestamp
        )

        return self.build_packet(0x07, payload)

    def create_heartbeat_packet(self) -> bytes:
        """Create a heartbeat packet"""
        timestamp = int(time.time() * 1000)

        # Packet structure: [HEADER:2][TYPE:1][SEQ:2][TIMESTAMP:8][CHECKSUM:2]
        payload = struct.pack(
            '<HQ',
            self.packet_sequence,  # Sequence number
            timestamp  # Timestamp
        )

        return self.build_packet(0x08, payload)

    def create_json_packet(self, event_data: Dict) -> bytes:
        """Create a JSON packet as fallback"""
        json_data = json.dumps(event_data).encode('utf-8')

        # Packet structure: [HEADER:2][TYPE:1][SEQ:2][JSON_LEN:2][JSON_DATA:variable][CHECKSUM:2]
        json_len = len(json_data)
        payload = struct.pack('<HH', self.packet_sequence, json_len) + json_data

        return self.build_packet(0xFF, payload)  # 0xFF = JSON packet

    def build_packet(self, packet_type: int, payload: bytes) -> bytes:
        """Build a complete packet with header and checksum"""
        # Compress payload if enabled and large enough
        if self.compression_enabled and len(payload) > 64:
            try:
                compressed_payload = zlib.compress(payload, level=1)
                if len(compressed_payload) < len(payload):
                    # Add compression flag to highest bit of type
                    packet_type |= 0x80
                    payload = compressed_payload
            except:
                pass  # Fallback to uncompressed

        # Build packet
        packet = struct.pack('<HB', self.HEADER_MAGIC, packet_type) + payload

        # Calculate and add checksum
        checksum = self.calculate_checksum(packet)
        packet += struct.pack('<H', checksum)

        # Increment sequence number
        self.packet_sequence = (self.packet_sequence + 1) % 65536

        return packet

    def calculate_checksum(self, data: bytes) -> int:
        """Calculate CRC16 checksum for data integrity"""
        crc = 0xFFFF
        for byte in data:
            crc ^= byte
            for _ in range(8):
                if crc & 1:
                    crc = (crc >> 1) ^ 0xA001
                else:
                    crc >>= 1
        return crc

    def verify_checksum(self, packet: bytes) -> bool:
        """Verify packet checksum"""
        if len(packet) < 2:
            return False

        received_checksum = struct.unpack('<H', packet[-2:])[0]
        calculated_checksum = self.calculate_checksum(packet[:-2])
        return received_checksum == calculated_checksum

    def parse_packet(self, packet: bytes) -> Optional[Dict]:
        """Parse incoming packet and return event data"""
        if len(packet) < 5:  # Minimum packet size: HEADER(2) + TYPE(1) + CHECKSUM(2)
            return None

        # Verify header magic bytes
        header = struct.unpack('<H', packet[:2])[0]
        if header != self.HEADER_MAGIC:
            return None

        # Verify checksum
        if not self.verify_checksum(packet):
            return None

        # Extract packet type and check for compression flag
        type_byte = packet[2]
        compressed = bool(type_byte & 0x80)
        packet_type = type_byte & 0x7F

        # Extract payload (everything after header+type, before checksum)
        payload = packet[3:-2]

        # Decompress if needed
        if compressed:
            try:
                payload = zlib.decompress(payload)
            except:
                return None

        # Parse based on packet type
        try:
            if packet_type == 0x01:  # Mouse movement
                return self.parse_mouse_movement_packet(payload)
            elif packet_type == 0x02:  # Mouse click
                return self.parse_mouse_click_packet(payload)
            elif packet_type == 0x03:  # Keyboard
                return self.parse_keyboard_packet(payload)
            elif packet_type == 0x04:  # Scroll
                return self.parse_scroll_packet(payload)
            elif packet_type == 0x07:  # Control switch
                return self.parse_control_switch_packet(payload)
            elif packet_type == 0x08:  # Heartbeat
                return self.parse_heartbeat_packet(payload)
            elif packet_type == 0xFF:  # JSON fallback
                return self.parse_json_packet(payload)
            else:
                return None
        except:
            return None

    def parse_mouse_movement_packet(self, payload: bytes) -> Dict:
        """Parse mouse movement packet"""
        seq, x, y, timestamp = struct.unpack('<HIIQ', payload)
        return {
            'type': 'mouse_move',
            'sequence': seq,
            'x': x,
            'y': y,
            'timestamp': timestamp
        }

    def parse_mouse_click_packet(self, payload: bytes) -> Dict:
        """Parse mouse click packet"""
        seq, x, y, button, state, timestamp = struct.unpack('<HIIIBBQ', payload)
        button_name = 'unknown'
        for name, code in self.MOUSE_BUTTONS.items():
            if code == button:
                button_name = name
                break

        return {
            'type': 'mouse_click',
            'sequence': seq,
            'x': x,
            'y': y,
            'button': button_name,
            'pressed': bool(state),
            'timestamp': timestamp
        }

    def parse_keyboard_packet(self, payload: bytes) -> Dict:
        """Parse keyboard packet"""
        seq, key_code, state, modifiers, key_bytes, timestamp = struct.unpack('<HIBBB16sQ', payload)
        key_str = key_bytes.rstrip(b'\x00').decode('utf-8', errors='ignore')

        event_type = 'key_press' if state == 0x01 else 'key_release'

        return {
            'type': event_type,
            'sequence': seq,
            'key_code': key_code,
            'key': key_str,
            'pressed': bool(state),
            'modifiers': modifiers,
            'timestamp': timestamp
        }

    def parse_scroll_packet(self, payload: bytes) -> Dict:
        """Parse scroll packet"""
        seq, x, y, dx, dy, timestamp = struct.unpack('<HIIHHHQ', payload)
        return {
            'type': 'mouse_scroll',
            'sequence': seq,
            'x': x,
            'y': y,
            'dx': dx,
            'dy': dy,
            'timestamp': timestamp
        }

    def parse_control_switch_packet(self, payload: bytes) -> Dict:
        """Parse control switch packet"""
        seq, edge, reserved1, reserved2, reserved3, timestamp = struct.unpack('<HBBBQ', payload)
        edge_name = 'unknown'
        for name, code in self.CONTROL_EDGES.items():
            if code == edge:
                edge_name = name
                break

        return {
            'type': 'control_switch',
            'sequence': seq,
            'edge': edge_name,
            'timestamp': timestamp
        }

    def parse_heartbeat_packet(self, payload: bytes) -> Dict:
        """Parse heartbeat packet"""
        seq, timestamp = struct.unpack('<HQ', payload)
        return {
            'type': 'heartbeat',
            'sequence': seq,
            'timestamp': timestamp
        }

    def parse_json_packet(self, payload: bytes) -> Dict:
        """Parse JSON fallback packet"""
        seq, json_len = struct.unpack('<HH', payload[:4])
        json_data = payload[4:4+json_len].decode('utf-8')
        return json.loads(json_data)

    def batch_events(self, events: List[Dict], max_size: int = 1024) -> List[bytes]:
        """Batch multiple events into packets"""
        packets = []
        current_batch = []
        current_size = 0

        for event in events:
            packet = self.create_packet(event)
            if current_size + len(packet) > max_size and current_batch:
                # Send current batch
                packets.append(self.create_batch_packet(current_batch))
                current_batch = [event]
                current_size = len(packet)
            else:
                current_batch.append(event)
                current_size += len(packet)

        # Send remaining batch
        if current_batch:
            packets.append(self.create_batch_packet(current_batch))

        return packets

    def create_batch_packet(self, events: List[Dict]) -> bytes:
        """Create a batch packet containing multiple events"""
        batch_data = json.dumps(events).encode('utf-8')

        # Use JSON packet type for batches
        payload = struct.pack('<HH', self.packet_sequence, len(batch_data)) + batch_data
        packet = struct.pack('<HB', self.HEADER_MAGIC, 0xFE) + payload  # 0xFE = Batch packet

        # Add checksum
        checksum = self.calculate_checksum(packet)
        packet += struct.pack('<H', checksum)

        self.packet_sequence = (self.packet_sequence + 1) % 65536
        return packet