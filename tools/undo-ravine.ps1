# Undo Ravine on the NVIDIA Shield: puts back everything Ravine's setup changes, from the backup taken
# on 2026-11-18 before Ravine was installed. Safe to run at any stage (steps that weren't done are no-ops).
#
# Run from the platform-tools folder, with the Shield connected (.\adb devices shows "device"):
#   powershell -ExecutionPolicy Bypass -File path\to\undo-ravine.ps1
# Optional: -KeepApp leaves Ravine installed and only restores the settings.

param([switch]$KeepApp)
$adb = Join-Path (Get-Location) "adb.exe"
if (-not (Test-Path $adb)) { Write-Error "Run this from the folder that contains adb.exe"; exit 1 }
function Sh($cmd) { & $adb shell $cmd }

Write-Host "Screensaver -> Google's Ambient Mode (Backdrop)"
Sh "settings put secure screensaver_components com.google.android.backdrop/.Backdrop"

Write-Host "Accessibility -> Projectivy's Home override only (removes Ravine's Home-button helper)"
Sh "settings put secure enabled_accessibility_services com.spocky.projengmenu/com.spocky.projengmenu.services.ProjectivyAccessibilityService"
Sh "settings put secure accessibility_enabled 1"

Write-Host "Notification access -> remove Ravine's (Google TV recommendations keeps its own)"
Sh "cmd notification disallow_listener app.ravine.launcher/dev.veil.launcher.MediaListener"

Write-Host "Private DNS -> not set (as before)"
Sh "settings delete global private_dns_mode"
Sh "settings delete global private_dns_specifier"

if (-not $KeepApp) {
    Write-Host "Uninstalling Ravine (Android then falls back to the previous home screen)"
    & $adb uninstall app.ravine.launcher
}

Write-Host "Waiting 30 s so Android saves the settings before any reboot..."
Start-Sleep -Seconds 30
Write-Host "Home screen now resolves to:"
Sh "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
Write-Host "Done. If Projectivy's 'Override current launcher' was turned off, turn it back on in Projectivy's settings."
