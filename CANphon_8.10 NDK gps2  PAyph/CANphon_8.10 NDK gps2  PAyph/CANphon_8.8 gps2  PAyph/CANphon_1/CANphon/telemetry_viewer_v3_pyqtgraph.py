#!/usr/bin/env python3
"""
CANphon Telemetry Viewer v3.0 - PyQtGraph Edition (FAST!)
==========================================================
نفس الواجهة والمميزات مع سرعة فائقة باستخدام pyqtgraph
"""

import sys
import struct
import threading
import time
from collections import deque
from datetime import datetime

from PyQt5.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QGridLayout, QPushButton, QComboBox, QLabel, QFrame, QFileDialog,
    QMessageBox, QSizePolicy, QStackedWidget, QSplitter, QTextEdit
)
from PyQt5.QtCore import Qt, QTimer, pyqtSignal, QObject
from PyQt5.QtGui import QFont, QCursor

import pyqtgraph as pg
import numpy as np

import serial
import serial.tools.list_ports

# Configure pyqtgraph for speed
pg.setConfigOptions(antialias=False, useOpenGL=True, enableExperimental=True)


# ===================== PROTOCOL CONSTANTS =====================
HEADER_1 = 0xAA
HEADER_2 = 0x55
FRAME_SIZE = 73


# ===================== SIGNAL BRIDGE =====================
class SignalBridge(QObject):
    new_frame = pyqtSignal(dict)
    status_update = pyqtSignal(str)
    raw_data = pyqtSignal(bytes)


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
            gps_alt = struct.unpack('<h', data[offset+8:offset+10])[0]
            speed = struct.unpack('<H', data[offset+10:offset+12])[0] / 100.0
            heading = struct.unpack('<H', data[offset+12:offset+14])[0] / 10.0
            sats = data[offset+14]
            fix_type = data[offset+15]
            hdop = struct.unpack('<H', data[offset+16:offset+18])[0] / 100.0
            offset += 18
            
            servo_cmds = [struct.unpack('<h', data[offset+i*2:offset+i*2+2])[0] for i in range(4)]
            offset += 8
            
            servo_fb = [struct.unpack('<h', data[offset+i*2:offset+i*2+2])[0] for i in range(4)]
            offset += 8
            
            servo_status = data[offset]
            offset += 1
            
            track_x = struct.unpack('<h', data[offset:offset+2])[0]
            track_y = struct.unpack('<h', data[offset+2:offset+4])[0]
            track_w = struct.unpack('<H', data[offset+4:offset+6])[0]
            track_h = struct.unpack('<H', data[offset+6:offset+8])[0]
            offset += 8
            
            battery_pct = data[offset]
            charging = data[offset+1]
            voltage = struct.unpack('<H', data[offset+2:offset+4])[0]
            offset += 4
            
            temperature = struct.unpack('<h', data[offset:offset+2])[0] / 10.0
            
            return {
                'timestamp': timestamp,
                'roll': roll, 'pitch': pitch, 'yaw': yaw,
                'accel_x': accel_x, 'accel_y': accel_y, 'accel_z': accel_z,
                'pressure': pressure, 'baro_alt': baro_alt,
                'lat': lat, 'lon': lon, 'gps_alt': gps_alt,
                'speed': speed, 'heading': heading, 'sats': sats, 'fix': fix_type, 'hdop': hdop,
                'servo_cmds': servo_cmds, 'servo_fb': servo_fb, 'servo_status': servo_status,
                'track_x': track_x, 'track_y': track_y, 'track_w': track_w, 'track_h': track_h,
                'battery': battery_pct, 'charging': charging, 'voltage': voltage,
                'temperature': temperature
            }
        except:
            return None


