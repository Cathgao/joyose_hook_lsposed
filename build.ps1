# JoyoseNoLimit Build Script for Windows PowerShell
$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
Set-Location $scriptDir

Write-Host "=== Building Joyose NoLimit LSPosed Module ===" -ForegroundColor Cyan

if (Test-Path "$scriptDir\gradlew.bat") {
    Write-Host "Executing Gradle Wrapper..." -ForegroundColor Yellow
    & "$scriptDir\gradlew.bat" assembleRelease
    if ($LASTEXITCODE -eq 0) {
        $outApk = "$scriptDir\app\build\outputs\apk\release\app-release.apk"
        if (Test-Path $outApk) {
            Copy-Item $outApk "$scriptDir\JoyoseNoLimit.apk" -Force
            Write-Host "Build succeeded! Output: $scriptDir\JoyoseNoLimit.apk" -ForegroundColor Green
        }
    } else {
        Write-Error "Gradle build failed with exit code $LASTEXITCODE"
    }
} elseif (Get-Command wsl -ErrorAction SilentlyContinue) {
    Write-Host "Running build.sh via WSL..." -ForegroundColor Yellow
    wsl bash -c "cd '$scriptDir' && ./build.sh"
} else {
    Write-Error "Neither gradlew.bat nor WSL found."
}
