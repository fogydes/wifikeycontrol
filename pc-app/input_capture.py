#!/usr/bin/env python3

import time
import threading
from pynput import mouse, keyboard
from PyQt5.QtCore import QObject, pyqtSignal
import screeninfo

class InputCapture(QObject):
    # Signals
    input_event = pyqtSignal(dict)
    log_message = pyqtSignal(str)

    def __init__(self):
        super().__init__()
        self.mouse_listener = None
        self.keyboard_listener = None
        self.active = False
        self.lock = threading.Lock()

        # Screen dimensions for edge detection
        self.screen_width = 1920  # Default
        self.screen_height = 1080  # Default
        self.update_screen_dimensions()

        # Edge detection threshold (pixels)
        self.edge_threshold = 10

        # Control state
        self.control_active = False  # Whether we're controlling Android device
        self.last_x = 0
        self.last_y = 0

        # Transmission rate limiting (60Hz target)
        self.last_mouse_time = 0
        self.mouse_interval = 1.0 / 60.0  # ~16ms for 60Hz

    def update_screen_dimensions(self):
        """Update screen dimensions based on actual monitors"""
        try:
            monitors = screeninfo.get_monitors()
            if monitors:
                # Use primary monitor or total combined width
                primary_monitor = monitors[0]
                self.screen_width = primary_monitor.width
                self.screen_height = primary_monitor.height
                self.log_message.emit(f"Screen dimensions: {self.screen_width}x{self.screen_height}")
        except Exception as e:
            self.log_message.emit(f"Error getting screen dimensions: {e}")
            # Use default values

    def start_capture(self):
        """Start capturing keyboard and mouse input"""
        with self.lock:
            if self.active:
                return

            self.active = True
            self.log_message.emit("Starting input capture...")

            # Start mouse listener
            self.mouse_listener = mouse.Listener(
                on_move=self.on_mouse_move,
                on_click=self.on_mouse_click,
                on_scroll=self.on_mouse_scroll
            )

            # Start keyboard listener
            self.keyboard_listener = keyboard.Listener(
                on_press=self.on_key_press,
                on_release=self.on_key_release
            )

            self.mouse_listener.start()
            self.keyboard_listener.start()

            self.log_message.emit("Input capture started")

    def stop_capture(self):
        """Stop capturing input"""
        with self.lock:
            if not self.active:
                return

            self.active = False
            self.log_message.emit("Stopping input capture...")

            if self.mouse_listener:
                self.mouse_listener.stop()
                self.mouse_listener = None

            if self.keyboard_listener:
                self.keyboard_listener.stop()
                self.keyboard_listener = None

            self.log_message.emit("Input capture stopped")

    def is_active(self):
        """Check if input capture is active"""
        with self.lock:
            return self.active

    def check_screen_edge_switch(self, x, y):
        """Check if mouse is at screen edge and should switch control"""
        # Left edge
        if x <= self.edge_threshold:
            return "left"
        # Right edge
        elif x >= self.screen_width - self.edge_threshold:
            return "right"
        # Top edge
        elif y <= self.edge_threshold:
            return "top"
        # Bottom edge
        elif y >= self.screen_height - self.edge_threshold:
            return "bottom"

        return None

    def on_mouse_move(self, x, y):
        """Handle mouse movement"""
        if not self.active:
            return

        current_time = time.time()

        # Rate limiting
        if current_time - self.last_mouse_time < self.mouse_interval:
            return

        self.last_mouse_time = current_time
        self.last_x = x
        self.last_y = y

        # Check for screen edge switching
        edge = self.check_screen_edge_switch(x, y)
        if edge and not self.control_active:
            # Switch control to Android device
            self.control_active = True
            self.log_message.emit(f"Switching control to Android device (edge: {edge})")

            # Send control switch event
            event = {
                'type': 'control_switch',
                'edge': edge,
                'timestamp': current_time * 1000  # Convert to milliseconds
            }
            self.input_event.emit(event)
            return

        # Only send mouse events if we're controlling the Android device
        if self.control_active:
            event = {
                'type': 'mouse_move',
                'x': int(x),
                'y': int(y),
                'timestamp': current_time * 1000
            }
            self.input_event.emit(event)

    def on_mouse_click(self, x, y, button, pressed):
        """Handle mouse clicks"""
        if not self.active or not self.control_active:
            return

        # Convert button to string
        button_str = str(button).replace('Button.', '')

        event = {
            'type': 'mouse_click',
            'x': int(x),
            'y': int(y),
            'button': button_str,
            'pressed': pressed,
            'timestamp': time.time() * 1000
        }

        self.input_event.emit(event)

    def on_mouse_scroll(self, x, y, dx, dy):
        """Handle mouse scroll"""
        if not self.active or not self.control_active:
            return

        event = {
            'type': 'mouse_scroll',
            'x': int(x),
            'y': int(y),
            'dx': int(dx),
            'dy': int(dy),
            'timestamp': time.time() * 1000
        }

        self.input_event.emit(event)

    def on_key_press(self, key):
        """Handle key press"""
        if not self.active:
            return

        # Handle special keys
        try:
            if hasattr(key, 'char') and key.char is not None:
                # Regular character key
                key_str = key.char
                key_code = ord(key.char) if key.char else 0
            else:
                # Special key (enter, shift, etc.)
                key_str = str(key).replace('Key.', '')
                key_code = self.get_special_key_code(key_str)
        except AttributeError:
            key_str = str(key)
            key_code = 0

        # Check for control toggle hotkey (Ctrl + Shift + Space)
        if self.is_control_toggle_hotkey(key, pressed=True):
            self.toggle_control()
            return

        event = {
            'type': 'key_press',
            'key': key_str,
            'key_code': key_code,
            'pressed': True,
            'timestamp': time.time() * 1000
        }

        # Always send key events (they can toggle control back to PC)
        self.input_event.emit(event)

    def on_key_release(self, key):
        """Handle key release"""
        if not self.active:
            return

        # Handle special keys
        try:
            if hasattr(key, 'char') and key.char is not None:
                # Regular character key
                key_str = key.char
                key_code = ord(key.char) if key.char else 0
            else:
                # Special key
                key_str = str(key).replace('Key.', '')
                key_code = self.get_special_key_code(key_str)
        except AttributeError:
            key_str = str(key)
            key_code = 0

        event = {
            'type': 'key_release',
            'key': key_str,
            'key_code': key_code,
            'pressed': False,
            'timestamp': time.time() * 1000
        }

        # Always send key events
        self.input_event.emit(event)

    def get_special_key_code(self, key_str):
        """Get key code for special keys"""
        key_codes = {
            'shift': 16,
            'ctrl': 17,
            'alt': 18,
            'cmd': 91,
            'space': 32,
            'enter': 13,
            'backspace': 8,
            'tab': 9,
            'escape': 27,
            'delete': 46,
            'home': 36,
            'end': 35,
            'page_up': 33,
            'page_down': 34,
            'up': 38,
            'down': 40,
            'left': 37,
            'right': 39,
            'f1': 112, 'f2': 113, 'f3': 114, 'f4': 115,
            'f5': 116, 'f6': 117, 'f7': 118, 'f8': 119,
            'f9': 120, 'f10': 121, 'f11': 122, 'f12': 123
        }
        return key_codes.get(key_str.lower(), 0)

    def is_control_toggle_hotkey(self, key, pressed=False):
        """Check if the key is part of the control toggle hotkey"""
        # This is a simplified version - in practice, we'd need to track
        # the state of modifier keys
        return False  # Placeholder - would implement proper hotkey detection

    def toggle_control(self):
        """Toggle control between PC and Android"""
        if self.control_active:
            self.control_active = False
            self.log_message.emit("Switching control back to PC")

            event = {
                'type': 'control_switch',
                'edge': 'return_to_pc',
                'timestamp': time.time() * 1000
            }
            self.input_event.emit(event)
        else:
            self.control_active = True
            self.log_message.emit("Switching control to Android device")

            event = {
                'type': 'control_switch',
                'edge': 'hotkey',
                'timestamp': time.time() * 1000
            }
            self.input_event.emit(event)

    def return_control_to_pc(self):
        """Force control back to PC (called by external triggers)"""
        if self.control_active:
            self.control_active = False
            self.log_message.emit("Control returned to PC")

            event = {
                'type': 'control_switch',
                'edge': 'return_to_pc',
                'timestamp': time.time() * 1000
            }
            self.input_event.emit(event)