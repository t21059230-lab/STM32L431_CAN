#!/usr/bin/env python3
"""
CANphon Telemetry Viewer v3.2
Real-time visualization dashboard for the 73-byte telemetry stream @ 60Hz

Features:
- 4 Servo graphs showing CMD (Red) vs Feedback (Blue)
- Gyroscope (Roll, Pitch, Yaw)
- GPS, Battery, Temperature tiles
- Forensic terminal sidebar
"""

import sys
import struct
import serial
import serial.tools.list_ports
from collections import deque
from datetime import datetime

from PyQt5.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QSplitter, QComboBox, QPushButton, QLabel, QTextEdit, QGridLayout,
    QGroupBox, QFrame
)
from PyQt5.QtCore import QTimer, Qt, pyqtSignal, QThread
from PyQt5.QtGui import QFont, QColor, QPalette

import matplotlib
matplotlib.use('Qt5Agg')
from matplotlib.backends.backend_qt5agg import FigureCanvasQTAgg as FigureCanvas
from matplotlib.figure import Figure
import matplotlib.pyplot as plt

# =============================================================================
# PROTOCOL CONSTANTS (Match TelemetryStreamer.kt)
# =============================================================================
FRAME_HEADER_1 = 0xAA
FRAME_HEADER_2 = 0x55
FRAME_LENGTH = 70
TOTAL_FRAME_SIZE = 73
BAUD_RATE = 115200

# Buffer size for plotting (5 minutes @ 60Hz = 18000 samples)
MAX_BUFFER_SIZE = 18000

# =============================================================================
# DARK THEME COLORS
# =============================================================================
COLORS = {
    'background': '#1a1a2e',
    'panel': '#16213e',
    'text': '#eaeaea',
    'accent': '#00ff88',
    'cmd': '#ff4444',      # Red for commands
    'feedback': '#0072bd',  # MATLAB Blue for feedback
    'grid': '#333355',
    'warning': '#ffcc00',
    'success': '#00ff00',
}


class TelemetryFrame:
    """Parsed telemetry frame data"""
    def __init__(self):
        self.timestamp = 0
        # Orientation
        self.roll = 0.0
        self.pitch = 0.0
        self.yaw = 0.0
        # Accelerometer
        self.acc_x = 0.0
        self.acc_y = 0.0
        self.acc_z = 0.0
        # Pressure
        self.pressure = 0.0
        self.baro_altitude = 0.0
        # GPS
        self.latitude = 0.0
        self.longitude = 0.0
        self.gps_altitude = 0.0
        self.speed = 0.0
        self.heading = 0.0
        self.satellites = 0
        self.gps_fix = 0
        self.hdop = 0.0
        # Servo Commands
        self.s1_cmd = 0.0
        self.s2_cmd = 0.0
        self.s3_cmd = 0.0
        self.s4_cmd = 0.0
        # Servo Feedback
        self.s1_fb = 0.0
        self.s2_fb = 0.0
        self.s3_fb = 0.0
        self.s4_fb = 0.0
        # Servo Status
        self.servo_online = 0
        # Tracking
        self.target_x = -1
        self.target_y = -1
        self.target_w = 0
        self.target_h = 0
        # Battery
        self.battery_percent = 0
        self.is_charging = False
        self.battery_voltage = 0
        # Temperature
        self.temperature = 0.0


