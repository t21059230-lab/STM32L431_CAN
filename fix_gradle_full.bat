@echo off
set LOGFILE="%~dp0fix_full_log.txt"
echo Starting Full Repair... > %LOGFILE%

echo Creating gradle directory... >> %LOGFILE%
if not exist "C:\CANPhon\gradle" mkdir "C:\CANPhon\gradle" >> %LOGFILE% 2>&1

echo Copying libs.versions.toml... >> %LOGFILE%
copy "CANphon_8.10 NDK gps2  PAyph\CANphon_8.10 NDK gps2  PAyph\CANphon_8.8 gps2  PAyph\CANphon_1\CANphon\gradle\libs.versions.toml" "C:\CANPhon\gradle\" /Y >> %LOGFILE% 2>&1

if %ERRORLEVEL% NEQ 0 (
    echo COPY LIBS FAILED! >> %LOGFILE%
    exit /b 1
)

echo Repair Complete. Running Installer... >> %LOGFILE%
call diagnose_install.bat >> %LOGFILE% 2>&1
