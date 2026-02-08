#!/usr/bin/env python3
"""
CANphon Telemetry Viewer
========================
Receives and visualizes telemetry from Android phone via USB Serial.

Features:
- Serial Terminal (raw hex display)
- Real-time MATLAB-style plots
- Frame parsing (73-byte protocol)
"""

import sys
import struct
import threading
import time
from collections import deque

# PyQt5
from PyQt5.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QPushButton, QComboBox, QLabel, QPlainTextEdit, QTabWidget, QGroupBox,
    QSplitter, QFrame
)
from PyQt5.QtCore import Qt, QTimer, pyqtSignal, QObject
from PyQt5.QtGui import QFont, QColor

# Matplotlib for plotting
import matplotlib
matplotlib.use('Qt5Agg')
from matplotlib.backends.backend_qt5agg import FigureCanvasQTAgg as FigureCanvas
from matplotlib.figure import Figure
import matplotlib.pyplot as plt

# Serial
import serial
import serial.tools.list_ports

import numpy as np


# ===================== PROTOCOL CONSTANTS =====================
HEADER_1 = 0xAA
HEADER_2 = 0x55
FRAME_SIZE = 73
BAUD_RATE = 115200


# ===================== SIGNAL BRIDGE =====================
class SignalBridge(QObject):
    """Bridge for thread-safe GUI updates"""
    new_frame = pyqtSignal(dict)
    raw_data = pyqtSignal(bytes)
    status_update = pyqtSignal(str)


# ===================== FRAME PARSER =====================
class FrameParser:
    """Parse 73-byte telemetry frames"""
    
    def __init__(self):
        self.buffer = bytearray()
        self.frame_count = 0
        self.error_count = 0
    
    def parse(self, data: bytes) -> list:
        """Add data to buffer and extract complete frames"""
        self.buffer.extend(data)
        frames = []
        
        while len(self.buffer) >= FRAME_SIZE:
            # Find header
            try:
                idx = self.buffer.index(HEADER_1)
                if idx > 0:
                    self.buffer = self.buffer[idx:]
                
                if len(self.buffer) < FRAME_SIZE:
                    break
                
                if self.buffer[1] != HEADER_2:
                    self.buffer = self.buffer[1:]
                    continue
                
                # Extract frame
                frame_data = bytes(self.buffer[:FRAME_SIZE])
                self.buffer = self.buffer[FRAME_SIZE:]
                
                # Verify checksum
                calc_checksum = 0
                for b in frame_data[:-1]:
                    calc_checksum ^= b
                
                if calc_checksum == frame_data[-1]:
                    parsed = self._parse_frame(frame_data)
                    if parsed:
                        frames.append(parsed)
                        self.frame_count += 1
                else:
                    self.error_count += 1
                    
            except ValueError:
                self.buffer.clear()
                break
        
        return frames
    
    def _parse_frame(self, data: bytes) -> dict:
        """Parse a single frame into a dictionary"""
        try:
            offset = 3  # Skip header and length
            
            # Timestamp (4 bytes, uint32)
            timestamp = struct.unpack('<I', data[offset:offset+4])[0]
            offset += 4
            
            # Orientation (6 bytes: 3x int16, scaled by 10)
            roll = struct.unpack('<h', data[offset:offset+2])[0] / 10.0
            pitch = struct.unpack('<h', data[offset+2:offset+4])[0] / 10.0
            yaw = struct.unpack('<h', data[offset+4:offset+6])[0] / 10.0
            offset += 6
            
            # Accelerometer (6 bytes: 3x int16, scaled by 100)
            accel_x = struct.unpack('<h', data[offset:offset+2])[0] / 100.0
            accel_y = struct.unpack('<h', data[offset+2:offset+4])[0] / 100.0
            accel_z = struct.unpack('<h', data[offset+4:offset+6])[0] / 100.0
            offset += 6
            
            # Pressure (4 bytes: pressure as uint16, altitude as int16)
            pressure = struct.unpack('<H', data[offset:offset+2])[0]
            baro_alt = struct.unpack('<h', data[offset+2:offset+4])[0] / 10.0
            offset += 4
            
            # GPS (18 bytes)
            lat = struct.unpack('<i', data[offset:offset+4])[0] / 1e7
            lon = struct.unpack('<i', data[offset+4:offset+8])[0] / 1e7
            gps_alt = struct.unpack('<i', data[offset+8:offset+12])[0] / 100.0
            speed = struct.unpack('<H', data[offset+12:offset+14])[0] / 10.0
            heading = struct.unpack('<H', data[offset+14:offset+16])[0] / 10.0
            sats = data[offset+16]
            fix_type = data[offset+17]
            offset += 18
            
            # Servo Commands (8 bytes: 4x int16)
            servo_cmds = struct.unpack('<4h', data[offset:offset+8])
            offset += 8
            
            # Servo Feedback (8 bytes: 4x int16)
            servo_fb = struct.unpack('<4h', data[offset:offset+8])
            offset += 8
            
            # Servo Status (1 byte)
            servo_status = data[offset]
            offset += 1
            
            # Tracking (8 bytes: 4x int16)
            track_x, track_y, track_w, track_h = struct.unpack('<4h', data[offset:offset+8])
            offset += 8
            
            # Battery (4 bytes)
            battery_pct = data[offset]
            charging = data[offset+1]
            voltage = struct.unpack('<H', data[offset+2:offset+4])[0]
            offset += 4
            
            # Temperature (2 bytes)
            temperature = struct.unpack('<h', data[offset:offset+2])[0] / 10.0
            
            return {
                'timestamp': timestamp,
                'roll': roll, 'pitch': pitch, 'yaw': yaw,
                'accel_x': accel_x, 'accel_y': accel_y, 'accel_z': accel_z,
                'pressure': pressure, 'baro_alt': baro_alt,
                'lat': lat, 'lon': lon, 'gps_alt': gps_alt,
                'speed': speed, 'heading': heading, 'sats': sats, 'fix': fix_type,
                'servo_cmds': servo_cmds, 'servo_fb': servo_fb, 'servo_status': servo_status,
                'track_x': track_x, 'track_y': track_y, 'track_w': track_w, 'track_h': track_h,
                'battery': battery_pct, 'charging': charging, 'voltage': voltage,
                'temperature': temperature
            }
        except Exception as e:
            print(f"Parse error: {e}")
            return None


