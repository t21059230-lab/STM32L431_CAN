@echo off
echo ================================================
echo   STM32 CAN Bridge Firmware Flash
echo ================================================
echo.

set STM32CLI="C:\STM32CubeProgrammer\bin\STM32_Programmer_CLI.exe"
set HEXFILE="%~dp0Debug\CAN 2.hex"

echo Looking for STM32_Programmer_CLI...
if not exist %STM32CLI% (
    echo ERROR: STM32CubeProgrammer not found at default location!
    echo Please install STM32CubeProgrammer or update the path.
    pause
    exit /b 1
)

echo Looking for HEX file...
if not exist %HEXFILE% (
    echo ERROR: HEX file not found: %HEXFILE%
    echo Please build the project in STM32CubeIDE first.
    pause
    exit /b 1
)

echo.
echo Connecting to STM32 via ST-Link...
%STM32CLI% -c port=SWD reset=HWrst -w %HEXFILE% 0x08000000 -v -rst

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ================================================
    echo   ✅ FLASH SUCCESSFUL!
    echo ================================================
) else (
    echo.
    echo ================================================
    echo   ❌ FLASH FAILED! Check connection.
    echo ================================================
)

pause