def parse_frame(data: bytes) -> TelemetryFrame:
    """Parse 73-byte telemetry frame"""
    if len(data) != TOTAL_FRAME_SIZE:
        return None
    
    # Verify header
    if data[0] != FRAME_HEADER_1 or data[1] != FRAME_HEADER_2:
        return None
    
    # Verify checksum
    checksum = 0
    for i in range(TOTAL_FRAME_SIZE - 1):
        checksum ^= data[i]
    if checksum != data[TOTAL_FRAME_SIZE - 1]:
        return None
    
    frame = TelemetryFrame()
    
    # Parse using struct (Little Endian)
    offset = 3  # Skip header (2) and length (1)
    
    # Timestamp (4 bytes - uint32)
    frame.timestamp = struct.unpack_from('<I', data, offset)[0]
    offset += 4
    
    # Orientation (6 bytes - int16 x3, scaled by 10)
    roll_raw, pitch_raw, yaw_raw = struct.unpack_from('<hhh', data, offset)
    frame.roll = roll_raw / 10.0
    frame.pitch = pitch_raw / 10.0
    frame.yaw = yaw_raw / 10.0
    offset += 6
    
    # Accelerometer (6 bytes - int16 x3, scaled by 100)
    acc_x_raw, acc_y_raw, acc_z_raw = struct.unpack_from('<hhh', data, offset)
    frame.acc_x = acc_x_raw / 100.0
    frame.acc_y = acc_y_raw / 100.0
    frame.acc_z = acc_z_raw / 100.0
    offset += 6
    
    # Pressure (2 bytes) + Baro Altitude (2 bytes)
    press_raw, baro_alt_raw = struct.unpack_from('<hh', data, offset)
    frame.pressure = press_raw
    frame.baro_altitude = baro_alt_raw / 10.0
    offset += 4
    
    # GPS Latitude (4 bytes - int32, scaled by 10^7)
    lat_raw = struct.unpack_from('<i', data, offset)[0]
    frame.latitude = lat_raw / 10_000_000.0
    offset += 4
    
    # GPS Longitude (4 bytes - int32, scaled by 10^7)
    lon_raw = struct.unpack_from('<i', data, offset)[0]
    frame.longitude = lon_raw / 10_000_000.0
    offset += 4
    
    # GPS Altitude (2 bytes)
    frame.gps_altitude = struct.unpack_from('<h', data, offset)[0]
    offset += 2
    
    # Speed (2 bytes - uint16, cm/s)
    speed_raw = struct.unpack_from('<H', data, offset)[0]
    frame.speed = speed_raw / 100.0
    offset += 2
    
    # Heading (2 bytes - uint16, scaled by 10)
    heading_raw = struct.unpack_from('<H', data, offset)[0]
    frame.heading = heading_raw / 10.0
    offset += 2
    
    # Satellites (1 byte)
    frame.satellites = data[offset]
    offset += 1
    
    # GPS Fix (1 byte)
    frame.gps_fix = data[offset]
    offset += 1
    
    # HDOP (2 bytes - uint16, scaled by 100)
    hdop_raw = struct.unpack_from('<H', data, offset)[0]
    frame.hdop = hdop_raw / 100.0
    offset += 2
    
    # Servo Commands (8 bytes - int16 x4, scaled by 10)
    s1c, s2c, s3c, s4c = struct.unpack_from('<hhhh', data, offset)
    frame.s1_cmd = s1c / 10.0
    frame.s2_cmd = s2c / 10.0
    frame.s3_cmd = s3c / 10.0
    frame.s4_cmd = s4c / 10.0
    offset += 8
    
    # Servo Feedback (8 bytes - int16 x4, scaled by 10)
    s1f, s2f, s3f, s4f = struct.unpack_from('<hhhh', data, offset)
    frame.s1_fb = s1f / 10.0
    frame.s2_fb = s2f / 10.0
    frame.s3_fb = s3f / 10.0
    frame.s4_fb = s4f / 10.0
    offset += 8
    
    # Servo Online Status (1 byte - bitmask)
    frame.servo_online = data[offset]
    offset += 1
    
    # Tracking (8 bytes - int16 x2, uint16 x2)
    frame.target_x, frame.target_y = struct.unpack_from('<hh', data, offset)
    offset += 4
    frame.target_w, frame.target_h = struct.unpack_from('<HH', data, offset)
    offset += 4
    
    # Battery (4 bytes)
    frame.battery_percent = data[offset]
    offset += 1
    frame.is_charging = data[offset] != 0
    offset += 1
    frame.battery_voltage = struct.unpack_from('<H', data, offset)[0]
    offset += 2
    
    # Temperature (2 bytes - int16, scaled by 10)
    temp_raw = struct.unpack_from('<h', data, offset)[0]
    frame.temperature = temp_raw / 10.0
    
    return frame


