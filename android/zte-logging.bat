@echo off
setlocal enabledelayedexpansion

:: ZTE Logging Toggle Script for Nubia Devices
:: Usage: zte-logging.bat [on|off|status]

:: Use ADB from Android SDK (not in PATH on this system)
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"

:: Check if ADB exists
if not exist "%ADB%" (
    echo Error: ADB not found at %ADB%
    echo Please install Android SDK or update the ADB path in this script.
    exit /b 1
)

:: Check for command argument
if "%~1"=="" (
    echo ZTE Logging Toggle for Nubia Devices
    echo.
    echo Usage: %~nx0 [on^|off^|status]
    echo.
    echo Commands:
    echo   on      Enable ZTE logging
    echo   off     Disable ZTE logging ^(saves battery/performance^)
    echo   status  Show current log buffer status
    exit /b 0
)

:: Handle commands
if /i "%~1"=="on" (
    echo Enabling ZTE logging...
    "%ADB%" shell am broadcast -a com.zte.logcontrol.intent.action.LogControl --ei log_enable 1
    if !errorlevel! equ 0 (
        echo.
        echo ZTE logging enabled. Use 'adb logcat' to view logs.
    ) else (
        echo.
        echo Failed to enable logging. Is the device connected?
    )
    exit /b !errorlevel!
)

if /i "%~1"=="off" (
    echo Disabling ZTE logging...
    "%ADB%" shell am broadcast -a com.zte.logcontrol.intent.action.LogControl --ei log_enable 0
    if !errorlevel! equ 0 (
        echo.
        echo ZTE logging disabled.
    ) else (
        echo.
        echo Failed to disable logging. Is the device connected?
    )
    exit /b !errorlevel!
)

if /i "%~1"=="status" (
    echo Log buffer status:
    echo.
    "%ADB%" logcat -g
    exit /b !errorlevel!
)

:: Unknown command
echo Unknown command: %~1
echo Use '%~nx0' without arguments for usage information.
exit /b 1