# ===================== PLOT BOX =====================
class PlotBox(QFrame):
    clicked = pyqtSignal(object)
    
    def __init__(self, title, labels, colors, max_points=600):
        super().__init__()
        self.title = title
        self.labels = labels
        self.colors = colors
        self.max_points = max_points
        
        self.time_data = deque(maxlen=max_points)
        self.data = {label: deque(maxlen=max_points) for label in labels}
        
        self._setup_ui()
    
    def _setup_ui(self):
        self.setStyleSheet("""
            PlotBox {
                background-color: #1e1e1e;
                border: 2px solid #444444;
                border-radius: 8px;
            }
            PlotBox:hover {
                border: 2px solid #00aaff;
            }
        """)
        self.setCursor(Qt.PointingHandCursor)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Expanding)
        
        layout = QVBoxLayout(self)
        layout.setContentsMargins(8, 8, 8, 8)
        layout.setSpacing(4)
        
        # Title
        title_lbl = QLabel(f"🔍 {self.title}")
        title_lbl.setStyleSheet("color: #00ff88; font-weight: bold; font-size: 16px;")
        title_lbl.setAlignment(Qt.AlignCenter)
        layout.addWidget(title_lbl)
        
        # Plot
        self.pw = pg.PlotWidget(background='#2d2d2d')
        self.pw.showGrid(x=True, y=True, alpha=0.3)
        self.pw.getAxis('left').setPen('w')
        self.pw.getAxis('bottom').setPen('w')
        self.pw.addLegend(offset=(10, 10))
        
        self.curves = {}
        for label, color in zip(self.labels, self.colors):
            self.curves[label] = self.pw.plot(pen=pg.mkPen(color, width=2), name=label)
        
        layout.addWidget(self.pw, 1)
        
        # Hint
        hint = QLabel("اضغط للتكبير")
        hint.setStyleSheet("color: #666666; font-size: 9px;")
        hint.setAlignment(Qt.AlignCenter)
        layout.addWidget(hint)
    
    def mousePressEvent(self, e):
        if e.button() == Qt.LeftButton:
            self.clicked.emit(self)
        super().mousePressEvent(e)
    
    def update_data(self, values: dict, timestamp: float):
        self.time_data.append(timestamp)
        for label, value in values.items():
            if label in self.data:
                self.data[label].append(value)
    
    def redraw(self):
        if len(self.time_data) < 2:
            return
        t = np.array(self.time_data)
        for label, curve in self.curves.items():
            if label in self.data:
                curve.setData(t, np.array(self.data[label]))
    
    def clear_data(self):
        self.time_data.clear()
        for d in self.data.values():
            d.clear()