class SerialReaderThread(QThread):
    """Background thread for reading serial data"""
    frame_received = pyqtSignal(object)
    raw_data = pyqtSignal(bytes)
    connection_status = pyqtSignal(bool, str)
    
    def __init__(self, port, baudrate=BAUD_RATE):
        super().__init__()
        self.port = port
        self.baudrate = baudrate
        self.running = False
        self.serial = None
    
    def run(self):
        self.running = True
        buffer = bytearray()
        
        try:
            self.serial = serial.Serial(self.port, self.baudrate, timeout=0.1)
            self.connection_status.emit(True, f"Connected to {self.port}")
            
            while self.running:
                # Read available data
                if self.serial.in_waiting > 0:
                    data = self.serial.read(self.serial.in_waiting)
                    self.raw_data.emit(data)
                    buffer.extend(data)
                    
                    # Process complete frames
                    while len(buffer) >= TOTAL_FRAME_SIZE:
                        # Find header
                        header_idx = -1
                        for i in range(len(buffer) - 1):
                            if buffer[i] == FRAME_HEADER_1 and buffer[i+1] == FRAME_HEADER_2:
                                header_idx = i
                                break
                        
                        if header_idx == -1:
                            # No header found, keep last byte
                            buffer = buffer[-1:]
                            break
                        
                        # Discard bytes before header
                        if header_idx > 0:
                            buffer = buffer[header_idx:]
                        
                        # Need full frame
                        if len(buffer) < TOTAL_FRAME_SIZE:
                            break
                        
                        # Parse frame
                        frame_data = bytes(buffer[:TOTAL_FRAME_SIZE])
                        frame = parse_frame(frame_data)
                        
                        if frame:
                            self.frame_received.emit(frame)
                        
                        # Remove processed frame
                        buffer = buffer[TOTAL_FRAME_SIZE:]
                        
        except Exception as e:
            self.connection_status.emit(False, str(e))
        finally:
            if self.serial:
                self.serial.close()
            self.connection_status.emit(False, "Disconnected")
    
    def stop(self):
        self.running = False
        self.wait()


class PlotCanvas(FigureCanvas):
    """Reusable matplotlib canvas with dark theme"""
    def __init__(self, title="", width=4, height=2.5):
        self.fig = Figure(figsize=(width, height), dpi=100, facecolor=COLORS['panel'])
        super().__init__(self.fig)
        
        self.ax = self.fig.add_subplot(111)
        self.ax.set_facecolor(COLORS['background'])
        self.ax.tick_params(colors=COLORS['text'], labelsize=8)
        self.ax.spines['bottom'].set_color(COLORS['grid'])
        self.ax.spines['top'].set_color(COLORS['grid'])
        self.ax.spines['left'].set_color(COLORS['grid'])
        self.ax.spines['right'].set_color(COLORS['grid'])
        self.ax.grid(True, color=COLORS['grid'], alpha=0.3, linestyle='--')
        
        if title:
            self.ax.set_title(title, color=COLORS['accent'], fontsize=12, fontweight='bold')
        
        self.fig.tight_layout(pad=1.5)


