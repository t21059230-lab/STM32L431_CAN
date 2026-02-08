#!/usr/bin/env python3
"""
CANphon Telemetry Viewer v3.0 - MATLAB Style (PyQtGraph Ultra Edition)
======================================================================
- 6 مربعات منظمة (Orientation, Servos, Accel, GPS, Battery, Tracking)
- اضغط على أي مربع لتكبيره ملء الشاشة مع Toolbar
- Cursor تفاعلي أصفر يُظهر القيم
- Zoom + Pan + Home
- سرعة MATLAB باستخدام pyqtgraph (60+ FPS)
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
    QMessageBox, QSizePolicy, QSplitter, QTextEdit, QStackedWidget
)
from PyQt5.QtCore import Qt, QTimer, pyqtSignal, QObject
from PyQt5.QtGui import QFont, QCursor

import pyqtgraph as pg
import numpy as np

import serial
import serial.tools.list_ports

# Configure pyqtgraph for MAXIMUM PERFORMANCE
pg.setConfigOptions(
    antialias=False,      # Disable antialiasing (big speedup)
    useOpenGL=True,       # GPU acceleration!
    enableExperimental=True,
    useNumba=False        # Numba can slow startup
)


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
            
            servo_cmds = [struct.unpack('<h', data[offset+i*2:offset+i*2+2])[0] / 10.0 for i in range(4)]
            offset += 8
            
            servo_fb = [struct.unpack('<h', data[offset+i*2:offset+i*2+2])[0] / 10.0 for i in range(4)]
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


# ===================== PLOT BOX (Single Plot with Click to Expand) =====================
class PlotBox(QFrame):
    """مربع رسم بياني قابل للضغط"""
    
    clicked = pyqtSignal(str)  # emit plot_id
    
    def __init__(self, plot_id, title, labels, colors, max_points=300):
        super().__init__()
        self.plot_id = plot_id
        self.title = title
        self.labels = labels
        self.colors = colors
        self.max_points = max_points
        
        # Data
        self.time_data = deque(maxlen=max_points)
        self.data = {lbl: deque(maxlen=max_points) for lbl in labels}
        
        self._setup_ui()
    
    def _setup_ui(self):
        self.setStyleSheet("""
            PlotBox {
                background-color: #1a1a1a;
                border: 2px solid #333;
                border-radius: 8px;
            }
            PlotBox:hover {
                border: 2px solid #00aaff;
            }
        """)
        self.setCursor(Qt.PointingHandCursor)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Expanding)
        
        layout = QVBoxLayout(self)
        layout.setContentsMargins(5, 5, 5, 5)
        layout.setSpacing(2)
        
        # Title
        title_lbl = QLabel(f"🔍 {self.title}")
        title_lbl.setStyleSheet("color: #00ff88; font-weight: bold; font-size: 13px;")
        title_lbl.setAlignment(Qt.AlignCenter)
        layout.addWidget(title_lbl)
        
        # Plot - maximized area (no axis labels)
        self.pw = pg.PlotWidget(background='#222')
        self.pw.showGrid(x=True, y=True, alpha=0.3)
        self.pw.getAxis('left').setPen('w')
        self.pw.getAxis('bottom').setPen('w')
        # Hide axis labels to maximize plot area
        self.pw.getAxis('left').setLabel('')
        self.pw.getAxis('bottom').setLabel('')
        # Reduce axis width/height for more plot space
        self.pw.getAxis('left').setWidth(35)
        self.pw.getAxis('bottom').setHeight(20)
        
        # Lines
        self.curves = {}
        for lbl, col in zip(self.labels, self.colors):
            self.curves[lbl] = self.pw.plot(pen=pg.mkPen(col, width=2), name=lbl)
        
        # Legend
        self.pw.addLegend(offset=(10, 10))
        
        # Crosshair
        self.vLine = pg.InfiniteLine(angle=90, movable=False, pen=pg.mkPen('#ffff00', width=1, style=Qt.DashLine))
        self.hLine = pg.InfiniteLine(angle=0, movable=False, pen=pg.mkPen('#ffff00', width=1, style=Qt.DashLine))
        self.pw.addItem(self.vLine, ignoreBounds=True)
        self.pw.addItem(self.hLine, ignoreBounds=True)
        
        # Cursor label
        self.cursor_label = pg.TextItem(anchor=(0, 1), fill='#333', color='#fff')
        self.pw.addItem(self.cursor_label, ignoreBounds=True)
        self.cursor_label.hide()
        
        # Mouse tracking
        self.pw.scene().sigMouseMoved.connect(self._on_mouse_move)
        
        layout.addWidget(self.pw, 1)
    
    def _on_mouse_move(self, pos):
        if self.pw.sceneBoundingRect().contains(pos):
            mp = self.pw.plotItem.vb.mapSceneToView(pos)
            self.vLine.setPos(mp.x())
            self.hLine.setPos(mp.y())
            self.cursor_label.setPos(mp.x(), mp.y())
            self.cursor_label.setText(f"t={mp.x():.2f}s, v={mp.y():.2f}")
            self.cursor_label.show()
        else:
            self.cursor_label.hide()
    
    def mousePressEvent(self, e):
        if e.button() == Qt.LeftButton:
            self.clicked.emit(self.plot_id)
        super().mousePressEvent(e)
    
    def add_point(self, t, values):
        self.time_data.append(t)
        for lbl, val in values.items():
            if lbl in self.data:
                self.data[lbl].append(val)
    
    def redraw(self):
        if len(self.time_data) < 2:
            return
        t = np.array(self.time_data)
        for lbl, curve in self.curves.items():
            curve.setData(t, np.array(self.data[lbl]))
    
    def clear_data(self):
        self.time_data.clear()
        for d in self.data.values():
            d.clear()


# ===================== SERVO BOX (2x2 Grid) =====================
class ServoBox(QFrame):
    """مربع السيرفوهات 2x2"""
    
    clicked = pyqtSignal(str)
    
    NAMES = ['Servo 1 (أمام يسار)', 'Servo 2 (أمام يمين)', 
             'Servo 3 (خلف يسار)', 'Servo 4 (خلف يمين)']
    
    def __init__(self, max_points=300):
        super().__init__()
        self.max_points = max_points
        
        self.time_data = deque(maxlen=max_points)
        self.cmd_data = [deque(maxlen=max_points) for _ in range(4)]
        self.fb_data = [deque(maxlen=max_points) for _ in range(4)]
        
        self._setup_ui()
    
    def _setup_ui(self):
        self.setStyleSheet("""
            ServoBox {
                background-color: #1a1a1a;
                border: 2px solid #333;
                border-radius: 8px;
            }
            ServoBox:hover {
                border: 2px solid #00aaff;
            }
        """)
        self.setCursor(Qt.PointingHandCursor)
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Expanding)
        
        layout = QVBoxLayout(self)
        layout.setContentsMargins(5, 5, 5, 5)
        layout.setSpacing(2)
        
        # Title
        title_lbl = QLabel("🔍 🎛️ SERVOS (Cmd vs Fb)")
        title_lbl.setStyleSheet("color: #00ff88; font-weight: bold; font-size: 13px;")
        title_lbl.setAlignment(Qt.AlignCenter)
        layout.addWidget(title_lbl)
        
        # 2x2 Grid
        grid = QWidget()
        grid_layout = QGridLayout(grid)
        grid_layout.setSpacing(3)
        grid_layout.setContentsMargins(0, 0, 0, 0)
        
        for i in range(2):
            grid_layout.setRowStretch(i, 1)
            grid_layout.setColumnStretch(i, 1)
        
        self.plots = []
        self.cmd_curves = []
        self.fb_curves = []
        
        for i in range(4):
            pw = pg.PlotWidget(background='#222')
            pw.setTitle(self.NAMES[i], color='#aaa', size='9pt')
            pw.showGrid(x=True, y=True, alpha=0.3)
            pw.setLabel('left', 'Degrees (°)')
            pw.setLabel('bottom', 'Time (s)')
            pw.getAxis('left').setPen('w')
            pw.getAxis('bottom').setPen('w')
            
            cmd_c = pw.plot(pen=pg.mkPen('#FF0000', width=2), name='CMD')
            fb_c = pw.plot(pen=pg.mkPen('#0088FF', width=2), name='FB')
            
            if i == 0:
                pw.addLegend(offset=(5, 5))
            
            self.plots.append(pw)
            self.cmd_curves.append(cmd_c)
            self.fb_curves.append(fb_c)
            
            grid_layout.addWidget(pw, i // 2, i % 2)
        
        layout.addWidget(grid, 1)
        
        # Legend
        leg = QHBoxLayout()
        leg.addStretch()
        cmd_l = QLabel("━━ CMD (الأمر)")
        cmd_l.setStyleSheet("color: #FF0000; font-size: 10px;")
        leg.addWidget(cmd_l)
        leg.addSpacing(15)
        fb_l = QLabel("━━ FB (Feedback)")
        fb_l.setStyleSheet("color: #0088FF; font-size: 10px;")
        leg.addWidget(fb_l)
        leg.addStretch()
        layout.addLayout(leg)
        
        # Hint
        hint = QLabel("اضغط للتكبير")
        hint.setStyleSheet("color: #555; font-size: 9px;")
        hint.setAlignment(Qt.AlignCenter)
        layout.addWidget(hint)
    
    def mousePressEvent(self, e):
        if e.button() == Qt.LeftButton:
            self.clicked.emit('servos')
        super().mousePressEvent(e)
    
    def add_point(self, t, cmds, fbs):
        self.time_data.append(t)
        for i in range(4):
            self.cmd_data[i].append(cmds[i])
            self.fb_data[i].append(fbs[i])
    
    def redraw(self):
        if len(self.time_data) < 2:
            return
        t = np.array(self.time_data)
        for i in range(4):
            self.cmd_curves[i].setData(t, np.array(self.cmd_data[i]))
            self.fb_curves[i].setData(t, np.array(self.fb_data[i]))
    
    def clear_data(self):
        self.time_data.clear()
        for i in range(4):
            self.cmd_data[i].clear()
            self.fb_data[i].clear()


# ===================== EXPANDED VIEW =====================
class ExpandedView(QWidget):
    """العرض الموسع مع Toolbar"""
    
    close_signal = pyqtSignal()
    
    def __init__(self):
        super().__init__()
        self.source_id = None
        self.source_widget = None
        self._setup_ui()
    
    def _setup_ui(self):
        self.setStyleSheet("background-color: #111;")
        layout = QVBoxLayout(self)
        layout.setContentsMargins(5, 5, 5, 5)
        
        # Header with toolbar
        header = QHBoxLayout()
        
        self.title_lbl = QLabel("📊 Title")
        self.title_lbl.setStyleSheet("color: #00ff88; font-weight: bold; font-size: 16px;")
        header.addWidget(self.title_lbl)
        
        header.addStretch()
        
        # Toolbar
        btn_style = "QPushButton{background:#444; color:white; border:none; padding:8px 12px; font-size:14px;} QPushButton:hover{background:#666;}"
        
        self.home_btn = QPushButton("🏠")
        self.home_btn.setStyleSheet(btn_style)
        self.home_btn.setToolTip("Home - Reset View")
        self.home_btn.clicked.connect(self._on_home)
        header.addWidget(self.home_btn)
        
        self.back_btn = QPushButton("⬅️")
        self.back_btn.setStyleSheet(btn_style)
        self.back_btn.setToolTip("Back")
        header.addWidget(self.back_btn)
        
        self.fwd_btn = QPushButton("➡️")
        self.fwd_btn.setStyleSheet(btn_style)
        self.fwd_btn.setToolTip("Forward")
        header.addWidget(self.fwd_btn)
        
        self.pan_btn = QPushButton("✋")
        self.pan_btn.setStyleSheet(btn_style)
        self.pan_btn.setToolTip("Pan")
        self.pan_btn.setCheckable(True)
        header.addWidget(self.pan_btn)
        
        self.zoom_btn = QPushButton("🔍")
        self.zoom_btn.setStyleSheet(btn_style)
        self.zoom_btn.setToolTip("Zoom")
        self.zoom_btn.setCheckable(True)
        header.addWidget(self.zoom_btn)
        
        self.cfg_btn = QPushButton("⚙️")
        self.cfg_btn.setStyleSheet(btn_style)
        self.cfg_btn.setToolTip("Configure")
        header.addWidget(self.cfg_btn)
        
        self.save_btn = QPushButton("💾")
        self.save_btn.setStyleSheet(btn_style)
        self.save_btn.setToolTip("Save")
        header.addWidget(self.save_btn)
        
        header.addSpacing(20)
        
        self.close_btn = QPushButton("❌ إغلاق (ESC)")
        self.close_btn.setStyleSheet("QPushButton{background:#e74c3c; color:white; border:none; padding:10px 20px; font-weight:bold;} QPushButton:hover{background:#c0392b;}")
        self.close_btn.clicked.connect(self.close_signal.emit)
        header.addWidget(self.close_btn)
        
        layout.addLayout(header)
        
        # Plot area (2x2 for servos) - SIMPLIFIED for performance
        self.plot_container = QWidget()
        self.plot_layout = QGridLayout(self.plot_container)
        self.plot_layout.setSpacing(5)
        
        self.expanded_plots = []
        self.exp_cmd_curves = []
        self.exp_fb_curves = []
        
        names = ['Servo 1 (أمام يسار)', 'Servo 2 (أمام يمين)', 
                 'Servo 3 (خلف يسار)', 'Servo 4 (خلف يمين)']
        
        for i in range(4):
            pw = pg.PlotWidget(background='#1a1a1a')
            pw.setTitle(names[i], color='white', size='12pt')
            pw.showGrid(x=True, y=True, alpha=0.3)
            pw.getAxis('left').setPen('w')
            pw.getAxis('bottom').setPen('w')
            # No axis labels for more space
            pw.getAxis('left').setLabel('')
            pw.getAxis('bottom').setLabel('')
            
            # Simple curves without legend (legend causes slowdown)
            cmd = pw.plot(pen=pg.mkPen('#FF0000', width=2))
            fb = pw.plot(pen=pg.mkPen('#0088FF', width=2))
            
            self.expanded_plots.append(pw)
            self.exp_cmd_curves.append(cmd)
            self.exp_fb_curves.append(fb)
            
            self.plot_layout.addWidget(pw, i // 2, i % 2)
        
        for i in range(2):
            self.plot_layout.setRowStretch(i, 1)
            self.plot_layout.setColumnStretch(i, 1)
        
        layout.addWidget(self.plot_container, 1)
        
        # Bottom legend
        leg = QHBoxLayout()
        leg.addStretch()
        cmd_l = QLabel("━━ CMD (الأمر)")
        cmd_l.setStyleSheet("color: #FF0000; font-size: 12px; font-weight: bold;")
        leg.addWidget(cmd_l)
        leg.addSpacing(30)
        fb_l = QLabel("━━ FB (Feedback)")
        fb_l.setStyleSheet("color: #0088FF; font-size: 12px; font-weight: bold;")
        leg.addWidget(fb_l)
        leg.addStretch()
        layout.addLayout(leg)
    
    def _on_home(self):
        for pw in self.expanded_plots:
            pw.autoRange()
    
    def set_source(self, source_id, source_widget):
        self.source_id = source_id
        self.source_widget = source_widget
        self.title_lbl.setText(f"🎛️ SERVOS - تحليل الأوامر والاستجابة")
    
    def update_from_source(self):
        if self.source_widget is None:
            return
        src = self.source_widget
        if len(src.time_data) < 2:
            return
        # Use NumPy arrays for better performance with pyqtgraph
        t = np.array(src.time_data)
        for i in range(4):
            self.exp_cmd_curves[i].setData(t, np.array(src.cmd_data[i]))
            self.exp_fb_curves[i].setData(t, np.array(src.fb_data[i]))
    
    def keyPressEvent(self, e):
        if e.key() == Qt.Key_Escape:
            self.close_signal.emit()
        super().keyPressEvent(e)


# ===================== MAIN WINDOW =====================
class TelemetryViewer(QMainWindow):
    
    def __init__(self):
        super().__init__()
        self.setWindowTitle("CANphon Telemetry Viewer v3.0 - MATLAB Style")
        self.setGeometry(50, 50, 1400, 900)
        self.setStyleSheet("""
            QMainWindow { background-color: #0d0d0d; }
            QLabel { color: white; }
            QPushButton { 
                background-color: #3498db; color: white; 
                border: none; padding: 8px 16px; border-radius: 4px;
                font-weight: bold;
            }
            QPushButton:hover { background-color: #2980b9; }
            QComboBox { 
                background-color: #2d2d2d; color: white; 
                border: 1px solid #555; padding: 5px;
            }
        """)
        
        self.serial_port = None
        self.is_connected = False
        self.read_thread = None
        self.parser = FrameParser()
        self.recorded_data = []
        
        self.signals = SignalBridge()
        self.signals.new_frame.connect(self._on_frame)
        self.signals.status_update.connect(self._on_status)
        self.signals.raw_data.connect(self._on_raw)
        
        self._setup_ui()
        self._refresh_ports()
        
        # 60 FPS display update (balanced between speed and stability)
        self.timer = QTimer()
        self.timer.timeout.connect(self._update_plots)
        self.timer.start(16)  # 16ms = ~60 FPS
        
        # Throttle expanded view updates
        self.expanded_update_counter = 0
        
        # Frame queue for smooth continuous updates
        self.frame_queue = []
        
        self.term_count = 0
        self.pending_frame = None
    
    def _setup_ui(self):
        central = QWidget()
        self.setCentralWidget(central)
        main_layout = QVBoxLayout(central)
        main_layout.setSpacing(5)
        main_layout.setContentsMargins(5, 5, 5, 5)
        
        # Connection bar
        conn = QHBoxLayout()
        conn.addWidget(QLabel("📡 COM Port:"))
        self.port_cb = QComboBox()
        self.port_cb.setMinimumWidth(200)
        conn.addWidget(self.port_cb)
        
        conn.addWidget(QLabel("⚡ Baud:"))
        self.baud_cb = QComboBox()
        self.baud_cb.addItems(['115200', '230400', '460800', '921600'])
        conn.addWidget(self.baud_cb)
        
        self.refresh_btn = QPushButton("🔄 تحديث")
        self.refresh_btn.clicked.connect(self._refresh_ports)
        conn.addWidget(self.refresh_btn)
        
        self.conn_btn = QPushButton("🔌 اتصال")
        self.conn_btn.clicked.connect(self._toggle_conn)
        conn.addWidget(self.conn_btn)
        
        self.clear_btn = QPushButton("🗑 مسح")
        self.clear_btn.clicked.connect(self._clear_all)
        conn.addWidget(self.clear_btn)
        
        self.export_btn = QPushButton("💾 تصدير CSV")
        self.export_btn.clicked.connect(self._export_csv)
        conn.addWidget(self.export_btn)
        
        conn.addStretch()
        
        self.status_lbl = QLabel("⚪ غير متصل")
        self.status_lbl.setStyleSheet("color: #888; font-weight: bold;")
        conn.addWidget(self.status_lbl)
        
        self.fps_lbl = QLabel("● 0 fps")
        self.fps_lbl.setStyleSheet("color: #00ff88; font-weight: bold; font-size: 14px;")
        conn.addWidget(self.fps_lbl)
        
        main_layout.addLayout(conn)
        
        # Stacked widget
        self.stacked = QStackedWidget()
        
        # Page 0: Grid
        grid_page = QWidget()
        grid_layout = QHBoxLayout(grid_page)
        grid_layout.setContentsMargins(0, 0, 0, 0)
        
        splitter = QSplitter(Qt.Horizontal)
        
        # Terminal
        term_frame = QFrame()
        term_frame.setStyleSheet("background-color: #080808; border: 1px solid #333;")
        term_layout = QVBoxLayout(term_frame)
        term_layout.setContentsMargins(5, 5, 5, 5)
        
        QLabel("📟 Serial Terminal", term_frame).setStyleSheet("color: #00ff88; font-weight: bold;")
        
        self.terminal = QTextEdit()
        self.terminal.setReadOnly(True)
        self.terminal.setStyleSheet("background: #080808; color: #00ff00; font-family: Consolas; font-size: 10px; border: none;")
        term_layout.addWidget(self.terminal)
        
        clr_term = QPushButton("🗑 Clear Terminal")
        clr_term.clicked.connect(lambda: self.terminal.clear())
        term_layout.addWidget(clr_term)
        
        splitter.addWidget(term_frame)
        
        # Plots grid
        plots_widget = QWidget()
        plots_grid = QGridLayout(plots_widget)
        plots_grid.setSpacing(8)
        
        for i in range(3):
            plots_grid.setRowStretch(i, 1)
        for j in range(2):
            plots_grid.setColumnStretch(j, 1)
        
        self.orientation_box = PlotBox('orientation', '📐 ORIENTATION', ['Roll', 'Pitch', 'Yaw'], ['#ff6b6b', '#4ecdc4', '#45b7d1'])
        self.orientation_box.clicked.connect(self._expand)
        plots_grid.addWidget(self.orientation_box, 0, 0)
        
        self.servo_box = ServoBox()
        self.servo_box.clicked.connect(self._expand)
        plots_grid.addWidget(self.servo_box, 0, 1)
        
        self.accel_box = PlotBox('accel', '📊 ACCELEROMETER', ['X', 'Y', 'Z'], ['#ff9f43', '#26de81', '#a55eea'])
        self.accel_box.clicked.connect(self._expand)
        plots_grid.addWidget(self.accel_box, 1, 0)
        
        self.gps_box = PlotBox('gps', '🛰 GPS', ['Speed', 'Heading', 'Altitude'], ['#00b894', '#fdcb6e', '#e17055'])
        self.gps_box.clicked.connect(self._expand)
        plots_grid.addWidget(self.gps_box, 1, 1)
        
        self.battery_box = PlotBox('battery', '🔋 BATTERY & TEMP', ['Battery', 'Voltage', 'Temp'], ['#f1c40f', '#e67e22', '#e74c3c'])
        self.battery_box.clicked.connect(self._expand)
        plots_grid.addWidget(self.battery_box, 2, 0)
        
        self.tracking_box = PlotBox('tracking', '🎯 TRACKING', ['X', 'Y'], ['#00cec9', '#fd79a8'])
        self.tracking_box.clicked.connect(self._expand)
        plots_grid.addWidget(self.tracking_box, 2, 1)
        
        splitter.addWidget(plots_widget)
        splitter.setSizes([200, 1000])
        
        grid_layout.addWidget(splitter)
        self.stacked.addWidget(grid_page)
        
        # Page 1: Expanded
        self.expanded_view = ExpandedView()
        self.expanded_view.close_signal.connect(self._close_expanded)
        self.stacked.addWidget(self.expanded_view)
        
        main_layout.addWidget(self.stacked, 1)
        
        # Status bar
        status = QHBoxLayout()
        self.frame_lbl = QLabel("Frames: 0")
        self.frame_lbl.setStyleSheet("color: #666;")
        status.addWidget(self.frame_lbl)
        
        self.error_lbl = QLabel("Errors: 0")
        self.error_lbl.setStyleSheet("color: #ff6b6b;")
        status.addWidget(self.error_lbl)
        
        status.addStretch()
        
        self.data_lbl = QLabel("Roll: -- | Pitch: -- | Yaw: -- | Battery: --%")
        self.data_lbl.setStyleSheet("color: #00ff88; font-family: Consolas;")
        status.addWidget(self.data_lbl)
        
        main_layout.addLayout(status)
    
    def _expand(self, plot_id):
        if plot_id == 'servos':
            self.expanded_view.set_source(plot_id, self.servo_box)
            self.stacked.setCurrentIndex(1)
    
    def _close_expanded(self):
        self.stacked.setCurrentIndex(0)
    
    def _refresh_ports(self):
        self.port_cb.clear()
        for p in serial.tools.list_ports.comports():
            self.port_cb.addItem(f"{p.device} - {p.description}", p.device)
    
    def _toggle_conn(self):
        if self.is_connected:
            self._disconnect()
        else:
            self._connect()
    
    def _connect(self):
        if self.port_cb.currentIndex() < 0:
            return
        port = self.port_cb.currentData()
        baud = int(self.baud_cb.currentText())
        
        try:
            self.serial_port = serial.Serial(port, baud, timeout=0.1)
            self.is_connected = True
            self.conn_btn.setText("⛔ قطع الاتصال")
            self.conn_btn.setStyleSheet("background: #e74c3c; color: white; border: none; padding: 8px 16px; border-radius: 4px;")
            self.status_lbl.setText(f"🟢 {port} @ {baud}")
            self.status_lbl.setStyleSheet("color: #00ff88; font-weight: bold;")
            
            self.read_thread = threading.Thread(target=self._read_serial, daemon=True)
            self.read_thread.start()
        except Exception as e:
            self.status_lbl.setText(f"🔴 {e}")
    
    def _disconnect(self):
        self.is_connected = False
        if self.serial_port:
            self.serial_port.close()
            self.serial_port = None
        self.conn_btn.setText("🔌 اتصال")
        self.conn_btn.setStyleSheet("")
        self.status_lbl.setText("⚪ غير متصل")
        self.status_lbl.setStyleSheet("color: #888;")
    
    def _read_serial(self):
        last_t = time.time()
        fps = 0
        while self.is_connected and self.serial_port:
            try:
                # Read available data without blocking
                available = self.serial_port.in_waiting
                if available > 0:
                    data = self.serial_port.read(min(available, 256))
                    if data:
                        self.signals.raw_data.emit(data)
                        for f in self.parser.parse(data):
                            self.signals.new_frame.emit(f)
                            fps += 1
                else:
                    # Small sleep to prevent CPU spinning
                    time.sleep(0.001)  # 1ms
                
                now = time.time()
                if now - last_t >= 1.0:
                    self.signals.status_update.emit(f"● {fps} fps")
                    fps = 0
                    last_t = now
            except:
                break
    
    def _on_frame(self, f):
        # Queue frames for smooth processing
        self.frame_queue.append(f)
        self.recorded_data.append(f)
        self.frame_lbl.setText(f"Frames: {self.parser.frame_count}")
        self.error_lbl.setText(f"Errors: {self.parser.error_count}")
        self.data_lbl.setText(f"Roll: {f['roll']:.1f}° | Pitch: {f['pitch']:.1f}° | Yaw: {f['yaw']:.1f}° | Battery: {f['battery']}%")
    
    def _on_status(self, s):
        self.fps_lbl.setText(s)
    
    def _on_raw(self, data):
        # Skip terminal updates for performance (update every 20th)
        self.term_count += 1
        if self.term_count % 20 != 0:
            return
        h = ' '.join(f'{b:02X}' for b in data[:32])
        if len(data) > 32:
            h += f' ...+{len(data)-32}B'
        self.terminal.append(h)
    
    def _update_plots(self):
        # Process ALL queued frames for smooth continuous movement
        if not self.frame_queue:
            return
        
        # Process up to 5 frames per update cycle (prevents lag buildup)
        frames_to_process = self.frame_queue[:5]
        self.frame_queue = self.frame_queue[5:]
        
        for f in frames_to_process:
            t = f['timestamp'] / 1000.0
            
            # Add data points
            self.orientation_box.add_point(t, {'Roll': f['roll'], 'Pitch': f['pitch'], 'Yaw': f['yaw']})
            self.servo_box.add_point(t, f['servo_cmds'], f['servo_fb'])
            self.accel_box.add_point(t, {'X': f['accel_x'], 'Y': f['accel_y'], 'Z': f['accel_z']})
            self.gps_box.add_point(t, {'Speed': f['speed'], 'Heading': f['heading']/10, 'Altitude': f['gps_alt']})
            self.battery_box.add_point(t, {'Battery': f['battery'], 'Voltage': f['voltage']/100, 'Temp': f['temperature']})
            self.tracking_box.add_point(t, {'X': f['track_x'], 'Y': f['track_y']})
        
        # Redraw once after processing all frames
        self.orientation_box.redraw()
        self.servo_box.redraw()
        self.accel_box.redraw()
        self.gps_box.redraw()
        self.battery_box.redraw()
        self.tracking_box.redraw()
        
        # Throttle expanded view updates (every 3rd cycle) to prevent freeze
        if self.stacked.currentIndex() == 1:
            self.expanded_update_counter += 1
            if self.expanded_update_counter % 3 == 0:
                self.expanded_view.update_from_source()
    
    def _clear_all(self):
        self.recorded_data.clear()
        self.parser.frame_count = 0
        self.parser.error_count = 0
        self.orientation_box.clear_data()
        self.servo_box.clear_data()
        self.accel_box.clear_data()
        self.gps_box.clear_data()
        self.battery_box.clear_data()
        self.tracking_box.clear_data()
        self.frame_lbl.setText("Frames: 0")
        self.error_lbl.setText("Errors: 0")
    
    def _export_csv(self):
        if not self.recorded_data:
            QMessageBox.warning(self, "تحذير", "لا توجد بيانات")
            return
        fn, _ = QFileDialog.getSaveFileName(self, "حفظ", f"telemetry_{datetime.now().strftime('%Y%m%d_%H%M%S')}.csv", "CSV (*.csv)")
        if not fn:
            return
        try:
            with open(fn, 'w') as f:
                f.write("timestamp,roll,pitch,yaw,s1_cmd,s1_fb,s2_cmd,s2_fb,s3_cmd,s3_fb,s4_cmd,s4_fb,battery\n")
                for r in self.recorded_data:
                    f.write(f"{r['timestamp']},{r['roll']:.2f},{r['pitch']:.2f},{r['yaw']:.2f},")
                    for i in range(4):
                        f.write(f"{r['servo_cmds'][i]:.1f},{r['servo_fb'][i]:.1f},")
                    f.write(f"{r['battery']}\n")
            QMessageBox.information(self, "تم", f"تم تصدير {len(self.recorded_data)} إطار")
        except Exception as e:
            QMessageBox.warning(self, "خطأ", str(e))
    
    def keyPressEvent(self, e):
        if e.key() == Qt.Key_Escape and self.stacked.currentIndex() == 1:
            self._close_expanded()
        super().keyPressEvent(e)
    
    def closeEvent(self, e):
        self._disconnect()
        e.accept()


if __name__ == '__main__':
    QApplication.setAttribute(Qt.AA_EnableHighDpiScaling, True)
    QApplication.setAttribute(Qt.AA_UseHighDpiPixmaps, True)
    
    app = QApplication(sys.argv)
    app.setStyle('Fusion')
    
    win = TelemetryViewer()
    win.show()
    
    sys.exit(app.exec_())
