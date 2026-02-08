#!/usr/bin/env python3
"""
CANphon Telemetry Viewer v3.0 - MATLAB Style
=============================================
- 6 مربعات منظمة (Orientation, Servos, Accel, GPS, Battery, Tracking)
- اضغط على أي مربع لتكبيره ملء الشاشة
- ألوان واضحة: الأمر (أحمر) vs الاستجابة (أزرق)
- Cursor تفاعلي + Zoom + Pan
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

import matplotlib
matplotlib.use('Qt5Agg')
from matplotlib.backends.backend_qt5agg import FigureCanvasQTAgg as FigureCanvas
from matplotlib.backends.backend_qt5agg import NavigationToolbar2QT as NavigationToolbar
from matplotlib.figure import Figure
import matplotlib.pyplot as plt

import serial
import serial.tools.list_ports
import numpy as np


# ===================== PROTOCOL CONSTANTS =====================
HEADER_1 = 0xAA
HEADER_2 = 0x55
FRAME_SIZE = 73
BAUD_RATE = 115200  # يجب أن يتطابق مع Android


# ===================== SIGNAL BRIDGE =====================
class SignalBridge(QObject):
    new_frame = pyqtSignal(dict)
    status_update = pyqtSignal(str)
    raw_data = pyqtSignal(bytes)  # For terminal display


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
        except Exception as e:
            print(f"Parse error: {e}")
            return None


# ===================== CLICKABLE PLOT WIDGET =====================
class ClickablePlotWidget(QFrame):
    """مربع رسم بياني قابل للضغط للتكبير"""
    
    clicked = pyqtSignal(object)
    
    def __init__(self, title, labels, colors, parent=None, max_points=600):
        super().__init__(parent)
        self.title = title
        self.labels = labels
        self.colors = colors
        self.max_points = max_points
        self.is_expanded = False
        
        # Data storage
        self.time_data = deque(maxlen=max_points)
        self.data = {label: deque(maxlen=max_points) for label in labels}
        self.start_time = time.time()
        
        self.setup_ui()
    
    def setup_ui(self):
        self.setFrameStyle(QFrame.Box | QFrame.Raised)
        self.setLineWidth(2)
        self.setStyleSheet("""
            ClickablePlotWidget {
                background-color: #1e1e1e;
                border: 2px solid #444444;
                border-radius: 8px;
            }
            ClickablePlotWidget:hover {
                border: 2px solid #00aaff;
            }
        """)
        self.setCursor(QCursor(Qt.PointingHandCursor))
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Expanding)
        self.setMinimumSize(200, 150)
        
        layout = QVBoxLayout(self)
        layout.setContentsMargins(8, 8, 8, 8)
        layout.setSpacing(4)
        
        # Title - BIGGER AND BOLDER
        title_label = QLabel(f"🔍 {self.title}")
        title_label.setStyleSheet("color: #00ff88; font-weight: bold; font-size: 16px;")
        title_label.setAlignment(Qt.AlignCenter)
        layout.addWidget(title_label)
        
        # Figure - BIGGER
        self.fig = Figure(figsize=(6, 4), dpi=100)
        self.fig.patch.set_facecolor('#1e1e1e')
        self.canvas = FigureCanvas(self.fig)
        self.canvas.setStyleSheet("background-color: #1e1e1e;")
        self.canvas.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Expanding)
        
        self.ax = self.fig.add_subplot(111)
        self.setup_axis()
        
        layout.addWidget(self.canvas, 1)  # stretch factor 1
        
        # Hint
        hint = QLabel("اضغط للتكبير")
        hint.setStyleSheet("color: #888888; font-size: 10px;")
        hint.setAlignment(Qt.AlignCenter)
        layout.addWidget(hint)
    
    def setup_axis(self):
        self.ax.set_facecolor('#2d2d2d')
        self.ax.tick_params(colors='white', labelsize=9)
        self.ax.set_xlabel('Time (s)', color='#888888', fontsize=10)
        for spine in self.ax.spines.values():
            spine.set_color('#555555')
        self.ax.grid(True, alpha=0.3, linestyle='--')
        
        # Create lines
        self.lines = {}
        for label, color in zip(self.labels, self.colors):
            line, = self.ax.plot([], [], color=color, linewidth=1.5, label=label)
            self.lines[label] = line
        
        self.ax.legend(loc='upper right', fontsize=6, facecolor='#2d2d2d',
                      labelcolor='white', framealpha=0.9)
        
        # Cursor annotation (yellow box)
        self.cursor_annotation = self.ax.annotate(
            '', xy=(0, 0), xytext=(10, 10),
            textcoords='offset points',
            bbox=dict(boxstyle='round,pad=0.4', facecolor='#FFFF00', edgecolor='black', alpha=0.95),
            fontsize=7, color='black', visible=False, zorder=100
        )
        self.cursor_vline = self.ax.axvline(x=0, color='#FFFF00', linestyle='--', linewidth=1, visible=False, zorder=99)
        
        # Connect click event
        self.canvas.mpl_connect('button_press_event', self.on_plot_click)
        
        self.fig.tight_layout(pad=1.0)
    
    def on_plot_click(self, event):
        """عند الضغط على الرسم، أظهر المربع الأصفر مع القيم"""
        if event.inaxes != self.ax or event.xdata is None:
            return
        
        # Find nearest time point
        time_list = list(self.time_data)
        if not time_list:
            return
        
        # Find closest index
        x_click = event.xdata
        closest_idx = min(range(len(time_list)), key=lambda i: abs(time_list[i] - x_click))
        t = time_list[closest_idx]
        
        # Build annotation text
        text_lines = [f"t = {t:.2f}s"]
        for label in self.labels:
            if label in self.data and len(self.data[label]) > closest_idx:
                value = list(self.data[label])[closest_idx]
                text_lines.append(f"{label}: {value:.2f}")
        
        annotation_text = "\n".join(text_lines)
        
        # Get y position (use middle of visible data)
        all_y = []
        for label in self.labels:
            if label in self.data and len(self.data[label]) > closest_idx:
                all_y.append(list(self.data[label])[closest_idx])
        y_pos = sum(all_y) / len(all_y) if all_y else 0
        
        # Update annotation
        self.cursor_annotation.xy = (t, y_pos)
        self.cursor_annotation.set_text(annotation_text)
        self.cursor_annotation.set_visible(True)
        self.cursor_vline.set_xdata([t])
        self.cursor_vline.set_visible(True)
        
        self.canvas.draw_idle()
    
    def mousePressEvent(self, event):
        self.clicked.emit(self)
        super().mousePressEvent(event)
    
    def update_data(self, values: dict, timestamp: float = None):
        if timestamp is None:
            timestamp = time.time() - self.start_time
        
        self.time_data.append(timestamp)
        
        for label, value in values.items():
            if label in self.data:
                self.data[label].append(value)
        
        # Smart decimation for smooth display (max 200 points visible)
        time_list = list(self.time_data)
        max_display_points = 200
        step = max(1, len(time_list) // max_display_points)
        
        for label, line in self.lines.items():
            if label in self.data:
                y_data = list(self.data[label])
                if len(y_data) == len(time_list):
                    # Decimate for faster rendering
                    if step > 1:
                        display_t = time_list[::step] + [time_list[-1]]
                        display_y = y_data[::step] + [y_data[-1]]
                    else:
                        display_t = time_list
                        display_y = y_data
                    line.set_data(display_t, display_y)
        
        if time_list:
            window = 15 if self.is_expanded else 10
            self.ax.set_xlim(max(0, time_list[-1] - window), time_list[-1] + 0.5)
            all_data = [v for label in self.data for v in self.data[label]]
            if all_data:
                margin = (max(all_data) - min(all_data)) * 0.15 + 0.5
                self.ax.set_ylim(min(all_data) - margin, max(all_data) + margin)
        
        # Don't draw here - will be called once after all updates
    
    def redraw(self):
        """رسم الـ canvas - يُستدعى مرة واحدة بعد التحديثات"""
        self.canvas.draw_idle()
    
    def clear_data(self):
        self.time_data.clear()
        for label in self.labels:
            self.data[label].clear()
        self.start_time = time.time()
        for line in self.lines.values():
            line.set_data([], [])
        self.canvas.draw()


# ===================== SERVO GROUP WIDGET (4 in 1) =====================
class ServoGroupWidget(QFrame):
    """مربع واحد يحتوي على 4 رسوم بيانية للسيرفوهات (2x2)"""
    
    clicked = pyqtSignal(object)
    
    SERVO_NAMES = ['Servo 1 (أمام يسار)', 'Servo 2 (أمام يمين)', 
                   'Servo 3 (خلف يسار)', 'Servo 4 (خلف يمين)']
    
    def __init__(self, parent=None, max_points=600):
        super().__init__(parent)
        self.title = "🎛️ SERVOS (Cmd vs Fb)"
        self.max_points = max_points
        self.is_expanded = False
        
        # Data storage for all 4 servos
        self.start_time = time.time()
        self.time_data = deque(maxlen=max_points)
        self.servo_data = {
            i: {'CMD': deque(maxlen=max_points), 'FB': deque(maxlen=max_points)}
            for i in range(4)
        }
        
        self.setup_ui()
    
    def setup_ui(self):
        self.setFrameStyle(QFrame.Box | QFrame.Raised)
        self.setLineWidth(2)
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
        self.setCursor(QCursor(Qt.PointingHandCursor))
        self.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Expanding)
        self.setMinimumSize(300, 250)
        
        layout = QVBoxLayout(self)
        layout.setContentsMargins(8, 8, 8, 8)
        layout.setSpacing(4)
        
        # Title - BIGGER AND BOLDER
        title_label = QLabel(f"🔍 {self.title}")
        title_label.setStyleSheet("color: #00ff88; font-weight: bold; font-size: 16px;")
        title_label.setAlignment(Qt.AlignCenter)
        layout.addWidget(title_label)
        
        # Create 2x2 grid of mini plots - BIGGER
        self.fig = Figure(figsize=(8, 6), dpi=100)
        self.fig.patch.set_facecolor('#1e1e1e')
        self.canvas = FigureCanvas(self.fig)
        self.canvas.setStyleSheet("background-color: #1e1e1e;")
        self.canvas.setSizePolicy(QSizePolicy.Expanding, QSizePolicy.Expanding)
        
        self.axes = []
        self.lines = []
        self.cursor_annotations = []
        self.cursor_vlines = []
        
        for i in range(4):
            ax = self.fig.add_subplot(2, 2, i + 1)
            ax.set_facecolor('#2d2d2d')
            ax.tick_params(colors='white', labelsize=8)
            ax.set_title(self.SERVO_NAMES[i], color='white', fontsize=10, pad=4)
            for spine in ax.spines.values():
                spine.set_color('#555555')
            ax.grid(True, alpha=0.3, linestyle='--')
            
            # FB (Blue) first, then CMD (Red) on top - like OLD project!
            line_fb, = ax.plot([], [], color='#0072BD', linewidth=2, label='FB', zorder=1)
            line_cmd, = ax.plot([], [], color='#FF0000', linewidth=2, label='CMD', zorder=2)
            
            if i == 0:  # Legend only on first subplot
                ax.legend(loc='upper right', fontsize=7, facecolor='#2d2d2d',
                         labelcolor='white', framealpha=0.8)
            
            # Cursor annotation for this subplot
            cursor_ann = ax.annotate(
                '', xy=(0, 0), xytext=(8, 8),
                textcoords='offset points',
                bbox=dict(boxstyle='round,pad=0.3', facecolor='#FFFF00', edgecolor='black', alpha=0.95),
                fontsize=6, color='black', visible=False, zorder=100
            )
            cursor_vl = ax.axvline(x=0, color='#FFFF00', linestyle='--', linewidth=1, visible=False, zorder=99)
            
            self.axes.append(ax)
            self.lines.append({'CMD': line_cmd, 'FB': line_fb})
            self.cursor_annotations.append(cursor_ann)
            self.cursor_vlines.append(cursor_vl)
        
        # Connect click event
        self.canvas.mpl_connect('button_press_event', self.on_servo_plot_click)
        
        self.fig.tight_layout(pad=1.5)
        layout.addWidget(self.canvas)
        
        # Legend at bottom
        legend_layout = QHBoxLayout()
        legend_layout.addStretch()
        
        cmd_label = QLabel("━━ CMD (الأمر)")
        cmd_label.setStyleSheet("color: #FF0000; font-size: 10px; font-weight: bold;")
        legend_layout.addWidget(cmd_label)
        
        legend_layout.addSpacing(20)
        
        fb_label = QLabel("━━ FB (Feedback)")
        fb_label.setStyleSheet("color: #0080FF; font-size: 10px; font-weight: bold;")
        legend_layout.addWidget(fb_label)
        
        legend_layout.addStretch()
        layout.addLayout(legend_layout)
        
        # Hint
        hint = QLabel("اضغط للتكبير")
        hint.setStyleSheet("color: #666666; font-size: 9px;")
        hint.setAlignment(Qt.AlignCenter)
        layout.addWidget(hint)
    
    def mousePressEvent(self, event):
        self.clicked.emit(self)
        super().mousePressEvent(event)
    
    def on_servo_plot_click(self, event):
        """عند الضغط على رسم السيرفو، أظهر المربع الأصفر"""
        if event.xdata is None:
            return
        
        # Find which subplot was clicked
        clicked_ax_idx = None
        for i, ax in enumerate(self.axes):
            if event.inaxes == ax:
                clicked_ax_idx = i
                break
        
        if clicked_ax_idx is None:
            return
        
        # Find nearest time point
        time_list = list(self.time_data)
        if not time_list:
            return
        
        x_click = event.xdata
        closest_idx = min(range(len(time_list)), key=lambda i: abs(time_list[i] - x_click))
        t = time_list[closest_idx]
        
        # Get CMD and FB values at this time
        cmd_val = list(self.servo_data[clicked_ax_idx]['CMD'])[closest_idx] if len(self.servo_data[clicked_ax_idx]['CMD']) > closest_idx else 0
        fb_val = list(self.servo_data[clicked_ax_idx]['FB'])[closest_idx] if len(self.servo_data[clicked_ax_idx]['FB']) > closest_idx else 0
        
        # Build annotation text
        annotation_text = f"t = {t:.2f}s\nCMD: {cmd_val:.1f}°\nFB: {fb_val:.1f}°"
        
        # Hide all annotations first
        for ann in self.cursor_annotations:
            ann.set_visible(False)
        for vl in self.cursor_vlines:
            vl.set_visible(False)
        
        # Show annotation on clicked subplot
        y_pos = (cmd_val + fb_val) / 2
        self.cursor_annotations[clicked_ax_idx].xy = (t, y_pos)
        self.cursor_annotations[clicked_ax_idx].set_text(annotation_text)
        self.cursor_annotations[clicked_ax_idx].set_visible(True)
        self.cursor_vlines[clicked_ax_idx].set_xdata([t])
        self.cursor_vlines[clicked_ax_idx].set_visible(True)
        
        self.canvas.draw_idle()
    
    def update_data(self, servo_cmds: list, servo_fbs: list, timestamp: float = None):
        """Update all 4 servos at once"""
        if timestamp is None:
            timestamp = time.time() - self.start_time
        
        self.time_data.append(timestamp)
        
        for i in range(4):
            self.servo_data[i]['CMD'].append(servo_cmds[i])
            self.servo_data[i]['FB'].append(servo_fbs[i])
        
        # Smart decimation for smooth display (max 200 points visible)
        time_list = list(self.time_data)
        window = 15 if self.is_expanded else 10
        max_display_points = 200
        step = max(1, len(time_list) // max_display_points)
        
        for i, ax in enumerate(self.axes):
            cmd_data = list(self.servo_data[i]['CMD'])
            fb_data = list(self.servo_data[i]['FB'])
            
            if len(cmd_data) == len(time_list):
                # Decimate for faster rendering
                if step > 1:
                    display_t = time_list[::step] + [time_list[-1]]
                    display_cmd = cmd_data[::step] + [cmd_data[-1]]
                    display_fb = fb_data[::step] + [fb_data[-1]]
                else:
                    display_t = time_list
                    display_cmd = cmd_data
                    display_fb = fb_data
                
                self.lines[i]['CMD'].set_data(display_t, display_cmd)
                self.lines[i]['FB'].set_data(display_t, display_fb)
            
            if time_list:
                ax.set_xlim(max(0, time_list[-1] - window), time_list[-1] + 0.5)
                all_data = cmd_data + fb_data
                if all_data:
                    margin = (max(all_data) - min(all_data)) * 0.15 + 1
                    ax.set_ylim(min(all_data) - margin, max(all_data) + margin)
        
        # Don't draw here - will be called once after all updates
    
    def redraw(self):
        """رسم الـ canvas - يُستدعى مرة واحدة بعد التحديثات"""
        self.canvas.draw_idle()
    
    def clear_data(self):
        self.time_data.clear()
        for i in range(4):
            self.servo_data[i]['CMD'].clear()
            self.servo_data[i]['FB'].clear()
        self.start_time = time.time()
        
        for lines in self.lines:
            lines['CMD'].set_data([], [])
            lines['FB'].set_data([], [])
        self.canvas.draw()

# ===================== EXPANDED VIEW =====================
class ExpandedPlotView(QWidget):
    """عرض مكبر لرسم بياني واحد"""
    
    close_requested = pyqtSignal()
    
    def __init__(self, parent=None):
        super().__init__(parent)
        self.current_widget = None
        self.setup_ui()
    
    def setup_ui(self):
        self.setStyleSheet("background-color: #1a1a1a;")
        layout = QVBoxLayout(self)
        layout.setContentsMargins(10, 10, 10, 10)
        
        # Header with close button
        header = QHBoxLayout()
        
        self.title_label = QLabel("📊 الرسم البياني")
        self.title_label.setStyleSheet("color: #00ff88; font-size: 18px; font-weight: bold;")
        header.addWidget(self.title_label)
        
        header.addStretch()
        
        close_btn = QPushButton("❌ إغلاق (ESC)")
        close_btn.setStyleSheet("""
            QPushButton {
                background-color: #cc3333;
                color: white;
                border: none;
                padding: 10px 20px;
                border-radius: 5px;
                font-weight: bold;
            }
            QPushButton:hover {
                background-color: #ff4444;
            }
        """)
        close_btn.clicked.connect(self.close_requested.emit)
        header.addWidget(close_btn)
        
        layout.addLayout(header)
        
        # Main figure
        self.fig = Figure(figsize=(12, 7), dpi=100)
        self.fig.patch.set_facecolor('#1a1a1a')
        self.canvas = FigureCanvas(self.fig)
        
        self.ax = self.fig.add_subplot(111)
        self.ax.set_facecolor('#252525')
        self.ax.tick_params(colors='white', labelsize=10)
        self.ax.set_xlabel('Time (s)', color='white', fontsize=12)
        self.ax.set_ylabel('Value', color='white', fontsize=12)
        for spine in self.ax.spines.values():
            spine.set_color('#666666')
        self.ax.grid(True, alpha=0.4, linestyle='--')
        
        # Toolbar
        self.toolbar = NavigationToolbar(self.canvas, self)
        self.toolbar.setStyleSheet("background-color: #2d2d2d;")
        
        layout.addWidget(self.toolbar)
        layout.addWidget(self.canvas)
        
        # Cursor annotation
        self.cursor_annotation = self.ax.annotate(
            '', xy=(0, 0), xytext=(15, 15),
            textcoords='offset points',
            bbox=dict(boxstyle='round,pad=0.5', facecolor='#ffff00', edgecolor='black'),
            fontsize=10, visible=False
        )
        self.cursor_vline = self.ax.axvline(x=0, color='#ffff00', linestyle='--', linewidth=1, visible=False)
        
        self.canvas.mpl_connect('button_press_event', self.on_click)
        
        self.lines = {}
    
    def on_click(self, event):
        if event.inaxes != self.ax or self.current_widget is None:
            return
        
        x = event.xdata
        if x is None:
            return
        
        # Find closest time point
        time_list = list(self.current_widget.time_data)
        if not time_list:
            return
        
        closest_idx = min(range(len(time_list)), key=lambda i: abs(time_list[i] - x))
        t = time_list[closest_idx]
        
        # Build annotation with all values
        text_lines = [f"t = {t:.2f}s"]
        all_y = []
        for label in self.current_widget.labels:
            if label in self.current_widget.data and len(self.current_widget.data[label]) > closest_idx:
                value = list(self.current_widget.data[label])[closest_idx]
                text_lines.append(f"{label}: {value:.2f}")
                all_y.append(value)
        
        y_pos = sum(all_y) / len(all_y) if all_y else 0
        
        self.cursor_annotation.xy = (t, y_pos)
        self.cursor_annotation.set_text("\n".join(text_lines))
        self.cursor_annotation.set_visible(True)
        self.cursor_vline.set_xdata([t])
        self.cursor_vline.set_visible(True)
        self.canvas.draw_idle()
    
    def load_from_widget(self, widget: ClickablePlotWidget):
        self.current_widget = widget
        self.title_label.setText(f"📊 {widget.title}")
        
        self.ax.clear()
        self.ax.set_facecolor('#252525')
        self.ax.tick_params(colors='white', labelsize=10)
        self.ax.set_xlabel('Time (s)', color='white', fontsize=12)
        for spine in self.ax.spines.values():
            spine.set_color('#666666')
        self.ax.grid(True, alpha=0.4, linestyle='--')
        
        self.lines = {}
        time_list = list(widget.time_data)
        
        for label, color in zip(widget.labels, widget.colors):
            y_data = list(widget.data[label])
            if len(y_data) == len(time_list):
                line, = self.ax.plot(time_list, y_data, color=color, linewidth=2, label=label)
                self.lines[label] = line
        
        self.ax.legend(loc='upper right', fontsize=10, facecolor='#2d2d2d',
                      labelcolor='white', framealpha=0.9)
        
        if time_list:
            self.ax.set_xlim(time_list[0], time_list[-1])
            all_data = [v for label in widget.data for v in widget.data[label]]
            if all_data:
                margin = (max(all_data) - min(all_data)) * 0.15 + 0.5
                self.ax.set_ylim(min(all_data) - margin, max(all_data) + margin)
        
        self.fig.tight_layout()
        self.canvas.draw()
    
    def update_from_widget(self):
        if self.current_widget is None:
            return
        
        widget = self.current_widget
        time_list = list(widget.time_data)
        
        for label in widget.labels:
            if label in self.lines and label in widget.data:
                y_data = list(widget.data[label])
                if len(y_data) == len(time_list):
                    self.lines[label].set_data(time_list, y_data)
        
        if time_list:
            self.ax.set_xlim(max(0, time_list[-1] - 20), time_list[-1] + 0.5)
            all_data = [v for label in widget.data for v in widget.data[label]]
            if all_data:
                margin = (max(all_data) - min(all_data)) * 0.15 + 0.5
                self.ax.set_ylim(min(all_data) - margin, max(all_data) + margin)
        
        self.canvas.draw_idle()
    
    def keyPressEvent(self, event):
        if event.key() == Qt.Key_Escape:
            self.close_requested.emit()
        super().keyPressEvent(event)


# ===================== EXPANDED SERVO VIEW (4 servos full screen) =====================
class ExpandedServoView(QWidget):
    """عرض مكبر للسيرفوهات الأربعة"""
    
    close_requested = pyqtSignal()
    
    SERVO_NAMES = ['Servo 1 (أمام يسار)', 'Servo 2 (أمام يمين)', 
                   'Servo 3 (خلف يسار)', 'Servo 4 (خلف يمين)']
    
    def __init__(self, parent=None):
        super().__init__(parent)
        self.current_widget = None
        self.cursor_annotations = []
        self.cursor_vlines = []
        self.setup_ui()
    
    def setup_ui(self):
        self.setStyleSheet("background-color: #1a1a1a;")
        layout = QVBoxLayout(self)
        layout.setContentsMargins(10, 10, 10, 10)
        
        # Header
        header = QHBoxLayout()
        
        self.title_label = QLabel("🎛️ SERVOS - تحليل الأوامر والاستجابة")
        self.title_label.setStyleSheet("color: #00ff88; font-size: 18px; font-weight: bold;")
        header.addWidget(self.title_label)
        
        header.addStretch()
        
        close_btn = QPushButton("❌ إغلاق (ESC)")
        close_btn.setStyleSheet("""
            QPushButton {
                background-color: #cc3333;
                color: white;
                border: none;
                padding: 10px 20px;
                border-radius: 5px;
                font-weight: bold;
            }
            QPushButton:hover {
                background-color: #ff4444;
            }
        """)
        close_btn.clicked.connect(self.close_requested.emit)
        header.addWidget(close_btn)
        
        layout.addLayout(header)
        
        # 2x2 Grid of large plots
        self.fig = Figure(figsize=(14, 10), dpi=100)
        self.fig.patch.set_facecolor('#1a1a1a')
        self.canvas = FigureCanvas(self.fig)
        
        self.axes = []
        self.lines = []
        
        for i in range(4):
            ax = self.fig.add_subplot(2, 2, i + 1)
            ax.set_facecolor('#252525')
            ax.tick_params(colors='white', labelsize=9)
            ax.set_title(self.SERVO_NAMES[i], color='white', fontsize=12, pad=8)
            ax.set_xlabel('Time (s)', color='#888888', fontsize=10)
            ax.set_ylabel('Degrees (°)', color='#888888', fontsize=10)
            for spine in ax.spines.values():
                spine.set_color('#666666')
            ax.grid(True, alpha=0.4, linestyle='--')
            
            # FB (Blue) first, then CMD (Red) on top - like OLD project!
            line_fb, = ax.plot([], [], color='#0080FF', linewidth=2, label='FB (Feedback)', zorder=1)
            line_cmd, = ax.plot([], [], color='#FF0000', linewidth=2, label='CMD (الأمر)', zorder=2)
            ax.legend(loc='upper right', fontsize=9, facecolor='#2d2d2d',
                     labelcolor='white', framealpha=0.9)
            
            # Cursor annotation for each subplot
            cursor_ann = ax.annotate(
                '', xy=(0, 0), xytext=(10, 10),
                textcoords='offset points',
                bbox=dict(boxstyle='round,pad=0.4', facecolor='#FFFF00', edgecolor='black', alpha=0.95),
                fontsize=9, color='black', visible=False, zorder=100
            )
            cursor_vl = ax.axvline(x=0, color='#FFFF00', linestyle='--', linewidth=1.5, visible=False, zorder=99)
            
            self.axes.append(ax)
            self.lines.append({'CMD': line_cmd, 'FB': line_fb})
            self.cursor_annotations.append(cursor_ann)
            self.cursor_vlines.append(cursor_vl)
        
        self.fig.tight_layout(pad=2.0)
        
        # Connect click event
        self.canvas.mpl_connect('button_press_event', self.on_servo_click)
        
        # Toolbar
        self.toolbar = NavigationToolbar(self.canvas, self)
        self.toolbar.setStyleSheet("background-color: #2d2d2d;")
        
        layout.addWidget(self.toolbar)
        layout.addWidget(self.canvas)
        
        # Legend at bottom
        legend_layout = QHBoxLayout()
        legend_layout.addStretch()
        
        cmd_label = QLabel("━━━ CMD (الأمر)")
        cmd_label.setStyleSheet("color: #FF0000; font-size: 14px; font-weight: bold;")
        legend_layout.addWidget(cmd_label)
        
        legend_layout.addSpacing(40)
        
        fb_label = QLabel("━━━ FB (Feedback)")
        fb_label.setStyleSheet("color: #0080FF; font-size: 14px; font-weight: bold;")
        legend_layout.addWidget(fb_label)
        
        legend_layout.addStretch()
        layout.addLayout(legend_layout)
    
    def on_servo_click(self, event):
        """عند الضغط على رسم السيرفو المكبر"""
        if event.xdata is None or self.current_widget is None:
            return
        
        # Find which subplot was clicked
        clicked_ax_idx = None
        for i, ax in enumerate(self.axes):
            if event.inaxes == ax:
                clicked_ax_idx = i
                break
        
        if clicked_ax_idx is None:
            return
        
        # Find nearest time point
        time_list = list(self.current_widget.time_data)
        if not time_list:
            return
        
        x_click = event.xdata
        closest_idx = min(range(len(time_list)), key=lambda i: abs(time_list[i] - x_click))
        t = time_list[closest_idx]
        
        # Get CMD and FB values
        cmd_val = list(self.current_widget.servo_data[clicked_ax_idx]['CMD'])[closest_idx] if len(self.current_widget.servo_data[clicked_ax_idx]['CMD']) > closest_idx else 0
        fb_val = list(self.current_widget.servo_data[clicked_ax_idx]['FB'])[closest_idx] if len(self.current_widget.servo_data[clicked_ax_idx]['FB']) > closest_idx else 0
        
        annotation_text = f"t = {t:.2f}s\nCMD: {cmd_val:.1f}°\nFB: {fb_val:.1f}°"
        
        # Hide all annotations first
        for ann in self.cursor_annotations:
            ann.set_visible(False)
        for vl in self.cursor_vlines:
            vl.set_visible(False)
        
        # Show on clicked subplot
        y_pos = (cmd_val + fb_val) / 2
        self.cursor_annotations[clicked_ax_idx].xy = (t, y_pos)
        self.cursor_annotations[clicked_ax_idx].set_text(annotation_text)
        self.cursor_annotations[clicked_ax_idx].set_visible(True)
        self.cursor_vlines[clicked_ax_idx].set_xdata([t])
        self.cursor_vlines[clicked_ax_idx].set_visible(True)
        
        self.canvas.draw_idle()
    
    def load_from_servo_group(self, widget):
        self.current_widget = widget
        
        time_list = list(widget.time_data)
        
        for i in range(4):
            cmd_data = list(widget.servo_data[i]['CMD'])
            fb_data = list(widget.servo_data[i]['FB'])
            
            if len(cmd_data) == len(time_list):
                self.lines[i]['CMD'].set_data(time_list, cmd_data)
                self.lines[i]['FB'].set_data(time_list, fb_data)
            
            if time_list:
                self.axes[i].set_xlim(time_list[0], time_list[-1])
                all_data = cmd_data + fb_data
                if all_data:
                    margin = (max(all_data) - min(all_data)) * 0.15 + 1
                    self.axes[i].set_ylim(min(all_data) - margin, max(all_data) + margin)
        
        self.canvas.draw()
    
    def update_from_servo_group(self):
        if self.current_widget is None:
            return
        
        widget = self.current_widget
        time_list = list(widget.time_data)
        
        for i in range(4):
            cmd_data = list(widget.servo_data[i]['CMD'])
            fb_data = list(widget.servo_data[i]['FB'])
            
            if len(cmd_data) == len(time_list):
                self.lines[i]['CMD'].set_data(time_list, cmd_data)
                self.lines[i]['FB'].set_data(time_list, fb_data)
            
            if time_list:
                self.axes[i].set_xlim(max(0, time_list[-1] - 20), time_list[-1] + 0.5)
                all_data = cmd_data + fb_data
                if all_data:
                    margin = (max(all_data) - min(all_data)) * 0.15 + 1
                    self.axes[i].set_ylim(min(all_data) - margin, max(all_data) + margin)
        
        self.canvas.draw_idle()
    
    def keyPressEvent(self, event):
        if event.key() == Qt.Key_Escape:
            self.close_requested.emit()
        super().keyPressEvent(event)


# ===================== MAIN WINDOW =====================
class TelemetryViewerV3(QMainWindow):
    
    def __init__(self):
        super().__init__()
        self.setWindowTitle("CANphon Telemetry Viewer v3.0 - MATLAB Style")
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
        
        self.update_timer = QTimer()
        self.update_timer.timeout.connect(self.update_plots)
        self.update_timer.start(16)  # 60 Hz - الأمثل لـ matplotlib
        
        self.pending_frame = None
    
    def setup_ui(self):
        central = QWidget()
        self.setCentralWidget(central)
        main_layout = QVBoxLayout(central)
        main_layout.setSpacing(8)
        
        # ===== Connection Bar =====
        conn_layout = QHBoxLayout()
        
        conn_layout.addWidget(QLabel("📡 COM Port:"))
        self.port_combo = QComboBox()
        self.port_combo.setMinimumWidth(180)
        conn_layout.addWidget(self.port_combo)
        
        # Baud Rate Selector
        conn_layout.addWidget(QLabel("⚡ Baud:"))
        self.baud_combo = QComboBox()
        self.baud_combo.setMinimumWidth(100)
        baud_rates = ['9600', '19200', '38400', '57600', '115200', '230400', '460800', '921600', '1000000', '2000000']
        self.baud_combo.addItems(baud_rates)
        self.baud_combo.setCurrentText('115200')  # Default
        self.baud_combo.setStyleSheet("""
            QComboBox {
                background-color: #2d2d2d;
                color: #00ff88;
                border: 1px solid #00aaff;
                padding: 5px;
                font-weight: bold;
            }
        """)
        conn_layout.addWidget(self.baud_combo)
        
        self.refresh_btn = QPushButton("🔄 تحديث")
        self.refresh_btn.clicked.connect(self.refresh_ports)
        conn_layout.addWidget(self.refresh_btn)
        
        self.connect_btn = QPushButton("🔌 اتصال")
        self.connect_btn.clicked.connect(self.toggle_connection)
        conn_layout.addWidget(self.connect_btn)
        
        conn_layout.addSpacing(20)
        
        self.clear_btn = QPushButton("🗑 مسح")
        self.clear_btn.clicked.connect(self.clear_all)
        conn_layout.addWidget(self.clear_btn)
        
        self.export_btn = QPushButton("💾 تصدير CSV")
        self.export_btn.clicked.connect(self.export_csv)
        conn_layout.addWidget(self.export_btn)
        
        conn_layout.addStretch()
        
        self.status_label = QLabel("⚪ غير متصل")
        self.status_label.setStyleSheet("color: #888888; font-weight: bold; font-size: 12px;")
        conn_layout.addWidget(self.status_label)
        
        self.fps_label = QLabel("0 fps")
        self.fps_label.setStyleSheet("color: #00ff88; font-weight: bold; font-size: 12px;")
        conn_layout.addWidget(self.fps_label)
        
        main_layout.addLayout(conn_layout)
        
        # ===== Main Content: Terminal (left) + Plots (right) =====
        main_splitter = QSplitter(Qt.Horizontal)
        
        # ----- Serial Terminal (Left Side) -----
        terminal_frame = QFrame()
        terminal_frame.setStyleSheet("""
            QFrame {
                background-color: #0a0a0a;
                border: 2px solid #333333;
                border-radius: 8px;
            }
        """)
        terminal_layout = QVBoxLayout(terminal_frame)
        terminal_layout.setContentsMargins(5, 5, 5, 5)
        
        # Terminal title
        terminal_title = QLabel("📟 Serial Terminal")
        terminal_title.setStyleSheet("color: #00ff88; font-weight: bold; font-size: 12px; border: none;")
        terminal_layout.addWidget(terminal_title)
        
        # Terminal text area
        self.terminal_text = QTextEdit()
        self.terminal_text.setReadOnly(True)
        self.terminal_text.setStyleSheet("""
            QTextEdit {
                background-color: #0a0a0a;
                color: #00ff00;
                font-family: Consolas, 'Courier New', monospace;
                font-size: 10px;
                border: none;
            }
        """)
        self.terminal_text.setLineWrapMode(QTextEdit.NoWrap)
        terminal_layout.addWidget(self.terminal_text)
        
        # Clear terminal button
        clear_terminal_btn = QPushButton("🗑 Clear Terminal")
        clear_terminal_btn.setStyleSheet("""
            QPushButton {
                background-color: #2196F3;
                color: white;
                border: none;
                padding: 8px;
                border-radius: 4px;
                font-weight: bold;
            }
            QPushButton:hover {
                background-color: #1976D2;
            }
        """)
        clear_terminal_btn.clicked.connect(self.clear_terminal)
        terminal_layout.addWidget(clear_terminal_btn)
        
        main_splitter.addWidget(terminal_frame)
        
        # ----- Plots (Right Side) -----
        plots_container = QWidget()
        plots_layout = QVBoxLayout(plots_container)
        plots_layout.setContentsMargins(0, 0, 0, 0)
        
        # Stacked Widget (Grid vs Expanded)
        self.stacked = QStackedWidget()
        
        # Grid View
        self.grid_widget = QWidget()
        grid_layout = QGridLayout(self.grid_widget)
        grid_layout.setSpacing(10)
        grid_layout.setContentsMargins(5, 5, 5, 5)
        
        # Make rows and columns stretch equally
        for row in range(3):
            grid_layout.setRowStretch(row, 1)
        for col in range(2):
            grid_layout.setColumnStretch(col, 1)
        
        # Create plot widgets
        # Row 0: Orientation + Servos (4-in-1)
        self.orientation_plot = ClickablePlotWidget(
            "📐 ORIENTATION", 
            ['Roll', 'Pitch', 'Yaw'],
            ['#ff6b6b', '#4ecdc4', '#45b7d1']
        )
        self.orientation_plot.clicked.connect(self.expand_plot)
        grid_layout.addWidget(self.orientation_plot, 0, 0)
        
        # Servo Group (4 servos in one box)
        self.servo_group = ServoGroupWidget()
        self.servo_group.clicked.connect(self.expand_servo_group)
        grid_layout.addWidget(self.servo_group, 0, 1)
        
        # Row 1: Accelerometer + GPS
        self.accel_plot = ClickablePlotWidget(
            "📊 ACCELEROMETER",
            ['X', 'Y', 'Z'],
            ['#ff9f43', '#26de81', '#a55eea']
        )
        self.accel_plot.clicked.connect(self.expand_plot)
        grid_layout.addWidget(self.accel_plot, 1, 0)
        
        self.gps_plot = ClickablePlotWidget(
            "📡 GPS",
            ['Speed', 'Heading', 'Altitude'],
            ['#00ff88', '#ff6b9d', '#ffeaa7']
        )
        self.gps_plot.clicked.connect(self.expand_plot)
        grid_layout.addWidget(self.gps_plot, 1, 1)
        
        # Row 2: Battery + Tracking
        self.battery_plot = ClickablePlotWidget(
            "🔋 BATTERY & TEMP",
            ['Battery%', 'Voltage/10', 'Temp'],
            ['#f1c40f', '#e67e22', '#e74c3c']
        )
        self.battery_plot.clicked.connect(self.expand_plot)
        grid_layout.addWidget(self.battery_plot, 2, 0)
        
        self.tracking_plot = ClickablePlotWidget(
            "🎯 TRACKING",
            ['Target_X', 'Target_Y'],
            ['#00ff00', '#ff00ff']
        )
        self.tracking_plot.clicked.connect(self.expand_plot)
        grid_layout.addWidget(self.tracking_plot, 2, 1)
        
        self.stacked.addWidget(self.grid_widget)
        
        # Expanded View
        self.expanded_view = ExpandedPlotView()
        self.expanded_view.close_requested.connect(self.collapse_plot)
        self.stacked.addWidget(self.expanded_view)
        
        # Expanded Servo View (special for 4 servos)
        self.expanded_servo_view = ExpandedServoView()
        self.expanded_servo_view.close_requested.connect(self.collapse_plot)
        self.stacked.addWidget(self.expanded_servo_view)
        
        plots_layout.addWidget(self.stacked)
        main_splitter.addWidget(plots_container)
        
        # Set splitter sizes (Terminal: 25%, Plots: 75%)
        main_splitter.setSizes([300, 900])
        main_splitter.setStretchFactor(0, 1)
        main_splitter.setStretchFactor(1, 3)
        
        main_layout.addWidget(main_splitter)
        
        # ===== Status Bar =====
        status_layout = QHBoxLayout()
        
        self.frame_label = QLabel("Frames: 0")
        self.frame_label.setStyleSheet("color: #888888;")
        status_layout.addWidget(self.frame_label)
        
        self.error_label = QLabel("Errors: 0")
        self.error_label.setStyleSheet("color: #888888;")
        status_layout.addWidget(self.error_label)
        
        status_layout.addStretch()
        
        self.data_label = QLabel("Roll: -- | Pitch: -- | Yaw: -- | Battery: --%")
        self.data_label.setStyleSheet("color: #00ff88; font-family: Consolas; font-size: 11px;")
        status_layout.addWidget(self.data_label)
        
        main_layout.addLayout(status_layout)
    
    def expand_plot(self, widget):
        self.expanded_view.load_from_widget(widget)
        self.stacked.setCurrentIndex(1)
        self.expanded_view.setFocus()
    
    def expand_servo_group(self, widget):
        self.expanded_servo_view.load_from_servo_group(widget)
        self.stacked.setCurrentIndex(2)
        self.expanded_servo_view.setFocus()
    
    def collapse_plot(self):
        self.stacked.setCurrentIndex(0)
    
    def keyPressEvent(self, event):
        if event.key() == Qt.Key_Escape and self.stacked.currentIndex() == 1:
            self.collapse_plot()
        super().keyPressEvent(event)
    
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
        baud_rate = int(self.baud_combo.currentText())  # Get selected baud rate
        
        try:
            self.serial_port = serial.Serial(port, baud_rate, timeout=0.1)
            self.is_connected = True
            self.connect_btn.setText("⛔ قطع الاتصال")
            self.connect_btn.setStyleSheet("background-color: #e74c3c; color: white; border: none; padding: 8px 16px; border-radius: 4px; font-weight: bold;")
            self.status_label.setText(f"🟢 متصل: {port} @ {baud_rate}")
            self.status_label.setStyleSheet("color: #00ff88; font-weight: bold; font-size: 12px;")
            
            self.read_thread = threading.Thread(target=self.read_serial, daemon=True)
            self.read_thread.start()
            
        except Exception as e:
            self.status_label.setText(f"🔴 خطأ: {e}")
            self.status_label.setStyleSheet("color: #ff6b6b; font-weight: bold;")
    
    def disconnect(self):
        self.is_connected = False
        if self.serial_port:
            self.serial_port.close()
            self.serial_port = None
        
        self.connect_btn.setText("🔌 اتصال")
        self.connect_btn.setStyleSheet("background-color: #3498db; color: white; border: none; padding: 8px 16px; border-radius: 4px; font-weight: bold;")
        self.status_label.setText("⚪ غير متصل")
        self.status_label.setStyleSheet("color: #888888; font-weight: bold; font-size: 12px;")
    
    def read_serial(self):
        last_fps_time = time.time()
        fps_count = 0
        
        while self.is_connected and self.serial_port:
            try:
                data = self.serial_port.read(256)
                if data:
                    # Send raw data to terminal
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
    
    def on_new_frame(self, frame: dict):
        self.pending_frame = frame
        self.recorded_data.append(frame)
        
        self.frame_label.setText(f"Frames: {self.parser.frame_count}")
        self.error_label.setText(f"Errors: {self.parser.error_count}")
        
        self.data_label.setText(
            f"Roll: {frame['roll']:.1f}° | Pitch: {frame['pitch']:.1f}° | "
            f"Yaw: {frame['yaw']:.1f}° | Battery: {frame['battery']}%"
        )
    
    def on_status_update(self, status: str):
        self.fps_label.setText(status)
    
    def on_raw_data(self, data: bytes):
        """عرض البيانات الخام في Terminal - مُحسّن للأداء"""
        # Format as hex with spaces
        hex_str = ' '.join(f'{b:02X}' for b in data)
        
        # Append without forcing redraw
        cursor = self.terminal_text.textCursor()
        cursor.movePosition(cursor.End)
        cursor.insertText(hex_str + '\n')
        
        # Auto-scroll (efficient)
        self.terminal_text.verticalScrollBar().setValue(
            self.terminal_text.verticalScrollBar().maximum()
        )
        
        # Limit text size to 30KB to avoid memory issues
        content = self.terminal_text.toPlainText()
        if len(content) > 30000:
            self.terminal_text.setPlainText(content[-20000:])
    
    def clear_terminal(self):
        """مسح محتوى Terminal"""
        self.terminal_text.clear()
    
    def update_plots(self):
        if self.pending_frame is None:
            return
        
        frame = self.pending_frame
        t = frame['timestamp'] / 1000.0
        self.pending_frame = None
        
        # Orientation
        self.orientation_plot.update_data({
            'Roll': frame['roll'],
            'Pitch': frame['pitch'],
            'Yaw': frame['yaw']
        }, t)
        
        # Servos (all 4 at once)
        self.servo_group.update_data(
            frame['servo_cmds'],
            frame['servo_fb'],
            t
        )
        
        # Accelerometer
        self.accel_plot.update_data({
            'X': frame['accel_x'],
            'Y': frame['accel_y'],
            'Z': frame['accel_z']
        }, t)
        
        # GPS
        self.gps_plot.update_data({
            'Speed': frame['speed'],
            'Heading': frame['heading'] / 10.0,
            'Altitude': frame['gps_alt']
        }, t)
        
        # Battery
        self.battery_plot.update_data({
            'Battery%': frame['battery'],
            'Voltage/10': frame['voltage'] / 100.0,
            'Temp': frame['temperature']
        }, t)
        
        # Tracking
        self.tracking_plot.update_data({
            'Target_X': frame['track_x'],
            'Target_Y': frame['track_y']
        }, t)
        
        # Redraw all plots ONCE (for performance)
        self.orientation_plot.redraw()
        self.servo_group.redraw()
        self.accel_plot.redraw()
        self.gps_plot.redraw()
        self.battery_plot.redraw()
        self.tracking_plot.redraw()
        
        # Update expanded view if open
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
            QMessageBox.warning(self, "تحذير", "لا توجد بيانات للتصدير")
            return
        
        filename, _ = QFileDialog.getSaveFileName(
            self, "حفظ ملف CSV",
            f"telemetry_{datetime.now().strftime('%Y%m%d_%H%M%S')}.csv",
            "CSV Files (*.csv)"
        )
        if not filename:
            return
        
        try:
            with open(filename, 'w') as f:
                f.write("timestamp,roll,pitch,yaw,accel_x,accel_y,accel_z,")
                f.write("s1_cmd,s1_fb,s2_cmd,s2_fb,s3_cmd,s3_fb,s4_cmd,s4_fb,")
                f.write("speed,heading,gps_alt,battery,voltage,temperature\n")
                
                for frame in self.recorded_data:
                    f.write(f"{frame['timestamp']},{frame['roll']:.2f},{frame['pitch']:.2f},{frame['yaw']:.2f},")
                    f.write(f"{frame['accel_x']:.3f},{frame['accel_y']:.3f},{frame['accel_z']:.3f},")
                    f.write(f"{frame['servo_cmds'][0]:.1f},{frame['servo_fb'][0]:.1f},")
                    f.write(f"{frame['servo_cmds'][1]:.1f},{frame['servo_fb'][1]:.1f},")
                    f.write(f"{frame['servo_cmds'][2]:.1f},{frame['servo_fb'][2]:.1f},")
                    f.write(f"{frame['servo_cmds'][3]:.1f},{frame['servo_fb'][3]:.1f},")
                    f.write(f"{frame['speed']:.1f},{frame['heading']:.1f},{frame['gps_alt']},")
                    f.write(f"{frame['battery']},{frame['voltage']},{frame['temperature']:.1f}\n")
            
            QMessageBox.information(self, "تم", f"تم تصدير {len(self.recorded_data)} إطار")
            
        except Exception as e:
            QMessageBox.warning(self, "خطأ", f"فشل التصدير: {e}")
    
    def closeEvent(self, event):
        self.disconnect()
        event.accept()


# ===================== MAIN =====================
if __name__ == '__main__':
    app = QApplication(sys.argv)
    app.setStyle('Fusion')
    
    window = TelemetryViewerV3()
    window.show()
    
    sys.exit(app.exec_())
