#!/usr/bin/env python3

import sys
import json
import os
from PyQt5.QtWidgets import (QApplication, QMainWindow, QWidget, QVBoxLayout,
                             QHBoxLayout, QPushButton, QLabel, QStatusBar,
                             QGroupBox, QGridLayout, QTextEdit, QSplitter,
                             QMenuBar, QAction, QMessageBox, QDialog,
                             QTabWidget, QTableWidget, QTableWidgetItem, QHeaderView)
from PyQt5.QtCore import Qt, QThread, pyqtSignal, QTimer
from PyQt5.QtGui import QFont, QIcon, QPixmap

from connection_manager import ConnectionManager
from input_capture import InputCapture
from protocol import ProtocolHandler

class ConnectionStatusThread(QThread):
    status_changed = pyqtSignal(str, bool)

    def __init__(self, connection_manager):
        super().__init__()
        self.connection_manager = connection_manager
        self.running = True

    def run(self):
        while self.running:
            status = self.connection_manager.get_status()
            self.status_changed.emit(status['device_name'], status['connected'])
            self.msleep(1000)

    def stop(self):
        self.running = False

class WiFiKeyControl(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("WiFi Key Control - PC Server")
        self.setGeometry(100, 100, 800, 600)

        # Initialize components
        self.connection_manager = ConnectionManager()
        self.protocol_handler = ProtocolHandler()
        self.input_capture = InputCapture()
        self.status_thread = None

        # Setup UI
        self.setup_ui()
        self.setup_menu()
        self.setup_status_bar()

        # Connect signals
        self.connect_signals()

        # Start status update timer
        self.status_timer = QTimer()
        self.status_timer.timeout.connect(self.update_status)
        self.status_timer.start(500)

    def setup_ui(self):
        central_widget = QWidget()
        self.setCentralWidget(central_widget)

        # Main layout
        main_layout = QVBoxLayout(central_widget)

        # Create splitter for resizable panels
        splitter = QSplitter(Qt.Horizontal)
        main_layout.addWidget(splitter)

        # Left panel - Controls
        left_panel = self.create_control_panel()
        splitter.addWidget(left_panel)

        # Right panel - Logs
        right_panel = self.create_log_panel()
        splitter.addWidget(right_panel)

        # Set splitter sizes (40% left, 60% right)
        splitter.setSizes([320, 480])

    def create_control_panel(self):
        panel = QWidget()
        layout = QVBoxLayout(panel)

        # Create Tab Widget
        self.tabs = QTabWidget()
        
        # Tab 1: Dashboard (Existing controls)
        self.dashboard_tab = QWidget()
        dashboard_layout = QVBoxLayout(self.dashboard_tab)
        
        # -- existing status group --
        status_group = QGroupBox("Connection Status")
        status_layout = QVBoxLayout(status_group)

        self.status_label = QLabel("Disconnected")
        self.status_label.setStyleSheet("color: red; font-weight: bold; font-size: 14px;")
        self.device_label = QLabel("No device connected")
        status_layout.addWidget(self.status_label)
        status_layout.addWidget(self.device_label)

        # -- existing server controls group --
        server_group = QGroupBox("Server Controls")
        server_layout = QGridLayout(server_group)

        self.start_button = QPushButton("Start Server")
        self.start_button.clicked.connect(self.start_server)
        self.start_button.setStyleSheet("QPushButton { background-color: #4CAF50; color: white; font-weight: bold; padding: 8px; }")

        self.stop_button = QPushButton("Stop Server")
        self.stop_button.clicked.connect(self.stop_server)
        self.stop_button.setEnabled(False)
        self.stop_button.setStyleSheet("QPushButton { background-color: #f44336; color: white; font-weight: bold; padding: 8px; }")

        self.discover_button = QPushButton("Discover Devices")
        self.discover_button.clicked.connect(self.discover_devices)

        server_layout.addWidget(self.start_button, 0, 0)
        server_layout.addWidget(self.stop_button, 0, 1)
        server_layout.addWidget(self.discover_button, 1, 0, 1, 2)

        # -- existing settings group --
        settings_group = QGroupBox("Settings")
        settings_layout = QGridLayout(settings_group)

        settings_layout.addWidget(QLabel("Port:"), 0, 0)
        self.port_edit = QLabel("12346")
        self.port_edit.setStyleSheet("border: 1px solid gray; padding: 4px;")
        settings_layout.addWidget(self.port_edit, 0, 1)

        settings_layout.addWidget(QLabel("Discovery Port:"), 1, 0)
        self.discovery_port_edit = QLabel("12345")
        self.discovery_port_edit.setStyleSheet("border: 1px solid gray; padding: 4px;")
        settings_layout.addWidget(self.discovery_port_edit, 1, 1)

        settings_layout.addWidget(QLabel("PC IP:"), 2, 0)
        self.ip_label = QLabel(self.get_local_ip())
        self.ip_label.setStyleSheet("border: 1px solid gray; padding: 4px;")
        settings_layout.addWidget(self.ip_label, 2, 1)

        # -- existing input control group --
        input_group = QGroupBox("Input Control")
        input_layout = QVBoxLayout(input_group)

        self.capture_label = QLabel("Input Capture: Inactive")
        self.capture_label.setStyleSheet("color: gray; font-weight: bold;")
        input_layout.addWidget(self.capture_label)

        self.toggle_capture_button = QPushButton("Start Input Capture")
        self.toggle_capture_button.clicked.connect(self.toggle_input_capture)
        self.toggle_capture_button.setEnabled(False)

        input_layout.addWidget(self.toggle_capture_button)

        dashboard_layout.addWidget(status_group)
        dashboard_layout.addWidget(server_group)
        dashboard_layout.addWidget(settings_group)
        dashboard_layout.addWidget(input_group)
        dashboard_layout.addStretch()

        # Tab 2: Discovered Devices
        self.devices_tab = QWidget()
        devices_layout = QVBoxLayout(self.devices_tab)
        
        self.device_table = QTableWidget()
        self.device_table.setColumnCount(3)
        self.device_table.setHorizontalHeaderLabels(["Device Name", "IP Address", "Status"])
        self.device_table.horizontalHeader().setSectionResizeMode(0, QHeaderView.Stretch)
        self.device_table.horizontalHeader().setSectionResizeMode(1, QHeaderView.ResizeToContents)
        self.device_table.setSelectionBehavior(QTableWidget.SelectRows)
        self.device_table.setSelectionMode(QTableWidget.SingleSelection)
        self.device_table.setEditTriggers(QTableWidget.NoEditTriggers)
        self.device_table.doubleClicked.connect(self.connect_selected_device)
        
        devices_layout.addWidget(self.device_table)
        
        self.connect_device_button = QPushButton("Connect to Selected")
        self.connect_device_button.clicked.connect(self.connect_selected_device)
        self.connect_device_button.setStyleSheet("QPushButton { background-color: #2196F3; color: white; font-weight: bold; padding: 8px; }")
        devices_layout.addWidget(self.connect_device_button)

        # Add tabs to widget
        self.tabs.addTab(self.dashboard_tab, "Dashboard")
        self.tabs.addTab(self.devices_tab, "Discovered Devices")

        layout.addWidget(self.tabs)
        return panel

    def connect_selected_device(self):
        """Initiate connection to the selected device in the table"""
        current_row = self.device_table.currentRow()
        if current_row < 0:
            QMessageBox.warning(self, "No Selection", "Please select a device to connect to.")
            return
            
        ip_item = self.device_table.item(current_row, 1)
        name_item = self.device_table.item(current_row, 0)
        
        if ip_item and name_item:
            ip = ip_item.text()
            name = name_item.text()
            
            # Send invitation
            self.log_message(f"Sending connection invitation to {name} ({ip})...")
            self.connection_manager.send_invite(ip)
            QMessageBox.information(self, "Invitation Sent", f"Invitation sent to {name}.\nPlease accept the request on your Android device.")

    def create_log_panel(self):
        panel = QWidget()
        layout = QVBoxLayout(panel)

        # Log header
        log_header = QLabel("System Logs")
        log_header.setFont(QFont("Arial", 12, QFont.Bold))
        layout.addWidget(log_header)

        # Log text area
        self.log_text = QTextEdit()
        self.log_text.setReadOnly(True)
        self.log_text.setFont(QFont("Consolas", 9))
        layout.addWidget(self.log_text)

        # Log controls
        log_controls = QHBoxLayout()

        self.clear_log_button = QPushButton("Clear Logs")
        self.clear_log_button.clicked.connect(self.clear_logs)

        log_controls.addWidget(self.clear_log_button)
        log_controls.addStretch()

        layout.addLayout(log_controls)

        return panel

    def setup_menu(self):
        menubar = self.menuBar()

        # File menu
        file_menu = menubar.addMenu('File')

        exit_action = QAction('Exit', self)
        exit_action.setShortcut('Ctrl+Q')
        exit_action.triggered.connect(self.close)
        file_menu.addAction(exit_action)

        # Tools menu
        tools_menu = menubar.addMenu('Tools')

        settings_action = QAction('Settings', self)
        settings_action.triggered.connect(self.show_settings)
        tools_menu.addAction(settings_action)

        # Help menu
        help_menu = menubar.addMenu('Help')

        about_action = QAction('About', self)
        about_action.triggered.connect(self.show_about)
        help_menu.addAction(about_action)

    def setup_status_bar(self):
        self.status_bar = QStatusBar()
        self.setStatusBar(self.status_bar)
        self.status_bar.showMessage("Ready")

    def connect_signals(self):
        self.connection_manager.status_changed.connect(self.on_connection_status_changed)
        self.connection_manager.device_discovered.connect(self.on_device_discovered)
        self.connection_manager.log_message.connect(self.log_message)
        self.connection_manager.control_returned.connect(self.input_capture.return_control_to_pc)

        self.input_capture.input_event.connect(self.on_input_event)
        self.input_capture.log_message.connect(self.log_message)

    def get_local_ip(self) -> str:
        """Return the local IP address used for outbound connections, or '127.0.0.1' as fallback."""
        import socket
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            # doesn't have to be reachable
            s.connect(('8.8.8.8', 80))
            ip = s.getsockname()[0]
            s.close()
            return ip
        except Exception:
            return '127.0.0.1'

    def start_server(self):
        try:
            port = int(self.port_edit.text())
            discovery_port = int(self.discovery_port_edit.text())

            # Update screen dimensions before starting
            self.connection_manager.set_screen_dimensions(
                self.input_capture.screen_width,
                self.input_capture.screen_height
            )

            if self.connection_manager.start_server(port, discovery_port):
                self.start_button.setEnabled(False)
                self.stop_button.setEnabled(True)
                self.discover_button.setEnabled(True)
                self.toggle_capture_button.setEnabled(True)
                self.log_message("Server started successfully")
                self.status_bar.showMessage("Server running")
            else:
                self.log_message("Failed to start server")
                QMessageBox.warning(self, "Error", "Failed to start server")
        except ValueError:
            QMessageBox.warning(self, "Error", "Invalid port number")

    def stop_server(self):
        self.connection_manager.stop_server()
        self.start_button.setEnabled(True)
        self.stop_button.setEnabled(False)
        self.discover_button.setEnabled(False)
        self.toggle_capture_button.setEnabled(False)

        if self.input_capture.is_active():
            self.toggle_input_capture()

        self.log_message("Server stopped")
        self.status_bar.showMessage("Server stopped")

    def discover_devices(self):
        self.log_message("Discovering devices...")
        self.connection_manager.discover_devices()

    def toggle_input_capture(self):
        if self.input_capture.is_active():
            self.input_capture.stop_capture()
            self.toggle_capture_button.setText("Start Input Capture")
            self.capture_label.setText("Input Capture: Inactive")
            self.capture_label.setStyleSheet("color: gray; font-weight: bold;")
            self.log_message("Input capture stopped")
        else:
            if self.connection_manager.is_connected():
                self.input_capture.start_capture()
                self.toggle_capture_button.setText("Stop Input Capture")
                self.capture_label.setText("Input Capture: Active")
                self.capture_label.setStyleSheet("color: green; font-weight: bold;")
                self.log_message("Input capture started")
            else:
                QMessageBox.information(self, "Info", "Please connect a device first")

    def on_connection_status_changed(self, connected, device_name):
        if connected:
            self.status_label.setText("Connected")
            self.status_label.setStyleSheet("color: green; font-weight: bold; font-size: 14px;")
            self.device_label.setText(f"Device: {device_name}")
            self.status_bar.showMessage(f"Connected to {device_name}")
        else:
            self.status_label.setText("Disconnected")
            self.status_label.setStyleSheet("color: red; font-weight: bold; font-size: 14px;")
            self.device_label.setText("No device connected")
            self.status_bar.showMessage("Disconnected")

            # Stop input capture if disconnected
            if self.input_capture.is_active():
                self.toggle_input_capture()

    def on_device_discovered(self, device_info):
        # Be defensive: discovery responses may omit `name` or `ip` fields.
        name = device_info.get('name') or device_info.get('device_name') or device_info.get('hostname')
        ip = device_info.get('ip') or device_info.get('address') or 'unknown'
        display_name = name if name else f"Android Device ({ip})"
        
        # Update Table
        row_count = self.device_table.rowCount()
        found_row = -1
        for i in range(row_count):
            item = self.device_table.item(i, 1) # Check IP column
            if item and item.text() == ip:
                found_row = i
                break
        
        if found_row == -1:
            # Add new row
            row = row_count
            self.device_table.insertRow(row)
            self.device_table.setItem(row, 0, QTableWidgetItem(display_name))
            self.device_table.setItem(row, 1, QTableWidgetItem(ip))
            self.device_table.setItem(row, 2, QTableWidgetItem("Available"))
            self.log_message(f"New device discovered: {display_name} at {ip}")
        else:
            # Update existing row (optional, e.g. name change)
            self.device_table.setItem(found_row, 0, QTableWidgetItem(display_name))
            # Flash or update status if needed
            pass

    def on_input_event(self, event_data):
        if self.connection_manager.is_connected():
            packet = self.protocol_handler.create_packet(event_data)
            self.connection_manager.send_packet(packet)

    def update_status(self):
        # Update connection status
        if self.connection_manager.is_connected():
            status = self.connection_manager.get_status()
            self.on_connection_status_changed(status['connected'], status['device_name'])

        # Update displayed IP in case network changed
        try:
            self.ip_label.setText(self.get_local_ip())
        except Exception:
            pass


    def log_message(self, message):
        from datetime import datetime
        timestamp = datetime.now().strftime("%H:%M:%S")
        self.log_text.append(f"[{timestamp}] {message}")
        # Auto-scroll to bottom
        scrollbar = self.log_text.verticalScrollBar()
        scrollbar.setValue(scrollbar.maximum())

    def clear_logs(self):
        self.log_text.clear()

    def show_settings(self):
        QMessageBox.information(self, "Settings", "Settings dialog will be implemented in Phase 3")

    def show_about(self):
        QMessageBox.about(self, "About",
                         "WiFi Key Control v1.0\n\n"
                         "Control your Android phone with your PC keyboard and mouse\n\n"
                         "Features:\n"
                         "• Wireless (Wi-Fi) connection\n"
                         "• USB/OTG connection (Phase 2)\n"
                         "• Screen edge switching\n"
                         "• Low latency input transmission")

    def closeEvent(self, event):
        # Cleanup when closing
        if self.input_capture.is_active():
            self.input_capture.stop_capture()

        if self.connection_manager.is_server_running():
            self.connection_manager.stop_server()

        event.accept()

def main():
    app = QApplication(sys.argv)
    app.setApplicationName("WiFi Key Control")
    app.setOrganizationName("WiFiKeyControl")

    window = WiFiKeyControl()
    window.show()

    sys.exit(app.exec_())

if __name__ == "__main__":
    main()

    