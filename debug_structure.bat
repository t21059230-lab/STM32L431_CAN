@echo off
set LOGFILE="%~dp0structure_log.txt"
echo Checking C:\CANPhon structure > %LOGFILE%
dir "C:\CANPhon" /b >> %LOGFILE%
echo. >> %LOGFILE%
echo Checking gradle wrapper in C:\CANPhon... >> %LOGFILE%
dir "C:\CANPhon\gradle\wrapper" /b >> %LOGFILE%
echo. >> %LOGFILE%
echo Checking source for gradle-wrapper.jar... >> %LOGFILE%
dir "CANphon_8.10 NDK gps2  PAyph\CANphon_8.10 NDK gps2  PAyph\CANphon_8.8 gps2  PAyph\CANphon_1\CANphon\gradle\wrapper\gradle-wrapper.jar" /b /s >> %LOGFILE%
