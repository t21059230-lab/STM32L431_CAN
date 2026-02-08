@echo off
REM CANphon Telemetry Viewer - Quick Start
REM This script installs dependencies and runs the viewer

echo ================================
echo CANphon Telemetry Viewer v3.2
echo ================================
echo.

REM Check Python
python --version >nul 2>&1
if errorlevel 1 (
    echo ERROR: Python not found! Please install Python 3.10+
    pause
    exit /b 1
)

echo Installing dependencies...
pip install pyserial PyQt5 matplotlib

echo.
echo Starting Telemetry Viewer...
python telemetry_viewer.py

pause
