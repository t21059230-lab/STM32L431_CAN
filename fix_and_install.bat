@echo off
set LOGFILE="%~dp0fix_log.txt"
echo Starting Repair... > %LOGFILE%

echo Creating wrapper directory... >> %LOGFILE%
mkdir "C:\CANPhon\gradle\wrapper" >> %LOGFILE% 2>&1

echo Copying wrapper files... >> %LOGFILE%
copy "CANphon_8.10 NDK gps2  PAyph\CANphon_8.10 NDK gps2  PAyph\CANphon_8.8 gps2  PAyph\CANphon_1\CANphon\gradle\wrapper\*.*" "C:\CANPhon\gradle\wrapper\" /Y >> %LOGFILE% 2>&1

if %ERRORLEVEL% NEQ 0 (
    echo COPY FAILED! >> %LOGFILE%
    exit /b 1
)

echo Repair Complete. Running Installer... >> %LOGFILE%
call diagnose_install.bat >> %LOGFILE% 2>&1
