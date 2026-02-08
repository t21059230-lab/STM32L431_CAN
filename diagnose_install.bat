@echo off
set LOGFILE="%~dp0build_log.txt"
echo Starting Installation Process > %LOGFILE%
echo Timestamp: %TIME% >> %LOGFILE%

set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set ANDROID_HOME=C:\Users\ZD\AppData\Local\Android\Sdk
set PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%PATH%

echo Environment: >> %LOGFILE%
echo JAVA_HOME=%JAVA_HOME% >> %LOGFILE%
echo ANDROID_HOME=%ANDROID_HOME% >> %LOGFILE%

echo Checking Project Directory... >> %LOGFILE%
if not exist "C:\CANPhon" (
    echo ERROR: C:\CANPhon does not exist! >> %LOGFILE%
    exit /b 1
)

cd /d C:\CANPhon

echo Creating local.properties... >> %LOGFILE%
echo sdk.dir=C:\\Users\\ZD\\AppData\\Local\\Android\\Sdk > local.properties

echo Checking ADB Devices... >> %LOGFILE%
call adb devices >> %LOGFILE% 2>&1

echo Cleaning Project... >> %LOGFILE%
call gradlew.bat clean >> %LOGFILE% 2>&1

echo Installing Debug APK... >> %LOGFILE%
call gradlew.bat installDebug >> %LOGFILE% 2>&1

if %ERRORLEVEL% NEQ 0 (
    echo BUILD FAILED! See logs above. >> %LOGFILE%
    exit /b 1
) else (
    echo BUILD SUCCESS! >> %LOGFILE%
    
    echo Launching App... >> %LOGFILE%
    call adb shell am start -n com.example.canphon/.MainActivity >> %LOGFILE% 2>&1
)