# ===================== PLOT WIDGET =====================
class PlotWidget(FigureCanvas):
    """Real-time plot widget"""
    
    def __init__(self, title="Plot", labels=None, colors=None, max_points=200):
        self.fig = Figure(figsize=(6, 3), dpi=100)
        self.fig.patch.set_facecolor('#1e1e1e')
        super().__init__(self.fig)
        
        self.ax = self.fig.add_subplot(111)
        self.ax.set_facecolor('#2d2d2d')
        self.ax.set_title(title, color='white', fontsize=10)
        self.ax.tick_params(colors='white')
        for spine in self.ax.spines.values():
            spine.set_color('#555555')
        self.ax.grid(True, alpha=0.3)
        
        self.labels = labels or ['Value']
        self.colors = colors or ['#00ff88']
        self.max_points = max_points
        
        self.data = {label: deque(maxlen=max_points) for label in self.labels}
        self.lines = {}
        
        for label, color in zip(self.labels, self.colors):
            line, = self.ax.plot([], [], color=color, linewidth=1.5, label=label)
            self.lines[label] = line
        
        if len(self.labels) > 1:
            self.ax.legend(loc='upper right', fontsize=8, facecolor='#2d2d2d', 
                          labelcolor='white', framealpha=0.8)
        
        self.fig.tight_layout()
    
    def update_data(self, values: dict):
        """Update plot with new values"""
        for label, value in values.items():
            if label in self.data:
                self.data[label].append(value)
        
        for label, line in self.lines.items():
            y_data = list(self.data[label])
            x_data = list(range(len(y_data)))
            line.set_data(x_data, y_data)
        
        if any(self.data.values()):
            all_data = [v for d in self.data.values() for v in d]
            if all_data:
                self.ax.set_xlim(0, max(len(d) for d in self.data.values()))
                margin = (max(all_data) - min(all_data)) * 0.1 + 0.1
                self.ax.set_ylim(min(all_data) - margin, max(all_data) + margin)
        
        self.draw()


