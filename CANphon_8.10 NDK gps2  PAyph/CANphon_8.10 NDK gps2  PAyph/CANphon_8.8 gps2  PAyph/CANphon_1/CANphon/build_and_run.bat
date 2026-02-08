@echo off
echo =========================================
echo   Building and Installing CANphon App
echo =========================================

REM Set Environment
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "ANDROID_HOME=C:\Users\ZD\AppData\Local\Android\Sdk"
set "PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%PATH%"

echo.
echo Checking ADB connection...
adb devices

echo.
echo Cleaning & Building Debug APK...
call gradlew.bat clean :app:assembleDebug

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ❌ BUILD FAILED!
    pause
    exit /b 1
)

echo.
echo Installing APK...
adb install -r app\build\outputs\apk\debug\app-debug.apk

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ❌ INSTALL FAILED!
    echo Ensure phone is connected and USB debugging is ON.
    pause
    exit /b 1
)

echo.
echo Launching App...
adb shell monkey -p com.example.canphon -c android.intent.category.LAUNCHER 1

echo.
echo ✅ DONE! App should be running.
echo Connect Waveshare adapter now.
pause
