#!/usr/bin/env python3
"""
CANphon Telemetry Viewer v4.0 - Optimized Edition
==================================================
Combines v2.0 performance with v3.0 features:
- 4 Servo plots (CMD vs FB) in 2x2 grid
- Fast rendering (no decimation lag)
- Interactive cursor
- Dark theme

Based on v2.0 architecture for maximum speed.
"""

import sys
import struct
import threading
import time
from collections import deque
from datetime import datetime

from PyQt5.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QPushButton, QComboBox, QLabel, QPlainTextEdit, QGroupBox,
    QSplitter, QGridLayout, QFileDialog, QMessageBox
)
from PyQt5.QtCore import Qt, QTimer, pyqtSignal, QObject
from PyQt5.QtGui import QFont

import matplotlib
matplotlib.use('Qt5Agg')
from matplotlib.backends.backend_qt5agg import FigureCanvasQTAgg as FigureCanvas
from matplotlib.figure import Figure

import serial
import serial.tools.list_ports


# ===================== PROTOCOL CONSTANTS =====================
HEADER_1 = 0xAA
HEADER_2 = 0x55
FRAME_SIZE = 73
BAUD_RATE = 115200


# ===================== SIGNAL BRIDGE =====================
class SignalBridge(QObject):
    new_frame = pyqtSignal(dict)
    raw_data = pyqtSignal(bytes)
    status_update = pyqtSignal(str)


# ===================== FRAME PARSER =====================
class FrameParser:
    def __init__(self):
        self.buffer = bytearray()
        self.frame_count = 0
        self.error_count = 0
    
    def parse(self, data: bytes) -> list:
        self.buffer.extend(data)
        frames = []
        
        while len(self.buffer) >= FRAME_SIZE:
            try:
                idx = self.buffer.index(HEADER_1)
                if idx > 0:
                    self.buffer = self.buffer[idx:]
                
                if len(self.buffer) < FRAME_SIZE:
                    break
                
                if self.buffer[1] != HEADER_2:
                    self.buffer = self.buffer[1:]
                    continue
                
                frame_data = bytes(self.buffer[:FRAME_SIZE])
                self.buffer = self.buffer[FRAME_SIZE:]
                
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
        try:
            offset = 3
            timestamp = struct.unpack('<I', data[offset:offset+4])[0]
            offset += 4
            
            roll = struct.unpack('<h', data[offset:offset+2])[0] / 10.0
            pitch = struct.unpack('<h', data[offset+2:offset+4])[0] / 10.0
            yaw = struct.unpack('<h', data[offset+4:offset+6])[0] / 10.0
            offset += 6
            
            accel_x = struct.unpack('<h', data[offset:offset+2])[0] / 100.0
            accel_y = struct.unpack('<h', data[offset+2:offset+4])[0] / 100.0
            accel_z = struct.unpack('<h', data[offset+4:offset+6])[0] / 100.0
            offset += 6
            
            pressure = struct.unpack('<H', data[offset:offset+2])[0]
            baro_alt = struct.unpack('<h', data[offset+2:offset+4])[0] / 10.0
            offset += 4
            
            lat = struct.unpack('<i', data[offset:offset+4])[0] / 1e7
            lon = struct.unpack('<i', data[offset+4:offset+8])[0] / 1e7
            offset += 18  # Skip rest of GPS
            
            servo_cmds = struct.unpack('<4h', data[offset:offset+8])
            offset += 8
            
            servo_fb = struct.unpack('<4h', data[offset:offset+8])
            offset += 8
            
            servo_status = data[offset]
            offset += 1
            
            offset += 8  # Skip tracking
            
            battery_pct = data[offset]
            charging = data[offset+1]
            voltage = struct.unpack('<H', data[offset+2:offset+4])[0]
            offset += 4
            
            temperature = struct.unpack('<h', data[offset:offset+2])[0] / 10.0
            
            return {
                'timestamp': timestamp,
                'roll': roll, 'pitch': pitch, 'yaw': yaw,
                'accel_x': accel_x, 'accel_y': accel_y, 'accel_z': accel_z,
                'servo_cmds': [c / 10.0 for c in servo_cmds],
                'servo_fb': [f / 10.0 for f in servo_fb],
                'servo_status': servo_status,
                'battery': battery_pct, 'voltage': voltage,
                'temperature': temperature
            }
        except Exception as e:
            print(f"Parse error: {e}")
            return None


