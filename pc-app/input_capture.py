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
        self.mouse_controller = mouse.Controller()
        self.active = False
        self.lock = threading.Lock()

        # Screen dimensions for edge detection
        self.screen_width = 1920  # Default
        self.screen_height = 1080  # Default
        self.update_screen_dimensions()
        
        self.center_x = self.screen_width // 2
        self.center_y = self.screen_height // 2

        # Edge detection threshold (pixels)
        self.edge_threshold = 5  # Increased for easier entry

        # Control state
        self.control_active = False  # Whether we're controlling Android device
        self.last_x = 0
        self.last_y = 0
        self.suppress_next_move = False

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
                self.center_x = self.screen_width // 2
                self.center_y = self.screen_height // 2
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

            # Start mouse listener (always passive to allow trapping)
            self.mouse_listener = mouse.Listener(
                on_move=self.on_mouse_move,
                on_click=self.on_mouse_click,
                on_scroll=self.on_mouse_scroll
            )

            # Start keyboard listener (initially passive)
            self.keyboard_listener = keyboard.Listener(
                on_press=self.on_key_press,
                on_release=self.on_key_release,
                suppress=False
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
            self.control_active = False # Reset control state
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

    def switch_to_android_mode(self):
        """Switch input capture to Android mode (blocking keyboard)"""
        self.control_active = True
        self.log_message.emit("Switching to Android Control Mode")
        
        # Restart keyboard listener as blocking
        if self.keyboard_listener:
            self.keyboard_listener.stop()
        
        self.keyboard_listener = keyboard.Listener(
            on_press=self.on_key_press,
            on_release=self.on_key_release,
            suppress=True # Block keyboard input to PC
        )
        self.keyboard_listener.start()
        
        # Trap mouse to center immediately
        self.suppress_next_move = True
        self.mouse_controller.position = (self.center_x, self.center_y)

    def switch_to_pc_mode(self):
        """Switch input capture back to PC mode (passive)"""
        self.control_active = False
        self.log_message.emit("Returning to PC Control Mode")
        
        # Restart keyboard listener as passive
        if self.keyboard_listener:
            self.keyboard_listener.stop()
            
        self.keyboard_listener = keyboard.Listener(
            on_press=self.on_key_press,
            on_release=self.on_key_release,
            suppress=False # Allow keyboard input to PC
        )
        self.keyboard_listener.start()

    def on_mouse_move(self, x, y):
        """Handle mouse movement"""
        if not self.active:
            return

        # Handle suppression (for reset)
        if self.suppress_next_move:
            self.suppress_next_move = False
            return

        current_time = time.time()

        # If not controlling Android, check for edge entry
        if not self.control_active:
            edge = self.check_screen_edge_switch(x, y)
            if edge:
                # Switch control to Android device
                self.switch_to_android_mode()

                # Send control switch event
                event = {
                    'type': 'control_switch',
                    'edge': edge,
                    'timestamp': current_time * 1000
                }
                self.input_event.emit(event)
                return
            
            # Normal PC usage
            self.last_x = x
            self.last_y = y
            return

        # If controlling Android, calculate relative movement from center trap
        dx = x - self.center_x
        dy = y - self.center_y
        
        # Only send if there is movement
        if dx == 0 and dy == 0:
            return

        # Rate limiting
        if current_time - self.last_mouse_time < self.mouse_interval:
            return

        self.last_mouse_time = current_time

        event = {
            'type': 'mouse_move_relative',
            'dx': int(dx),
            'dy': int(dy),
            'timestamp': current_time * 1000
        }
        self.input_event.emit(event)
        
        # Re-trap mouse to center
        self.suppress_next_move = True
        self.mouse_controller.position = (self.center_x, self.center_y)

    def on_mouse_click(self, x, y, button, pressed):
        """Handle mouse clicks"""
        if not self.active or not self.control_active:
            return

        # Convert button to string
        button_str = str(button).replace('Button.', '')

        event = {
            'type': 'mouse_click',
            'x': 0, # Ignored for relative
            'y': 0, # Ignored for relative
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
            'x': 0,
            'y': 0,
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

        event = {
            'type': 'key_press',
            'key': key_str,
            'key_code': key_code,
            'pressed': True,
            'timestamp': time.time() * 1000
        }

        # Always send key events
        if self.control_active:
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
        if self.control_active:
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

    def return_control_to_pc(self):
        """Force control back to PC (called by external triggers)"""
        if self.control_active:
            self.switch_to_pc_mode()

            event = {
                'type': 'control_switch',
                'edge': 'return_to_pc',
                'timestamp': time.time() * 1000
            }
            self.input_event.emit(event)