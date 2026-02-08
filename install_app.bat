@echo off
echo Setting up environment...
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set ANDROID_HOME=C:\Users\ZD\AppData\Local\Android\Sdk
set PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%PATH%

echo Checking connection...
adb devices

echo Navigate to project...
cd /d C:\CANPhon

echo Building and Installing...
cmd /c "gradlew.bat installDebug"

if %ERRORLEVEL% NEQ 0 (
    echo Build Failed!
    pause
    exit /b %ERRORLEVEL%
)

echo Build Success! App Installed.
pause
