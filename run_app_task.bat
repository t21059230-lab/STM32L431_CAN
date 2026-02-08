@echo off
set LOGFILE="%~dp0app_task_log.txt"
echo Starting App Task Install... > %LOGFILE%

set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set ANDROID_HOME=C:\Users\ZD\AppData\Local\Android\Sdk
set PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%PATH%

cd /d C:\CANPhon

echo Checking settings.gradle.kts... >> %LOGFILE%
type settings.gradle.kts >> %LOGFILE% 2>&1

echo Running :app:clean... >> %LOGFILE%
call gradlew.bat :app:clean >> %LOGFILE% 2>&1

echo Running :app:installDebug... >> %LOGFILE%
call gradlew.bat :app:installDebug >> %LOGFILE% 2>&1

if %ERRORLEVEL% NEQ 0 (
    echo APP INSTALL FAILED! >> %LOGFILE%
    exit /b 1
) else (
    echo APP INSTALL SUCCESS! >> %LOGFILE%
    
    echo Launching App... >> %LOGFILE%
    call adb shell am start -n com.example.canphon/.MainActivity >> %LOGFILE% 2>&1
)
