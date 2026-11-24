# Connects the Marble CamLink phone app to the Marble Flow page over the USB cable.
# 1. Turn on USB debugging on the Android phone and plug it in (tap "Allow" on the phone).
# 2. Run this script (right-click > Run with PowerShell, or:  powershell -ExecutionPolicy Bypass -File start-phone-link.ps1)
# 3. In the page's Camera menu pick "Phone app over USB (Marble CamLink)".
$ErrorActionPreference = 'Continue'
$site = 'https://ren2103.github.io/marble-flow'

$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" }
if (-not (Test-Path $adb)) {
  Write-Host 'adb (Android platform-tools) is not installed. Install it with:' -ForegroundColor Yellow
  Write-Host '  winget install Google.PlatformTools'
  exit 1
}

$devices = & $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\S' }
if (-not $devices) { Write-Host 'No phone found. Plug it in and enable USB debugging.' -ForegroundColor Yellow; exit 1 }
if ($devices -match 'unauthorized') { Write-Host 'Phone connected but not authorised: unlock it and tap "Allow USB debugging".' -ForegroundColor Yellow; exit 1 }

$installed = & $adb shell pm list packages com.marbleflow.camlink
if (-not ($installed -match 'camlink')) {
  $apk = Join-Path $PSScriptRoot 'marble-camlink.apk'
  if (-not (Test-Path $apk)) {
    $apk = Join-Path $env:TEMP 'marble-camlink.apk'
    Write-Host "Downloading the phone app from $site/phone/marble-camlink.apk"
    Invoke-WebRequest "$site/phone/marble-camlink.apk" -OutFile $apk -UseBasicParsing
  }
  Write-Host 'Installing Marble CamLink on the phone...'
  & $adb install -r $apk
}

& $adb shell pm grant com.marbleflow.camlink android.permission.CAMERA 2>$null
& $adb forward tcp:47865 tcp:47865 | Out-Null
& $adb shell am start -n com.marbleflow.camlink/.MainActivity | Out-Null
Write-Host 'Phone link ready. In Marble Flow choose "Phone app over USB (Marble CamLink)".' -ForegroundColor Green
Write-Host "Page: $site/"