class ServoPlot(PlotCanvas):
    """Servo CMD vs FB plot with diagnostic overlay"""
    def __init__(self, servo_num):
        super().__init__(title=f"Servo {servo_num}")
        self.servo_num = servo_num
        
        # Data buffers
        self.time_data = deque(maxlen=MAX_BUFFER_SIZE)
        self.cmd_data = deque(maxlen=MAX_BUFFER_SIZE)
        self.fb_data = deque(maxlen=MAX_BUFFER_SIZE)
        
        # Plot lines
        self.cmd_line, = self.ax.plot([], [], color=COLORS['cmd'], 
                                       linewidth=2, label='CMD', zorder=2)
        self.fb_line, = self.ax.plot([], [], color=COLORS['feedback'], 
                                      linewidth=2, label='FB', zorder=1)
        
        self.ax.legend(loc='upper right', fontsize=8, 
                       facecolor=COLORS['panel'], edgecolor=COLORS['grid'],
                       labelcolor=COLORS['text'])
        self.ax.set_ylabel('Degrees (°)', color=COLORS['text'], fontsize=9)
        self.ax.set_ylim(-100, 100)
        
        # Online status indicator
        self.status_text = self.ax.text(0.02, 0.98, '●', transform=self.ax.transAxes,
                                         fontsize=14, color='gray', 
                                         verticalalignment='top')
    
    def update_data(self, timestamp, cmd, fb, is_online):
        """Add new data point"""
        self.time_data.append(timestamp / 1000.0)  # Convert to seconds
        self.cmd_data.append(cmd)
        self.fb_data.append(fb)
        
        # Update online status
        color = COLORS['success'] if is_online else 'gray'
        self.status_text.set_color(color)
    
    def refresh_plot(self):
        """Redraw the plot with smart decimation"""
        if len(self.time_data) < 2:
            return
        
        # Smart decimation for performance
        data_len = len(self.time_data)
        step = max(1, data_len // 300)  # Max 300 points
        
        times = list(self.time_data)[::step]
        cmds = list(self.cmd_data)[::step]
        fbs = list(self.fb_data)[::step]
        
        self.cmd_line.set_data(times, cmds)
        self.fb_line.set_data(times, fbs)
        
        # Auto-scale X axis (show last 10 seconds)
        if times:
            x_max = times[-1]
            x_min = max(0, x_max - 10)
            self.ax.set_xlim(x_min, x_max)
        
        # Auto-scale Y axis with padding
        all_vals = cmds + fbs
        if all_vals:
            y_min = min(all_vals) - 5
            y_max = max(all_vals) + 5
            self.ax.set_ylim(y_min, y_max)
        
        self.draw_idle()


class OrientationPlot(PlotCanvas):
    """Roll, Pitch, Yaw plot"""
    def __init__(self):
        super().__init__(title="Orientation (Gyroscope)")
        
        self.time_data = deque(maxlen=MAX_BUFFER_SIZE)
        self.roll_data = deque(maxlen=MAX_BUFFER_SIZE)
        self.pitch_data = deque(maxlen=MAX_BUFFER_SIZE)
        self.yaw_data = deque(maxlen=MAX_BUFFER_SIZE)
        
        self.roll_line, = self.ax.plot([], [], color='#ff6b6b', linewidth=1.5, label='Roll')
        self.pitch_line, = self.ax.plot([], [], color='#4ecdc4', linewidth=1.5, label='Pitch')
        self.yaw_line, = self.ax.plot([], [], color='#ffe66d', linewidth=1.5, label='Yaw')
        
        self.ax.legend(loc='upper right', fontsize=8,
                       facecolor=COLORS['panel'], edgecolor=COLORS['grid'],
                       labelcolor=COLORS['text'])
        self.ax.set_ylabel('Degrees (°)', color=COLORS['text'], fontsize=9)
        self.ax.set_ylim(-180, 180)
    
    def update_data(self, timestamp, roll, pitch, yaw):
        self.time_data.append(timestamp / 1000.0)
        self.roll_data.append(roll)
        self.pitch_data.append(pitch)
        self.yaw_data.append(yaw)
    
    def refresh_plot(self):
        if len(self.time_data) < 2:
            return
        
        data_len = len(self.time_data)
        step = max(1, data_len // 300)
        
        times = list(self.time_data)[::step]
        rolls = list(self.roll_data)[::step]
        pitches = list(self.pitch_data)[::step]
        yaws = list(self.yaw_data)[::step]
        
        self.roll_line.set_data(times, rolls)
        self.pitch_line.set_data(times, pitches)
        self.yaw_line.set_data(times, yaws)
        
        if times:
            x_max = times[-1]
            x_min = max(0, x_max - 10)
            self.ax.set_xlim(x_min, x_max)
        
        self.draw_idle()


class MainWindow(QMainWindow):
    """Main application window"""
    def __init__(self):
        super().__init__()
        self.setWindowTitle("CANphon Telemetry Viewer v3.2")
        self.setMinimumSize(1400, 900)
        
        # Apply dark theme
        self.apply_dark_theme()
        
        # Serial reader thread
        self.serial_thread = None
        
        # Frame counter
        self.frame_count = 0
        self.fps = 0
        self.last_fps_time = datetime.now()
        
        # Setup UI
        self.setup_ui()
        
        # Refresh timer (30Hz for smooth updates)
        self.refresh_timer = QTimer()
        self.refresh_timer.timeout.connect(self.refresh_plots)
        self.refresh_timer.start(33)  # ~30 FPS
        
        # FPS counter timer
        self.fps_timer = QTimer()
        self.fps_timer.timeout.connect(self.update_fps)
        self.fps_timer.start(1000)
    
    def apply_dark_theme(self):
        """Apply dark theme to the application"""
        palette = QPalette()
        palette.setColor(QPalette.Window, QColor(COLORS['background']))
        palette.setColor(QPalette.WindowText, QColor(COLORS['text']))
        palette.setColor(QPalette.Base, QColor(COLORS['panel']))
        palette.setColor(QPalette.AlternateBase, QColor(COLORS['background']))
        palette.setColor(QPalette.Text, QColor(COLORS['text']))
        palette.setColor(QPalette.Button, QColor(COLORS['panel']))
        palette.setColor(QPalette.ButtonText, QColor(COLORS['text']))
        palette.setColor(QPalette.Highlight, QColor(COLORS['accent']))
        self.setPalette(palette)
        
        self.setStyleSheet(f"""
            QMainWindow {{
                background-color: {COLORS['background']};
            }}
            QGroupBox {{
                color: {COLORS['accent']};
                font-weight: bold;
                border: 1px solid {COLORS['grid']};
                border-radius: 5px;
                margin-top: 10px;
                padding-top: 10px;
            }}
            QGroupBox::title {{
                subcontrol-origin: margin;
                left: 10px;
            }}
            QPushButton {{
                background-color: {COLORS['panel']};
                color: {COLORS['text']};
                border: 1px solid {COLORS['grid']};
                border-radius: 4px;
                padding: 8px 16px;
                font-weight: bold;
            }}
            QPushButton:hover {{
                background-color: {COLORS['grid']};
                border-color: {COLORS['accent']};
            }}
            QPushButton:pressed {{
                background-color: {COLORS['accent']};
                color: {COLORS['background']};
            }}
            QComboBox {{
                background-color: {COLORS['panel']};
                color: {COLORS['text']};
                border: 1px solid {COLORS['grid']};
                border-radius: 4px;
                padding: 5px;
            }}
            QTextEdit {{
                background-color: #0a0a12;
                color: #00ff00;
                font-family: Consolas, monospace;
                font-size: 10px;
                border: 1px solid {COLORS['grid']};
            }}
            QLabel {{
                color: {COLORS['text']};
            }}
        """)
    
    def setup_ui(self):
        """Setup the main UI layout"""
        central = QWidget()
        self.setCentralWidget(central)
        main_layout = QVBoxLayout(central)
        main_layout.setContentsMargins(10, 10, 10, 10)
        main_layout.setSpacing(10)
        
        # === Control Bar ===
        control_bar = QHBoxLayout()
        
        # Port selection
        self.port_combo = QComboBox()
        self.port_combo.setMinimumWidth(200)
        self.refresh_ports()
        control_bar.addWidget(QLabel("Port:"))
        control_bar.addWidget(self.port_combo)
        
        # Refresh ports button
        refresh_btn = QPushButton("🔄 Refresh")
        refresh_btn.clicked.connect(self.refresh_ports)
        control_bar.addWidget(refresh_btn)
        
        # Connect button
        self.connect_btn = QPushButton("▶ Connect")
        self.connect_btn.clicked.connect(self.toggle_connection)
        control_bar.addWidget(self.connect_btn)
        
        control_bar.addStretch()
        
        # Status indicators
        self.status_label = QLabel("● Disconnected")
        self.status_label.setStyleSheet(f"color: gray; font-weight: bold;")
        control_bar.addWidget(self.status_label)
        
        self.fps_label = QLabel("0 FPS")
        self.fps_label.setStyleSheet(f"color: {COLORS['accent']}; font-weight: bold;")
        control_bar.addWidget(self.fps_label)
        
        main_layout.addLayout(control_bar)
        
        # === Main Content (Splitter) ===
        splitter = QSplitter(Qt.Horizontal)
        
        # Left side: Terminal
        terminal_group = QGroupBox("📟 Raw Terminal")
        terminal_layout = QVBoxLayout(terminal_group)
        self.terminal = QTextEdit()
        self.terminal.setReadOnly(True)
        self.terminal.setMaximumWidth(350)
        terminal_layout.addWidget(self.terminal)
        splitter.addWidget(terminal_group)
        
        # Right side: Plots
        plots_widget = QWidget()
        plots_layout = QVBoxLayout(plots_widget)
        plots_layout.setSpacing(10)
        
        # Row 1: 4 Servo Plots
        servo_group = QGroupBox("⚙️ Servo Response (CMD vs Feedback)")
        servo_grid = QGridLayout(servo_group)
        servo_grid.setSpacing(5)
        
        self.servo_plots = []
        for i in range(4):
            plot = ServoPlot(i + 1)
            self.servo_plots.append(plot)
            servo_grid.addWidget(plot, 0, i)
        
        plots_layout.addWidget(servo_group)
        
        # Row 2: Orientation + Info panels
        bottom_row = QHBoxLayout()
        
        # Orientation plot
        orient_group = QGroupBox("🔄 Orientation")
        orient_layout = QVBoxLayout(orient_group)
        self.orientation_plot = OrientationPlot()
        orient_layout.addWidget(self.orientation_plot)
        bottom_row.addWidget(orient_group, 2)
        
        # Info panels
        info_group = QGroupBox("📊 System Status")
        info_layout = QVBoxLayout(info_group)
        
        # GPS
        self.gps_label = QLabel("📍 GPS: --°, --° (-- sats)")
        self.gps_label.setStyleSheet(f"color: {COLORS['text']}; font-size: 11px;")
        info_layout.addWidget(self.gps_label)
        
        # Battery
        self.battery_label = QLabel("🔋 Battery: --% (--V)")
        self.battery_label.setStyleSheet(f"color: {COLORS['text']}; font-size: 11px;")
        info_layout.addWidget(self.battery_label)
        
        # Temperature
        self.temp_label = QLabel("🌡️ Temp: --°C")
        self.temp_label.setStyleSheet(f"color: {COLORS['text']}; font-size: 11px;")
        info_layout.addWidget(self.temp_label)
        
        # Accelerometer
        self.accel_label = QLabel("📈 Accel: X=-- Y=-- Z=--")
        self.accel_label.setStyleSheet(f"color: {COLORS['text']}; font-size: 11px;")
        info_layout.addWidget(self.accel_label)
        
        # Pressure
        self.pressure_label = QLabel("🎈 Pressure: -- hPa (Alt: --m)")
        self.pressure_label.setStyleSheet(f"color: {COLORS['text']}; font-size: 11px;")
        info_layout.addWidget(self.pressure_label)
        
        # Tracking
        self.tracking_label = QLabel("🎯 Target: --")
        self.tracking_label.setStyleSheet(f"color: {COLORS['text']}; font-size: 11px;")
        info_layout.addWidget(self.tracking_label)
        
        info_layout.addStretch()
        bottom_row.addWidget(info_group, 1)
        
        plots_layout.addLayout(bottom_row)
        
        splitter.addWidget(plots_widget)
        splitter.setSizes([300, 1100])
        
        main_layout.addWidget(splitter)
    
    def refresh_ports(self):
        """Refresh available serial ports"""
        self.port_combo.clear()
        ports = serial.tools.list_ports.comports()
        for port in ports:
            self.port_combo.addItem(f"{port.device} - {port.description}", port.device)
    
    def toggle_connection(self):
        """Connect or disconnect"""
        if self.serial_thread and self.serial_thread.isRunning():
            self.disconnect()
        else:
            self.connect()
    
    def connect(self):
        """Start serial connection"""
        port = self.port_combo.currentData()
        if not port:
            return
        
        self.serial_thread = SerialReaderThread(port)
        self.serial_thread.frame_received.connect(self.on_frame_received)
        self.serial_thread.raw_data.connect(self.on_raw_data)
        self.serial_thread.connection_status.connect(self.on_connection_status)
        self.serial_thread.start()
        
        self.connect_btn.setText("⏹ Disconnect")
    
    def disconnect(self):
        """Stop serial connection"""
        if self.serial_thread:
            self.serial_thread.stop()
            self.serial_thread = None
        
        self.connect_btn.setText("▶ Connect")
        self.status_label.setText("● Disconnected")
        self.status_label.setStyleSheet("color: gray; font-weight: bold;")
    
    def on_connection_status(self, connected, message):
        """Handle connection status change"""
        if connected:
            self.status_label.setText(f"● {message}")
            self.status_label.setStyleSheet(f"color: {COLORS['success']}; font-weight: bold;")
        else:
            self.status_label.setText(f"● {message}")
            self.status_label.setStyleSheet("color: gray; font-weight: bold;")
    
    def on_raw_data(self, data):
        """Handle raw serial data for terminal"""
        try:
            # Show hex dump
            hex_str = data.hex(' ').upper()
            timestamp = datetime.now().strftime("%H:%M:%S.%f")[:-3]
            self.terminal.append(f"[{timestamp}] {hex_str}")
            
            # Keep terminal size manageable
            if len(self.terminal.toPlainText()) > 30000:
                cursor = self.terminal.textCursor()
                cursor.setPosition(0)
                cursor.setPosition(10000, cursor.KeepAnchor)
                cursor.removeSelectedText()
        except:
            pass
    
    def on_frame_received(self, frame: TelemetryFrame):
        """Handle received telemetry frame"""
        self.frame_count += 1
        
        # Update servo plots
        servo_cmds = [frame.s1_cmd, frame.s2_cmd, frame.s3_cmd, frame.s4_cmd]
        servo_fbs = [frame.s1_fb, frame.s2_fb, frame.s3_fb, frame.s4_fb]
        
        for i, plot in enumerate(self.servo_plots):
            is_online = (frame.servo_online >> i) & 1
            plot.update_data(frame.timestamp, servo_cmds[i], servo_fbs[i], is_online)
        
        # Update orientation plot
        self.orientation_plot.update_data(
            frame.timestamp, frame.roll, frame.pitch, frame.yaw
        )
        
        # Update info labels
        fix_str = ["No Fix", "2D", "3D"][min(frame.gps_fix, 2)]
        self.gps_label.setText(
            f"📍 GPS: {frame.latitude:.6f}°, {frame.longitude:.6f}° ({frame.satellites} sats, {fix_str})"
        )
        
        charging = "⚡" if frame.is_charging else ""
        voltage = frame.battery_voltage / 1000.0
        self.battery_label.setText(
            f"🔋 Battery: {frame.battery_percent}% ({voltage:.2f}V) {charging}"
        )
        
        self.temp_label.setText(f"🌡️ Temp: {frame.temperature:.1f}°C")
        
        self.accel_label.setText(
            f"📈 Accel: X={frame.acc_x:.2f} Y={frame.acc_y:.2f} Z={frame.acc_z:.2f}"
        )
        
        self.pressure_label.setText(
            f"🎈 Pressure: {frame.pressure:.1f} hPa (Alt: {frame.baro_altitude:.1f}m)"
        )
        
        if frame.target_x >= 0:
            self.tracking_label.setText(
                f"🎯 Target: ({frame.target_x}, {frame.target_y}) [{frame.target_w}x{frame.target_h}]"
            )
        else:
            self.tracking_label.setText("🎯 Target: No target")
    
    def refresh_plots(self):
        """Refresh all plots"""
        for plot in self.servo_plots:
            plot.refresh_plot()
        self.orientation_plot.refresh_plot()
    
    def update_fps(self):
        """Calculate and display FPS"""
        now = datetime.now()
        elapsed = (now - self.last_fps_time).total_seconds()
        if elapsed > 0:
            self.fps = int(self.frame_count / elapsed)
        self.frame_count = 0
        self.last_fps_time = now
        self.fps_label.setText(f"{self.fps} FPS")
    
    def closeEvent(self, event):
        """Clean up on close"""
        self.disconnect()
        event.accept()


def main():
    app = QApplication(sys.argv)
    app.setStyle('Fusion')
    
    window = MainWindow()
    window.show()
    
    sys.exit(app.exec_())


if __name__ == '__main__':
    main()
