@echo off
set LOGFILE="%~dp0app_debug_log.txt"
echo Checking App Directory... > %LOGFILE%
dir "C:\CANPhon\app" /b >> %LOGFILE%

echo. >> %LOGFILE%
echo Checking App Build File... >> %LOGFILE%
if exist "C:\CANPhon\app\build.gradle.kts" (
    echo Found build.gradle.kts >> %LOGFILE%
) else (
    echo MISSING build.gradle.kts! >> %LOGFILE%
)

echo. >> %LOGFILE%
echo Listing Tasks for :app... >> %LOGFILE%
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set ANDROID_HOME=C:\Users\ZD\AppData\Local\Android\Sdk
set PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%PATH%
cd /d C:\CANPhon
call gradlew.bat :app:tasks --all >> %LOGFILE% 2>&1
