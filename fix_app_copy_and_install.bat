@echo off
set LOGFILE="%~dp0fix_app_log.txt"
echo Starting App Repair... > %LOGFILE%

echo Creating app directory... >> %LOGFILE%
if not exist "C:\CANPhon\app" mkdir "C:\CANPhon\app" >> %LOGFILE% 2>&1

echo Copying app folder (Robust)... >> %LOGFILE%
xcopy "CANphon_8.10 NDK gps2  PAyph\CANphon_8.10 NDK gps2  PAyph\CANphon_8.8 gps2  PAyph\CANphon_1\CANphon\app" "C:\CANPhon\app" /S /E /H /Y /I >> %LOGFILE% 2>&1

if %ERRORLEVEL% NEQ 0 (
    echo XCOPY APP FAILED! >> %LOGFILE%
    exit /b 1
)

echo App Repair Complete. Running Installer... >> %LOGFILE%
call run_app_task.bat >> %LOGFILE% 2>&1
  ي