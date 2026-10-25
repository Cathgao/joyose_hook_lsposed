@echo off
setlocal
cd /d "%~dp0"
echo === Building Joyose NoLimit LSPosed Module ===

if exist "%~dp0gradlew.bat" (
    call "%~dp0gradlew.bat" assembleRelease
    if %ERRORLEVEL% equ 0 (
        copy /Y "%~dp0app\build\outputs\apk\release\app-release.apk" "%~dp0JoyoseNoLimit.apk"
        echo.
        echo === Build Succeeded! ===
        echo Output: "%~dp0JoyoseNoLimit.apk"
    )
) else (
    wsl bash -c "cd '%~dp0' && ./build.sh"
)
pause