# ===================== MAIN WINDOW =====================
class TelemetryViewer(QMainWindow):
    """Main application window"""
    
    def __init__(self):
        super().__init__()
        self.setWindowTitle("CANphon Telemetry Viewer")
        self.setGeometry(100, 100, 1400, 900)
        self.setStyleSheet("""
            QMainWindow { background-color: #1e1e1e; }
            QLabel { color: white; }
            QPushButton { 
                background-color: #3498db; color: white; 
                border: none; padding: 8px 16px; border-radius: 4px;
                font-weight: bold;
            }
            QPushButton:hover { background-color: #2980b9; }
            QPushButton:disabled { background-color: #555555; }
            QComboBox { 
                background-color: #2d2d2d; color: white; 
                border: 1px solid #555555; padding: 5px;
            }
            QTextEdit { 
                background-color: #0d0d0d; color: #00ff00; 
                font-family: Consolas, monospace; font-size: 11px;
                border: 1px solid #333333;
            }
            QGroupBox { 
                color: white; border: 1px solid #555555; 
                border-radius: 4px; margin-top: 10px; padding-top: 10px;
            }
            QGroupBox::title { subcontrol-origin: margin; left: 10px; }
            QTabWidget::pane { border: 1px solid #555555; }
            QTabBar::tab { 
                background-color: #2d2d2d; color: white; 
                padding: 8px 16px; border: 1px solid #555555;
            }
            QTabBar::tab:selected { background-color: #3498db; }
        """)
        
        # Serial connection
        self.serial_port = None
        self.is_connected = False
        self.read_thread = None
        
        # Parser
        self.parser = FrameParser()
        
        # Signal bridge
        self.signals = SignalBridge()
        self.signals.new_frame.connect(self.on_new_frame)
        self.signals.raw_data.connect(self.on_raw_data)
        self.signals.status_update.connect(self.on_status_update)
        
        self.setup_ui()
        self.refresh_ports()
        
        # Update timer for plots
        self.update_timer = QTimer()
        self.update_timer.timeout.connect(self.update_plots)
        self.update_timer.start(50)  # 20Hz update
        
        # Pending data
        self.pending_frames = []
    
    def setup_ui(self):
        """Setup the user interface"""
        central = QWidget()
        self.setCentralWidget(central)
        layout = QVBoxLayout(central)
        layout.setSpacing(10)
        
        # ===== Connection Bar =====
        conn_layout = QHBoxLayout()
        
        conn_layout.addWidget(QLabel("COM Port:"))
        self.port_combo = QComboBox()
        self.port_combo.setMinimumWidth(150)
        conn_layout.addWidget(self.port_combo)
        
        self.refresh_btn = QPushButton("🔄 Refresh")
        self.refresh_btn.clicked.connect(self.refresh_ports)
        conn_layout.addWidget(self.refresh_btn)
        
        self.connect_btn = QPushButton("🔌 Connect")
        self.connect_btn.clicked.connect(self.toggle_connection)
        conn_layout.addWidget(self.connect_btn)
        
        conn_layout.addStretch()
        
        self.status_label = QLabel("⚪ Disconnected")
        self.status_label.setStyleSheet("color: #888888; font-weight: bold;")
        conn_layout.addWidget(self.status_label)
        
        self.fps_label = QLabel("0 fps")
        self.fps_label.setStyleSheet("color: #00ff88; font-weight: bold;")
        conn_layout.addWidget(self.fps_label)
        
        layout.addLayout(conn_layout)
        
        # ===== Main Content =====
        splitter = QSplitter(Qt.Horizontal)
        
        # Left: Terminal
        terminal_group = QGroupBox("📟 Serial Terminal")
        terminal_layout = QVBoxLayout(terminal_group)
        
        self.terminal = QPlainTextEdit()
        self.terminal.setReadOnly(True)
        self.terminal.setMaximumBlockCount(1000)
        terminal_layout.addWidget(self.terminal)
        
        self.clear_btn = QPushButton("🗑 Clear")
        self.clear_btn.clicked.connect(self.terminal.clear)
        terminal_layout.addWidget(self.clear_btn)
        
        splitter.addWidget(terminal_group)
        
        # Right: Plots
        plots_widget = QWidget()
        plots_layout = QVBoxLayout(plots_widget)
        plots_layout.setSpacing(5)
        
        # Orientation Plot
        self.orientation_plot = PlotWidget(
            title="📐 Orientation (deg)",
            labels=['Roll', 'Pitch', 'Yaw'],
            colors=['#ff6b6b', '#4ecdc4', '#45b7d1']
        )
        plots_layout.addWidget(self.orientation_plot)
        
        # Accelerometer Plot
        self.accel_plot = PlotWidget(
            title="📊 Accelerometer (g)",
            labels=['X', 'Y', 'Z'],
            colors=['#ff9f43', '#26de81', '#a55eea']
        )
        plots_layout.addWidget(self.accel_plot)
        
        # Servo Plot
        self.servo_plot = PlotWidget(
            title="🎛 Servos (deg)",
            labels=['S1 Cmd', 'S2 Cmd', 'S3 Cmd', 'S4 Cmd'],
            colors=['#ff6b6b', '#4ecdc4', '#45b7d1', '#ffeaa7']
        )
        plots_layout.addWidget(self.servo_plot)
        
        splitter.addWidget(plots_widget)
        splitter.setSizes([400, 1000])
        
        layout.addWidget(splitter)
        
        # ===== Status Bar =====
        status_layout = QHBoxLayout()
        
        self.frame_label = QLabel("Frames: 0")
        self.frame_label.setStyleSheet("color: #888888;")
        status_layout.addWidget(self.frame_label)
        
        self.error_label = QLabel("Errors: 0")
        self.error_label.setStyleSheet("color: #888888;")
        status_layout.addWidget(self.error_label)
        
        status_layout.addStretch()
        
        self.data_label = QLabel("Roll: -- | Pitch: -- | Yaw: --")
        self.data_label.setStyleSheet("color: #00ff88; font-family: Consolas;")
        status_layout.addWidget(self.data_label)
        
        layout.addLayout(status_layout)
    
    def refresh_ports(self):
        """Refresh available COM ports"""
        self.port_combo.clear()
        ports = serial.tools.list_ports.comports()
        for port in ports:
            self.port_combo.addItem(f"{port.device} - {port.description}", port.device)
    
    def toggle_connection(self):
        """Connect or disconnect from serial port"""
        if self.is_connected:
            self.disconnect()
        else:
            self.connect()
    
    def connect(self):
        """Connect to selected COM port"""
        if self.port_combo.currentIndex() < 0:
            return
        
        port = self.port_combo.currentData()
        try:
            self.serial_port = serial.Serial(port, BAUD_RATE, timeout=0.1)
            self.is_connected = True
            self.connect_btn.setText("⛔ Disconnect")
            self.connect_btn.setStyleSheet("background-color: #e74c3c;")
            self.status_label.setText(f"🟢 Connected to {port}")
            self.status_label.setStyleSheet("color: #00ff88; font-weight: bold;")
            
            # Start read thread
            self.read_thread = threading.Thread(target=self.read_serial, daemon=True)
            self.read_thread.start()
            
        except Exception as e:
            self.status_label.setText(f"🔴 Error: {e}")
            self.status_label.setStyleSheet("color: #ff6b6b; font-weight: bold;")
    
    def disconnect(self):
        """Disconnect from serial port"""
        self.is_connected = False
        if self.serial_port:
            self.serial_port.close()
            self.serial_port = None
        
        self.connect_btn.setText("🔌 Connect")
        self.connect_btn.setStyleSheet("")
        self.status_label.setText("⚪ Disconnected")
        self.status_label.setStyleSheet("color: #888888; font-weight: bold;")
    
    def read_serial(self):
        """Read serial data in background thread"""
        last_fps_time = time.time()
        fps_count = 0
        
        while self.is_connected and self.serial_port:
            try:
                data = self.serial_port.read(256)
                if data:
                    self.signals.raw_data.emit(data)
                    frames = self.parser.parse(data)
                    for frame in frames:
                        self.signals.new_frame.emit(frame)
                        fps_count += 1
                
                # Update FPS
                now = time.time()
                if now - last_fps_time >= 1.0:
                    self.signals.status_update.emit(f"{fps_count} fps")
                    fps_count = 0
                    last_fps_time = now
                    
            except Exception as e:
                if self.is_connected:
                    print(f"Read error: {e}")
                break
    
    def on_raw_data(self, data: bytes):
        """Handle raw data for terminal display"""
        hex_str = ' '.join(f'{b:02X}' for b in data)
        self.terminal.appendPlainText(hex_str)
    
    def on_new_frame(self, frame: dict):
        """Handle parsed frame"""
        self.pending_frames.append(frame)
        
        # Update status
        self.frame_label.setText(f"Frames: {self.parser.frame_count}")
        self.error_label.setText(f"Errors: {self.parser.error_count}")
        
        # Update data display
        self.data_label.setText(
            f"Roll: {frame['roll']:.1f}° | Pitch: {frame['pitch']:.1f}° | "
            f"Yaw: {frame['yaw']:.1f}° | Battery: {frame['battery']}%"
        )
    
    def on_status_update(self, status: str):
        """Handle status updates"""
        self.fps_label.setText(status)
    
    def update_plots(self):
        """Update all plots with pending data"""
        if not self.pending_frames:
            return
        
        # Process latest frame
        frame = self.pending_frames[-1]
        self.pending_frames.clear()
        
        # Orientation
        self.orientation_plot.update_data({
            'Roll': frame['roll'],
            'Pitch': frame['pitch'],
            'Yaw': frame['yaw']
        })
        
        # Accelerometer
        self.accel_plot.update_data({
            'X': frame['accel_x'],
            'Y': frame['accel_y'],
            'Z': frame['accel_z']
        })
        
        # Servos
        self.servo_plot.update_data({
            'S1 Cmd': frame['servo_cmds'][0] / 10.0,
            'S2 Cmd': frame['servo_cmds'][1] / 10.0,
            'S3 Cmd': frame['servo_cmds'][2] / 10.0,
            'S4 Cmd': frame['servo_cmds'][3] / 10.0
        })
    
    def closeEvent(self, event):
        """Clean up on close"""
        self.disconnect()
        event.accept()


# ===================== MAIN =====================
if __name__ == '__main__':
    app = QApplication(sys.argv)
    app.setStyle('Fusion')
    
    window = TelemetryViewer()
    window.show()
    
    sys.exit(app.exec_())