# ===================== FAST SERVO PLOT =====================
class FastServoPlot(QWidget):
    """Single servo plot with CMD (red) and FB (blue) - optimized for speed"""
    
    def __init__(self, servo_num, max_points=500, parent=None):
        super().__init__(parent)
        self.servo_num = servo_num
        self.max_points = max_points
        
        # Data storage
        self.time_data = deque(maxlen=max_points)
        self.cmd_data = deque(maxlen=max_points)
        self.fb_data = deque(maxlen=max_points)
        self.start_time = time.time()
        
        self.setup_ui()
    
    def setup_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(2, 2, 2, 2)
        
        # Small, efficient figure
        self.fig = Figure(figsize=(4, 2), dpi=100)
        self.fig.patch.set_facecolor('#1e1e1e')
        self.canvas = FigureCanvas(self.fig)
        
        self.ax = self.fig.add_subplot(111)
        self.ax.set_facecolor('#2d2d2d')
        self.ax.set_title(f'Servo {self.servo_num}', color='#00ff88', fontsize=10, pad=3)
        self.ax.tick_params(colors='white', labelsize=7)
        for spine in self.ax.spines.values():
            spine.set_color('#444444')
        self.ax.grid(True, alpha=0.3, linestyle='--')
        
        # CMD line (red) and FB line (blue) - like MATLAB
        self.cmd_line, = self.ax.plot([], [], color='#FF0000', linewidth=1.5, label='CMD')
        self.fb_line, = self.ax.plot([], [], color='#0072BD', linewidth=1.5, label='FB')
        
        self.ax.legend(loc='upper right', fontsize=6, facecolor='#2d2d2d', 
                      labelcolor='white', framealpha=0.8)
        
        # Cursor
        self.cursor_ann = self.ax.annotate(
            '', xy=(0, 0), xytext=(8, 8),
            textcoords='offset points',
            bbox=dict(boxstyle='round,pad=0.3', facecolor='#FFFF00', edgecolor='black'),
            fontsize=7, visible=False, zorder=100
        )
        self.cursor_vline = self.ax.axvline(x=0, color='#FFFF00', linestyle='--', 
                                            linewidth=1, visible=False)
        
        self.fig.tight_layout(pad=1.0)
        layout.addWidget(self.canvas)
        
        # Click event for cursor
        self.canvas.mpl_connect('button_press_event', self.on_click)
    
    def on_click(self, event):
        if event.inaxes != self.ax or event.xdata is None:
            return
        
        time_list = list(self.time_data)
        if not time_list:
            return
        
        idx = min(range(len(time_list)), key=lambda i: abs(time_list[i] - event.xdata))
        t = time_list[idx]
        
        cmd_val = list(self.cmd_data)[idx] if idx < len(self.cmd_data) else 0
        fb_val = list(self.fb_data)[idx] if idx < len(self.fb_data) else 0
        delta = abs(cmd_val - fb_val)
        
        text = f"t={t:.2f}s\nCMD: {cmd_val:.1f}°\nFB: {fb_val:.1f}°\nΔ: {delta:.1f}°"
        
        self.cursor_ann.xy = (t, (cmd_val + fb_val) / 2)
        self.cursor_ann.set_text(text)
        self.cursor_ann.set_visible(True)
        self.cursor_vline.set_xdata([t])
        self.cursor_vline.set_visible(True)
        
        self.canvas.draw_idle()
    
    def update_data(self, timestamp, cmd, fb):
        self.time_data.append(timestamp)
        self.cmd_data.append(cmd)
        self.fb_data.append(fb)
        
        # Update lines
        time_list = list(self.time_data)
        self.cmd_line.set_data(time_list, list(self.cmd_data))
        self.fb_line.set_data(time_list, list(self.fb_data))
        
        # Auto-scale
        if time_list:
            self.ax.set_xlim(max(0, time_list[-1] - 10), time_list[-1] + 0.5)
            all_vals = list(self.cmd_data) + list(self.fb_data)
            if all_vals:
                margin = max(5, (max(all_vals) - min(all_vals)) * 0.15)
                self.ax.set_ylim(min(all_vals) - margin, max(all_vals) + margin)
        
        self.canvas.draw_idle()
    
    def clear_data(self):
        self.time_data.clear()
        self.cmd_data.clear()
        self.fb_data.clear()
        self.start_time = time.time()
        self.cmd_line.set_data([], [])
        self.fb_line.set_data([], [])
        self.canvas.draw()