# ===================== SERVO GROUP =====================
class ServoGroupWidget(QFrame):
    clicked = pyqtSignal(object)
    
    SERVO_NAMES = ['Servo 1 (أمام يسار)', 'Servo 2 (أمام يمين)', 
                   'Servo 3 (خلف يسار)', 'Servo 4 (خلف يمين)']
    
    def __init__(self, max_points=600):
        super().__init__()
        self.max_points = max_points
        
        self.time_data = deque(maxlen=max_points)
        self.servo_data = {
            i: {'CMD': deque(maxlen=max_points), 'FB': deque(maxlen=max_points)}
            for i in range(4)
        }
        
        self._setup_ui()
    
    def _setup_ui(self):
        self.setStyleSheet("""
            ServoGroupWidget {
                background-color: #1e1e1e;
                border: 2px solid #444444;
                border-radius: 8px;
            }
            ServoGroupWidget:hover {
                border: 2px solid #00aaff;
            }
        """)
        self.setCursor(Qt.PointingHandCursor)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Expanding)
        
        layout = QVBoxLayout(self)
        layout.setContentsMargins(8, 8, 8, 8)
        layout.setSpacing(4)
        
        # Title
        title_lbl = QLabel("🔍 🎛️ SERVOS (Cmd vs Fb)")
        title_lbl.setStyleSheet("color: #00ff88; font-weight: bold; font-size: 16px;")
        title_lbl.setAlignment(Qt.AlignCenter)
        layout.addWidget(title_lbl)
        
        # 2x2 Grid
        grid = QWidget()
        grid_layout = QGridLayout(grid)
        grid_layout.setSpacing(5)
        
        self.plots = []
        self.cmd_curves = []
        self.fb_curves = []
        
        for i in range(4):
            pw = pg.PlotWidget(background='#2d2d2d')
            pw.setTitle(self.SERVO_NAMES[i], color='w', size='10pt')
            pw.showGrid(x=True, y=True, alpha=0.3)
            pw.getAxis('left').setPen('w')
            pw.getAxis('bottom').setPen('w')
            
            # Draw CMD first, then FB on top for visibility
            cmd = pw.plot(pen=pg.mkPen('#FF0000', width=2))
            fb = pw.plot(pen=pg.mkPen('#0072BD', width=3))  # Thicker
            
            self.plots.append(pw)
            self.cmd_curves.append(cmd)
            self.fb_curves.append(fb)
            
            grid_layout.addWidget(pw, i // 2, i % 2)
        
        layout.addWidget(grid, 1)
        
        # Legend
        leg = QHBoxLayout()
        leg.addStretch()
        cmd_l = QLabel("━━ CMD (الأمر)")
        cmd_l.setStyleSheet("color: #FF0000; font-size: 10px; font-weight: bold;")
        leg.addWidget(cmd_l)
        leg.addSpacing(20)
        fb_l = QLabel("━━ FB (Feedback)")
        fb_l.setStyleSheet("color: #0080FF; font-size: 10px; font-weight: bold;")
        leg.addWidget(fb_l)
        leg.addStretch()
        layout.addLayout(leg)
        
        # Hint
        hint = QLabel("اضغط للتكبير")
        hint.setStyleSheet("color: #666666; font-size: 9px;")
        hint.setAlignment(Qt.AlignCenter)
        layout.addWidget(hint)
    
    def mousePressEvent(self, e):
        if e.button() == Qt.LeftButton:
            self.clicked.emit(self)
        super().mousePressEvent(e)
    
    def update_data(self, servo_cmds, servo_fbs, timestamp):
        self.time_data.append(timestamp)
        for i in range(4):
            self.servo_data[i]['CMD'].append(servo_cmds[i])
            self.servo_data[i]['FB'].append(servo_fbs[i])
    
    def redraw(self):
        if len(self.time_data) < 2:
            return
        t = np.array(self.time_data)
        for i in range(4):
            self.cmd_curves[i].setData(t, np.array(self.servo_data[i]['CMD']))
            self.fb_curves[i].setData(t, np.array(self.servo_data[i]['FB']))
    
    def clear_data(self):
        self.time_data.clear()
        for i in range(4):
            self.servo_data[i]['CMD'].clear()
            self.servo_data[i]['FB'].clear()


# ===================== EXPANDED VIEW =====================
class ExpandedPlotView(QWidget):
    close_requested = pyqtSignal()
    
    def __init__(self):
        super().__init__()
        self.source = None
        self._setup_ui()
    
    def _setup_ui(self):
        self.setStyleSheet("background-color: #1a1a1a;")
        layout = QVBoxLayout(self)
        layout.setContentsMargins(10, 10, 10, 10)
        
        # Header
        header = QHBoxLayout()
        self.title_lbl = QLabel("📊 الرسم البياني")
        self.title_lbl.setStyleSheet("color: #00ff88; font-size: 18px; font-weight: bold;")
        header.addWidget(self.title_lbl)
        header.addStretch()
        
        close_btn = QPushButton("❌ إغلاق (ESC)")
        close_btn.setStyleSheet("background-color: #cc3333; color: white; border: none; padding: 10px 20px; border-radius: 5px; font-weight: bold;")
        close_btn.clicked.connect(self.close_requested.emit)
        header.addWidget(close_btn)
        layout.addLayout(header)
        
        # Plot
        self.pw = pg.PlotWidget(background='#252525')
        self.pw.showGrid(x=True, y=True, alpha=0.4)
        self.pw.getAxis('left').setPen('w')
        self.pw.getAxis('bottom').setPen('w')
        self.pw.setLabel('left', 'Value', color='w')
        self.pw.setLabel('bottom', 'Time (s)', color='w')
        self.pw.addLegend(offset=(10, 10))
        
        self.curves = {}
        layout.addWidget(self.pw, 1)
    
    def load_from_widget(self, widget):
        self.source = widget
        self.title_lbl.setText(f"📊 {widget.title}")
        
        self.pw.clear()
        self.curves = {}
        
        for label, color in zip(widget.labels, widget.colors):
            self.curves[label] = self.pw.plot(pen=pg.mkPen(color, width=2), name=label)
        
        self.update_from_widget()
    
    def update_from_widget(self):
        if self.source is None or len(self.source.time_data) < 2:
            return
        t = np.array(self.source.time_data)
        for label in self.source.labels:
            if label in self.curves and label in self.source.data:
                self.curves[label].setData(t, np.array(self.source.data[label]))
    
    def keyPressEvent(self, e):
        if e.key() == Qt.Key_Escape:
            self.close_requested.emit()
        super().keyPressEvent(e)


# ===================== EXPANDED SERVO VIEW =====================
class ExpandedServoView(QWidget):
    close_requested = pyqtSignal()
    
    SERVO_NAMES = ['Servo 1 (أمام يسار)', 'Servo 2 (أمام يمين)', 
                   'Servo 3 (خلف يسار)', 'Servo 4 (خلف يمين)']
    
    def __init__(self):
        super().__init__()
        self.source = None
        self._setup_ui()
    
    def _setup_ui(self):
        self.setStyleSheet("background-color: #1a1a1a;")
        layout = QVBoxLayout(self)
        layout.setContentsMargins(10, 10, 10, 10)
        
        # Header
        header = QHBoxLayout()
        self.title_lbl = QLabel("🎛️ SERVOS - تحليل الأوامر والاستجابة")
        self.title_lbl.setStyleSheet("color: #00ff88; font-size: 18px; font-weight: bold;")
        header.addWidget(self.title_lbl)
        header.addStretch()
        
        close_btn = QPushButton("❌ إغلاق (ESC)")
        close_btn.setStyleSheet("background-color: #cc3333; color: white; border: none; padding: 10px 20px; border-radius: 5px; font-weight: bold;")
        close_btn.clicked.connect(self.close_requested.emit)
        header.addWidget(close_btn)
        layout.addLayout(header)
        
        # 2x2 Grid
        grid = QWidget()
        grid_layout = QGridLayout(grid)
        grid_layout.setSpacing(10)
        
        self.plots = []
        self.cmd_curves = []
        self.fb_curves = []
        
        for i in range(4):
            pw = pg.PlotWidget(background='#252525')
            pw.setTitle(self.SERVO_NAMES[i], color='w', size='12pt')
            pw.showGrid(x=True, y=True, alpha=0.4)
            pw.getAxis('left').setPen('w')
            pw.getAxis('bottom').setPen('w')
            pw.setLabel('left', 'Degrees (°)', color='w')
            pw.setLabel('bottom', 'Time (s)', color='w')
            pw.addLegend(offset=(10, 10))
            
            # Draw CMD first (red), then FB on top (blue) for visibility
            cmd = pw.plot(pen=pg.mkPen('#FF0000', width=2), name='CMD')
            fb = pw.plot(pen=pg.mkPen('#0072BD', width=3), name='FB')  # Thicker line
            
            self.plots.append(pw)
            self.cmd_curves.append(cmd)
            self.fb_curves.append(fb)
            
            grid_layout.addWidget(pw, i // 2, i % 2)
        
        layout.addWidget(grid, 1)
        
        # Legend
        leg = QHBoxLayout()
        leg.addStretch()
        cmd_l = QLabel("━━━ CMD (الأمر)")
        cmd_l.setStyleSheet("color: #FF0000; font-size: 14px; font-weight: bold;")
        leg.addWidget(cmd_l)
        leg.addSpacing(40)
        fb_l = QLabel("━━━ FB (Feedback)")
        fb_l.setStyleSheet("color: #0080FF; font-size: 14px; font-weight: bold;")
        leg.addWidget(fb_l)
        leg.addStretch()
        layout.addLayout(leg)
    
    def load_from_servo_group(self, widget):
        self.source = widget
        self.update_from_servo_group()
    
    def update_from_servo_group(self):
        if self.source is None or len(self.source.time_data) < 2:
            return
        t = np.array(self.source.time_data)
        for i in range(4):
            self.cmd_curves[i].setData(t, np.array(self.source.servo_data[i]['CMD']))
            self.fb_curves[i].setData(t, np.array(self.source.servo_data[i]['FB']))
    
    def keyPressEvent(self, e):
        if e.key() == Qt.Key_Escape:
            self.close_requested.emit()
        super().keyPressEvent(e)


# ===================== MAIN WINDOW =====================
class TelemetryViewerV3(QMainWindow):
    
    def __init__(self):
        super().__init__()
        self.setWindowTitle("CANphon Telemetry Viewer v3.0 - PyQtGraph (FAST!)")
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
            QComboBox { 
                background-color: #2d2d2d; color: white; 
                border: 1px solid #555555; padding: 5px;
            }
        """)
        
        self.serial_port = None
        self.is_connected = False
        self.read_thread = None
        self.parser = FrameParser()
        self.recorded_data = []
        
        self.signals = SignalBridge()
        self.signals.new_frame.connect(self.on_new_frame)
        self.signals.status_update.connect(self.on_status_update)
        self.signals.raw_data.connect(self.on_raw_data)
        
        self.setup_ui()
        self.refresh_ports()
        
        # 200 FPS - سرعة فائقة مع pyqtgraph!
        self.update_timer = QTimer()
        self.update_timer.timeout.connect(self.update_plots)
        self.update_timer.start(5)  # 5ms = 200 FPS
        
        self.pending_frames = []  # Queue instead of single frame
        self.term_count = 0
    
    def setup_ui(self):
        central = QWidget()
        self.setCentralWidget(central)
        main_layout = QVBoxLayout(central)
        main_layout.setSpacing(8)
        
        # Connection Bar
        conn_layout = QHBoxLayout()
        
        conn_layout.addWidget(QLabel("📡 COM Port:"))
        self.port_combo = QComboBox()
        self.port_combo.setMinimumWidth(180)
        conn_layout.addWidget(self.port_combo)
        
        conn_layout.addWidget(QLabel("⚡ Baud:"))
        self.baud_combo = QComboBox()
        self.baud_combo.addItems(['9600', '19200', '38400', '57600', '115200', '230400', '460800', '921600', '1000000', '2000000'])
        self.baud_combo.setCurrentText('115200')
        self.baud_combo.setStyleSheet("color: #00ff88; border: 1px solid #00aaff;")
        conn_layout.addWidget(self.baud_combo)
        
        self.refresh_btn = QPushButton("🔄 تحديث")
        self.refresh_btn.clicked.connect(self.refresh_ports)
        conn_layout.addWidget(self.refresh_btn)
        
        self.connect_btn = QPushButton("🔌 اتصال")
        self.connect_btn.clicked.connect(self.toggle_connection)
        conn_layout.addWidget(self.connect_btn)
        
        self.clear_btn = QPushButton("🗑 مسح")
        self.clear_btn.clicked.connect(self.clear_all)
        conn_layout.addWidget(self.clear_btn)
        
        self.export_btn = QPushButton("💾 تصدير CSV")
        self.export_btn.clicked.connect(self.export_csv)
        conn_layout.addWidget(self.export_btn)
        
        conn_layout.addStretch()
        
        self.status_label = QLabel("⚪ غير متصل")
        self.status_label.setStyleSheet("color: #888888; font-weight: bold;")
        conn_layout.addWidget(self.status_label)
        
        self.fps_label = QLabel("0 fps")
        self.fps_label.setStyleSheet("color: #00ff88; font-weight: bold;")
        conn_layout.addWidget(self.fps_label)
        
        main_layout.addLayout(conn_layout)
        
        # Main Content
        main_splitter = QSplitter(Qt.Horizontal)
        
        # Terminal
        terminal_frame = QFrame()
        terminal_frame.setStyleSheet("background-color: #0a0a0a; border: 2px solid #333333; border-radius: 8px;")
        terminal_layout = QVBoxLayout(terminal_frame)
        terminal_layout.setContentsMargins(5, 5, 5, 5)
        
        terminal_title = QLabel("📟 Serial Terminal")
        terminal_title.setStyleSheet("color: #00ff88; font-weight: bold; border: none;")
        terminal_layout.addWidget(terminal_title)
        
        self.terminal_text = QTextEdit()
        self.terminal_text.setReadOnly(True)
        self.terminal_text.setStyleSheet("background-color: #0a0a0a; color: #00ff00; font-family: Consolas; font-size: 10px; border: none;")
        terminal_layout.addWidget(self.terminal_text)
        
        clear_terminal_btn = QPushButton("🗑 Clear Terminal")
        clear_terminal_btn.setStyleSheet("background-color: #2196F3;")
        clear_terminal_btn.clicked.connect(lambda: self.terminal_text.clear())
        terminal_layout.addWidget(clear_terminal_btn)
        
        main_splitter.addWidget(terminal_frame)
        
        # Plots
        plots_container = QWidget()
        plots_layout = QVBoxLayout(plots_container)
        plots_layout.setContentsMargins(0, 0, 0, 0)
        
        self.stacked = QStackedWidget()
        
        # Grid View
        self.grid_widget = QWidget()
        grid_layout = QGridLayout(self.grid_widget)
        grid_layout.setSpacing(10)
        
        for row in range(3):
            grid_layout.setRowStretch(row, 1)
        for col in range(2):
            grid_layout.setColumnStretch(col, 1)
        
        self.orientation_plot = PlotBox("📐 ORIENTATION", ['Roll', 'Pitch', 'Yaw'], ['#ff6b6b', '#4ecdc4', '#45b7d1'])
        self.orientation_plot.clicked.connect(self.expand_plot)
        grid_layout.addWidget(self.orientation_plot, 0, 0)
        
        self.servo_group = ServoGroupWidget()
        self.servo_group.clicked.connect(self.expand_servo_group)
        grid_layout.addWidget(self.servo_group, 0, 1)
        
        self.accel_plot = PlotBox("📊 ACCELEROMETER", ['X', 'Y', 'Z'], ['#ff9f43', '#26de81', '#a55eea'])
        self.accel_plot.clicked.connect(self.expand_plot)
        grid_layout.addWidget(self.accel_plot, 1, 0)
        
        self.gps_plot = PlotBox("📡 GPS", ['Speed', 'Heading', 'Altitude'], ['#00ff88', '#ff6b9d', '#ffeaa7'])
        self.gps_plot.clicked.connect(self.expand_plot)
        grid_layout.addWidget(self.gps_plot, 1, 1)
        
        self.battery_plot = PlotBox("🔋 BATTERY & TEMP", ['Battery%', 'Voltage/10', 'Temp'], ['#f1c40f', '#e67e22', '#e74c3c'])
        self.battery_plot.clicked.connect(self.expand_plot)
        grid_layout.addWidget(self.battery_plot, 2, 0)
        
        self.tracking_plot = PlotBox("🎯 TRACKING", ['Target_X', 'Target_Y'], ['#00ff00', '#ff00ff'])
        self.tracking_plot.clicked.connect(self.expand_plot)
        grid_layout.addWidget(self.tracking_plot, 2, 1)
        
        self.stacked.addWidget(self.grid_widget)
        
        # Expanded Views
        self.expanded_view = ExpandedPlotView()
        self.expanded_view.close_requested.connect(self.collapse_plot)
        self.stacked.addWidget(self.expanded_view)
        
        self.expanded_servo_view = ExpandedServoView()
        self.expanded_servo_view.close_requested.connect(self.collapse_plot)
        self.stacked.addWidget(self.expanded_servo_view)
        
        plots_layout.addWidget(self.stacked)
        main_splitter.addWidget(plots_container)
        
        main_splitter.setSizes([300, 900])
        main_layout.addWidget(main_splitter)
        
        # Status Bar
        status_layout = QHBoxLayout()
        self.frame_label = QLabel("Frames: 0")
        self.frame_label.setStyleSheet("color: #888888;")
        status_layout.addWidget(self.frame_label)
        
        self.error_label = QLabel("Errors: 0")
        self.error_label.setStyleSheet("color: #888888;")
        status_layout.addWidget(self.error_label)
        
        status_layout.addStretch()
        
        self.data_label = QLabel("Roll: -- | Pitch: -- | Yaw: -- | Battery: --%")
        self.data_label.setStyleSheet("color: #00ff88; font-family: Consolas;")
        status_layout.addWidget(self.data_label)
        
        main_layout.addLayout(status_layout)
    
    def expand_plot(self, widget):
        self.expanded_view.load_from_widget(widget)
        self.stacked.setCurrentIndex(1)
    
    def expand_servo_group(self, widget):
        self.expanded_servo_view.load_from_servo_group(widget)
        self.stacked.setCurrentIndex(2)
    
    def collapse_plot(self):
        self.stacked.setCurrentIndex(0)
    
    def keyPressEvent(self, e):
        if e.key() == Qt.Key_Escape and self.stacked.currentIndex() != 0:
            self.collapse_plot()
        super().keyPressEvent(e)
    
    def refresh_ports(self):
        self.port_combo.clear()
        for p in serial.tools.list_ports.comports():
            self.port_combo.addItem(f"{p.device} - {p.description}", p.device)
    
    def toggle_connection(self):
        if self.is_connected:
            self.disconnect()
        else:
            self.connect()
    
    def connect(self):
        if self.port_combo.currentIndex() < 0:
            return
        
        port = self.port_combo.currentData()
        baud = int(self.baud_combo.currentText())
        
        try:
            self.serial_port = serial.Serial(port, baud, timeout=0.1)
            self.is_connected = True
            self.connect_btn.setText("⛔ قطع الاتصال")
            self.connect_btn.setStyleSheet("background-color: #e74c3c; color: white; border: none; padding: 8px 16px; border-radius: 4px;")
            self.status_label.setText(f"🟢 {port} @ {baud}")
            self.status_label.setStyleSheet("color: #00ff88; font-weight: bold;")
            
            self.read_thread = threading.Thread(target=self.read_serial, daemon=True)
            self.read_thread.start()
        except Exception as e:
            self.status_label.setText(f"🔴 {e}")
            self.status_label.setStyleSheet("color: #ff6b6b;")
    
    def disconnect(self):
        self.is_connected = False
        if self.serial_port:
            self.serial_port.close()
            self.serial_port = None
        
        self.connect_btn.setText("🔌 اتصال")
        self.connect_btn.setStyleSheet("background-color: #3498db; color: white; border: none; padding: 8px 16px; border-radius: 4px;")
        self.status_label.setText("⚪ غير متصل")
        self.status_label.setStyleSheet("color: #888888;")
    
    def read_serial(self):
        last_fps_time = time.time()
        fps_count = 0
        
        while self.is_connected and self.serial_port:
            try:
                data = self.serial_port.read(256)
                if data:
                    self.signals.raw_data.emit(data)
                    for frame in self.parser.parse(data):
                        self.signals.new_frame.emit(frame)
                        fps_count += 1
                
                now = time.time()
                if now - last_fps_time >= 1.0:
                    self.signals.status_update.emit(f"{fps_count} fps")
                    fps_count = 0
                    last_fps_time = now
            except:
                break
    
    def on_new_frame(self, frame):
        self.pending_frames.append(frame)  # Add to queue
        self.recorded_data.append(frame)
        self.frame_label.setText(f"Frames: {self.parser.frame_count}")
        self.error_label.setText(f"Errors: {self.parser.error_count}")
        self.data_label.setText(f"Roll: {frame['roll']:.1f}° | Pitch: {frame['pitch']:.1f}° | Yaw: {frame['yaw']:.1f}° | Battery: {frame['battery']}%")
    
    def on_status_update(self, status):
        self.fps_label.setText(status)
    
    def on_raw_data(self, data):
        self.term_count += 1
        if self.term_count % 10 != 0:
            return
        hex_str = ' '.join(f'{b:02X}' for b in data[:32])
        if len(data) > 32:
            hex_str += f' ...+{len(data)-32}B'
        self.terminal_text.append(hex_str)
    
    def update_plots(self):
        if not self.pending_frames:
            return
        
        # Process ALL pending frames to ensure no delay
        frames_to_process = self.pending_frames
        self.pending_frames = []
        
        for frame in frames_to_process:
            t = frame['timestamp'] / 1000.0
            self.orientation_plot.update_data({'Roll': frame['roll'], 'Pitch': frame['pitch'], 'Yaw': frame['yaw']}, t)
            self.servo_group.update_data(frame['servo_cmds'], frame['servo_fb'], t)
            self.accel_plot.update_data({'X': frame['accel_x'], 'Y': frame['accel_y'], 'Z': frame['accel_z']}, t)
            self.gps_plot.update_data({'Speed': frame['speed'], 'Heading': frame['heading']/10, 'Altitude': frame['gps_alt']}, t)
            self.battery_plot.update_data({'Battery%': frame['battery'], 'Voltage/10': frame['voltage']/100, 'Temp': frame['temperature']}, t)
            self.tracking_plot.update_data({'Target_X': frame['track_x'], 'Target_Y': frame['track_y']}, t)
        
        # Redraw ONCE after processing all frames
        self.orientation_plot.redraw()
        self.servo_group.redraw()
        self.accel_plot.redraw()
        self.gps_plot.redraw()
        self.battery_plot.redraw()
        self.tracking_plot.redraw()
        
        if self.stacked.currentIndex() == 1:
            self.expanded_view.update_from_widget()
        elif self.stacked.currentIndex() == 2:
            self.expanded_servo_view.update_from_servo_group()
    
    def clear_all(self):
        self.recorded_data.clear()
        self.parser.frame_count = 0
        self.parser.error_count = 0
        self.orientation_plot.clear_data()
        self.servo_group.clear_data()
        self.accel_plot.clear_data()
        self.gps_plot.clear_data()
        self.battery_plot.clear_data()
        self.tracking_plot.clear_data()
        self.frame_label.setText("Frames: 0")
        self.error_label.setText("Errors: 0")
    
    def export_csv(self):
        if not self.recorded_data:
            QMessageBox.warning(self, "تحذير", "لا توجد بيانات")
            return
        
        filename, _ = QFileDialog.getSaveFileName(self, "حفظ", f"telemetry_{datetime.now().strftime('%Y%m%d_%H%M%S')}.csv", "CSV (*.csv)")
        if not filename:
            return
        
        try:
            with open(filename, 'w') as f:
                f.write("timestamp,roll,pitch,yaw,s1_cmd,s1_fb,s2_cmd,s2_fb,s3_cmd,s3_fb,s4_cmd,s4_fb,battery\n")
                for r in self.recorded_data:
                    f.write(f"{r['timestamp']},{r['roll']:.2f},{r['pitch']:.2f},{r['yaw']:.2f},")
                    for i in range(4):
                        f.write(f"{r['servo_cmds'][i]:.1f},{r['servo_fb'][i]:.1f},")
                    f.write(f"{r['battery']}\n")
            QMessageBox.information(self, "تم", f"تم تصدير {len(self.recorded_data)} إطار")
        except Exception as e:
            QMessageBox.warning(self, "خطأ", str(e))
    
    def closeEvent(self, e):
        self.disconnect()
        e.accept()


if __name__ == '__main__':
    app = QApplication(sys.argv)
    app.setStyle('Fusion')
    win = TelemetryViewerV3()
    win.show()
    sys.exit(app.exec_())