# ===================== ORIENTATION PLOT =====================
class OrientationPlot(QWidget):
    """Roll, Pitch, Yaw plot"""
    
    def __init__(self, max_points=500, parent=None):
        super().__init__(parent)
        self.max_points = max_points
        
        self.time_data = deque(maxlen=max_points)
        self.roll_data = deque(maxlen=max_points)
        self.pitch_data = deque(maxlen=max_points)
        self.yaw_data = deque(maxlen=max_points)
        
        self.setup_ui()
    
    def setup_ui(self):
        layout = QVBoxLayout(self)
        layout.setContentsMargins(2, 2, 2, 2)
        
        self.fig = Figure(figsize=(6, 2.5), dpi=100)
        self.fig.patch.set_facecolor('#1e1e1e')
        self.canvas = FigureCanvas(self.fig)
        
        self.ax = self.fig.add_subplot(111)
        self.ax.set_facecolor('#2d2d2d')
        self.ax.set_title('📐 Orientation (deg)', color='#00ff88', fontsize=10)
        self.ax.tick_params(colors='white', labelsize=8)
        for spine in self.ax.spines.values():
            spine.set_color('#444444')
        self.ax.grid(True, alpha=0.3)
        
        self.roll_line, = self.ax.plot([], [], color='#ff6b6b', linewidth=1.5, label='Roll')
        self.pitch_line, = self.ax.plot([], [], color='#4ecdc4', linewidth=1.5, label='Pitch')
        self.yaw_line, = self.ax.plot([], [], color='#45b7d1', linewidth=1.5, label='Yaw')
        
        self.ax.legend(loc='upper right', fontsize=7, facecolor='#2d2d2d', 
                      labelcolor='white', framealpha=0.8)
        
        self.fig.tight_layout()
        layout.addWidget(self.canvas)
    
    def update_data(self, timestamp, roll, pitch, yaw):
        self.time_data.append(timestamp)
        self.roll_data.append(roll)
        self.pitch_data.append(pitch)
        self.yaw_data.append(yaw)
        
        time_list = list(self.time_data)
        self.roll_line.set_data(time_list, list(self.roll_data))
        self.pitch_line.set_data(time_list, list(self.pitch_data))
        self.yaw_line.set_data(time_list, list(self.yaw_data))
        
        if time_list:
            self.ax.set_xlim(max(0, time_list[-1] - 10), time_list[-1] + 0.5)
            all_vals = list(self.roll_data) + list(self.pitch_data) + list(self.yaw_data)
            if all_vals:
                margin = max(5, (max(all_vals) - min(all_vals)) * 0.15)
                self.ax.set_ylim(min(all_vals) - margin, max(all_vals) + margin)
        
        self.canvas.draw_idle()
    
    def clear_data(self):
        self.time_data.clear()
        self.roll_data.clear()
        self.pitch_data.clear()
        self.yaw_data.clear()
        self.roll_line.set_data([], [])
        self.pitch_line.set_data([], [])
        self.yaw_line.set_data([], [])
        self.canvas.draw()


# ===================== MAIN WINDOW =====================
class TelemetryViewerV4(QMainWindow):
    
    def __init__(self):
        super().__init__()
        self.setWindowTitle("CANphon Telemetry Viewer v4.0 - Optimized")
        self.setGeometry(50, 50, 1400, 900)
        self.setStyleSheet("""
            QMainWindow { background-color: #121212; }
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
            QPlainTextEdit { 
                background-color: #0a0a0a; color: #00ff00; 
                font-family: Consolas; font-size: 9px;
                border: 1px solid #333333;
            }
            QGroupBox { 
                color: #00ff88; border: 1px solid #444444; 
                border-radius: 4px; margin-top: 8px; padding-top: 8px;
                font-weight: bold;
            }
            QGroupBox::title { subcontrol-origin: margin; left: 10px; }
        """)
        
        self.serial_port = None
        self.is_connected = False
        self.read_thread = None
        self.parser = FrameParser()
        self.recorded_data = []
        
        self.signals = SignalBridge()
        self.signals.new_frame.connect(self.on_new_frame)
        self.signals.raw_data.connect(self.on_raw_data)
        self.signals.status_update.connect(self.on_status_update)
        
        self.setup_ui()
        self.refresh_ports()
        
        # Fast update timer - 20Hz (every 50ms)
        self.update_timer = QTimer()
        self.update_timer.timeout.connect(self.update_plots)
        self.update_timer.start(50)
        
        self.pending_frame = None
        self.terminal_counter = 0
    
    def setup_ui(self):
        central = QWidget()
        self.setCentralWidget(central)
        layout = QVBoxLayout(central)
        layout.setSpacing(5)
        
        # ===== Connection Bar =====
        conn_layout = QHBoxLayout()
        
        conn_layout.addWidget(QLabel("📡 Port:"))
        self.port_combo = QComboBox()
        self.port_combo.setMinimumWidth(180)
        conn_layout.addWidget(self.port_combo)
        
        self.refresh_btn = QPushButton("🔄")
        self.refresh_btn.setMaximumWidth(40)
        self.refresh_btn.clicked.connect(self.refresh_ports)
        conn_layout.addWidget(self.refresh_btn)
        
        self.connect_btn = QPushButton("🔌 Connect")
        self.connect_btn.clicked.connect(self.toggle_connection)
        conn_layout.addWidget(self.connect_btn)
        
        conn_layout.addSpacing(15)
        
        self.clear_btn = QPushButton("🗑 Clear")
        self.clear_btn.clicked.connect(self.clear_all)
        conn_layout.addWidget(self.clear_btn)
        
        self.export_btn = QPushButton("💾 Export")
        self.export_btn.clicked.connect(self.export_csv)
        conn_layout.addWidget(self.export_btn)
        
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
        
        # Left: Terminal (small)
        terminal_group = QGroupBox("📟 Terminal")
        terminal_layout = QVBoxLayout(terminal_group)
        self.terminal = QPlainTextEdit()
        self.terminal.setReadOnly(True)
        self.terminal.setMaximumBlockCount(200)
        self.terminal.setMaximumWidth(280)
        terminal_layout.addWidget(self.terminal)
        splitter.addWidget(terminal_group)
        
        # Right: Plots
        plots_widget = QWidget()
        plots_layout = QVBoxLayout(plots_widget)
        plots_layout.setSpacing(3)
        
        # Orientation Plot
        self.orientation_plot = OrientationPlot()
        plots_layout.addWidget(self.orientation_plot)
        
        # Servo Plots (2x2 grid)
        servo_group = QGroupBox("🎛️ Servos - CMD (Red) vs Feedback (Blue)")
        servo_grid = QGridLayout(servo_group)
        servo_grid.setSpacing(3)
        
        self.servo_plots = []
        for i in range(4):
            plot = FastServoPlot(i + 1)
            self.servo_plots.append(plot)
            servo_grid.addWidget(plot, i // 2, i % 2)
        
        plots_layout.addWidget(servo_group)
        
        splitter.addWidget(plots_widget)
        splitter.setSizes([280, 1120])
        
        layout.addWidget(splitter)
        
        # ===== Status Bar =====
        status_layout = QHBoxLayout()
        
        self.frame_label = QLabel("Frames: 0")
        self.frame_label.setStyleSheet("color: #666666;")
        status_layout.addWidget(self.frame_label)
        
        status_layout.addStretch()
        
        self.data_label = QLabel("Roll: -- | Pitch: -- | Yaw: -- | Battery: --%")
        self.data_label.setStyleSheet("color: #00ff88; font-family: Consolas;")
        status_layout.addWidget(self.data_label)
        
        layout.addLayout(status_layout)
    
    def refresh_ports(self):
        self.port_combo.clear()
        ports = serial.tools.list_ports.comports()
        for port in ports:
            self.port_combo.addItem(f"{port.device} - {port.description}", port.device)
    
    def toggle_connection(self):
        if self.is_connected:
            self.disconnect()
        else:
            self.connect()
    
    def connect(self):
        if self.port_combo.currentIndex() < 0:
            return
        
        port = self.port_combo.currentData()
        try:
            self.serial_port = serial.Serial(port, BAUD_RATE, timeout=0.1)
            self.is_connected = True
            self.connect_btn.setText("⛔ Disconnect")
            self.connect_btn.setStyleSheet("background-color: #e74c3c; color: white; border: none; padding: 8px 16px; border-radius: 4px; font-weight: bold;")
            self.status_label.setText(f"🟢 {port}")
            self.status_label.setStyleSheet("color: #00ff88; font-weight: bold;")
            
            self.read_thread = threading.Thread(target=self.read_serial, daemon=True)
            self.read_thread.start()
            
        except Exception as e:
            self.status_label.setText(f"🔴 Error: {e}")
            self.status_label.setStyleSheet("color: #ff6b6b;")
    
    def disconnect(self):
        self.is_connected = False
        if self.serial_port:
            self.serial_port.close()
            self.serial_port = None
        
        self.connect_btn.setText("🔌 Connect")
        self.connect_btn.setStyleSheet("")
        self.status_label.setText("⚪ Disconnected")
        self.status_label.setStyleSheet("color: #888888;")
    
    def read_serial(self):
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
        # Throttle terminal updates (every 5th)
        self.terminal_counter += 1
        if self.terminal_counter % 5 != 0:
            return
        
        hex_str = ' '.join(f'{b:02X}' for b in data[:24])
        if len(data) > 24:
            hex_str += f' +{len(data)-24}B'
        self.terminal.appendPlainText(hex_str)
    
    def on_new_frame(self, frame: dict):
        self.pending_frame = frame
        self.recorded_data.append(frame)
        self.frame_label.setText(f"Frames: {self.parser.frame_count}")
        self.data_label.setText(
            f"Roll: {frame['roll']:.1f}° | Pitch: {frame['pitch']:.1f}° | "
            f"Yaw: {frame['yaw']:.1f}° | Battery: {frame['battery']}%"
        )
    
    def on_status_update(self, status: str):
        self.fps_label.setText(status)
    
    def update_plots(self):
        if self.pending_frame is None:
            return
        
        frame = self.pending_frame
        t = frame['timestamp'] / 1000.0
        self.pending_frame = None
        
        # Update orientation
        self.orientation_plot.update_data(t, frame['roll'], frame['pitch'], frame['yaw'])
        
        # Update servos
        for i, plot in enumerate(self.servo_plots):
            cmd = frame['servo_cmds'][i]
            fb = frame['servo_fb'][i]
            plot.update_data(t, cmd, fb)
    
    def clear_all(self):
        self.recorded_data.clear()
        self.parser.frame_count = 0
        self.parser.error_count = 0
        
        self.orientation_plot.clear_data()
        for plot in self.servo_plots:
            plot.clear_data()
        self.terminal.clear()
        
        self.frame_label.setText("Frames: 0")
    
    def export_csv(self):
        if not self.recorded_data:
            QMessageBox.warning(self, "Warning", "No data to export")
            return
        
        filename, _ = QFileDialog.getSaveFileName(
            self, "Save CSV", 
            f"telemetry_{datetime.now().strftime('%Y%m%d_%H%M%S')}.csv",
            "CSV Files (*.csv)"
        )
        if not filename:
            return
        
        try:
            with open(filename, 'w') as f:
                f.write("timestamp,roll,pitch,yaw,s1_cmd,s1_fb,s2_cmd,s2_fb,s3_cmd,s3_fb,s4_cmd,s4_fb,battery\n")
                for frame in self.recorded_data:
                    f.write(f"{frame['timestamp']},{frame['roll']:.2f},{frame['pitch']:.2f},{frame['yaw']:.2f},")
                    for i in range(4):
                        f.write(f"{frame['servo_cmds'][i]:.1f},{frame['servo_fb'][i]:.1f},")
                    f.write(f"{frame['battery']}\n")
            
            QMessageBox.information(self, "Success", f"Exported {len(self.recorded_data)} frames")
        except Exception as e:
            QMessageBox.warning(self, "Error", f"Export failed: {e}")
    
    def closeEvent(self, event):
        self.disconnect()
        event.accept()


# ===================== MAIN =====================
if __name__ == '__main__':
    app = QApplication(sys.argv)
    app.setStyle('Fusion')
    
    window = TelemetryViewerV4()
    window.show()
    
    sys.exit(app.exec_())
