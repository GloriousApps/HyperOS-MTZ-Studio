<#
.SYNOPSIS
  Runs the rootless re-test from docs/rootless-capability-device-test.md section 9 against the
  attached device.

.DESCRIPTION
  Step 1 checks the phone is actually clean. Step 3 opens the local-resource deep link and reads
  the resulting screen. Step 4 taps Apply and looks for evidence of a real apply — and only runs
  when -Apply is passed, because it changes the device's active theme.

  Step 2 (getting a theme into the catalogue over Shizuku) is manual: do it in MTZ Studio on the
  phone first, then run this.

.EXAMPLE
  .\Test-RootlessApply.ps1
  .\Test-RootlessApply.ps1 -LocalId 2009277e-2e37-3b91-988b-821aa43aa81c -Apply
#>
[CmdletBinding()]
param(
    # Omit to list the catalogue and pick the most recently written record.
    [string]$LocalId,
    [string]$Serial,
    # Taps Apply. Changes the active theme, so it is opt-in.
    [switch]$Apply
)

$ErrorActionPreference = 'Stop'

function Get-Adb {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    # A session started before adb was installed still has the old PATH.
    $env:Path = "$([Environment]::GetEnvironmentVariable('Path','Machine'));$([Environment]::GetEnvironmentVariable('Path','User'))"
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    throw 'adb not found. Install Google platform-tools and reopen the shell.'
}

$adb = Get-Adb
$target = if ($Serial) { @('-s', $Serial) } else { @() }
function Sh { param([string]$Command) & $adb @target shell $Command 2>&1 | Out-String }

$themeStore = '/sdcard/Android/data/com.android.thememanager/files/MIUI/theme/.data/meta/theme'

Write-Host "`n=== Step 1: is the phone clean? ===" -ForegroundColor Cyan
$suPath = (Sh 'command -v su').Trim()
$managers = (Sh 'pm list packages').Split("`n") |
    Where-Object { $_ -match 'ksunext|magisk|kernelsu|apatch|supersu|lsposed|xposed|matrix\.vector|corepatch' }
$enforce = (Sh 'getenforce').Trim()
$bootState = (Sh 'getprop ro.boot.verifiedbootstate').Trim()
$themesVersion = ((Sh 'dumpsys package com.android.thememanager') -split "`n" |
    Where-Object { $_ -match 'versionName' } | Select-Object -First 1).Trim()

[pscustomobject]@{
    SuBinary      = if ($suPath) { $suPath } else { '(none)' }
    RootManagers  = if ($managers) { ($managers -join ', ') } else { '(none)' }
    SELinux       = $enforce
    VerifiedBoot  = $bootState
    Themes        = $themesVersion
} | Format-List

$clean = -not $managers -and $bootState -eq 'green' -and $enforce -eq 'Enforcing'
if ($clean) {
    Write-Host 'CLEAN: no root manager, verified boot green, SELinux enforcing.' -ForegroundColor Green
} else {
    Write-Warning 'NOT CLEAN. Results from this phone cannot establish a rootless capability.'
}

Write-Host "`n=== Step 2 check: local theme catalogue ===" -ForegroundColor Cyan
$records = (Sh "ls -t $themeStore 2>/dev/null").Split("`n") |
    ForEach-Object { $_.Trim() } | Where-Object { $_ -match '\.mrm$' }
if (-not $records) {
    Write-Warning "No records under $themeStore. Import a theme in MTZ Studio over Shizuku first (step 2)."
    return
}
$records | ForEach-Object {
    $id = $_ -replace '\.mrm$', ''
    # Records written by Themes are pretty-printed, so the value sits on a later line than the
    # "titles" key. MTZ Studio's own converter emits compact JSON, where it is on the same line.
    $titles = (Sh "grep -A3 '\`"titles\`"' '$themeStore/$_'").Trim()
    $title = if ($titles -match '"fallback"\s*:\s*"([^"]*)"') { $Matches[1] }
             elseif ($titles -match ':\s*"([^"]+)"') { $Matches[1] }
             else { '(untitled)' }
    [pscustomobject]@{ LocalId = $id; Title = $title }
} | Format-Table -AutoSize

if (-not $LocalId) { $LocalId = ($records | Select-Object -First 1) -replace '\.mrm$', '' }
Write-Host "Using localId: $LocalId"

Write-Host "`n=== Step 3: deep link ===" -ForegroundColor Cyan
Sh 'am force-stop com.android.thememanager' | Out-Null
Start-Sleep -Seconds 1
Sh "am start -a android.intent.action.VIEW -c android.intent.category.DEFAULT -n com.android.thememanager/.activity.ThemeDetailActivity -d 'ViewLocalResource://view.local.resource#$LocalId'" | Out-Null
Start-Sleep -Seconds 6
Sh 'uiautomator dump /data/local/tmp/ui.xml' | Out-Null
$ui = Sh 'cat /data/local/tmp/ui.xml'
$screen = ([regex]::Matches($ui, 'text="([^"]{1,})"') | ForEach-Object { $_.Groups[1].Value }) -join ' | '
$applyBtn = [regex]::Match($ui, 'resource-id="com\.android\.thememanager:id/applyBtn"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')

Write-Host "screen: $screen"
if ($applyBtn.Success) {
    Write-Host 'PASS: the local theme page with its Apply button opened.' -ForegroundColor Green
} else {
    Write-Warning 'FAIL: no Apply button. Likely the online detail screen (spinner) instead.'
    return
}

if (-not $Apply) {
    Write-Host "`nStep 4 skipped. Re-run with -Apply to tap Apply (this changes the active theme)." -ForegroundColor Yellow
    return
}

Write-Host "`n=== Step 4: tap Apply ===" -ForegroundColor Cyan
$x = ([int]$applyBtn.Groups[1].Value + [int]$applyBtn.Groups[3].Value) / 2
$y = ([int]$applyBtn.Groups[2].Value + [int]$applyBtn.Groups[4].Value) / 2
$before = (Sh 'ls -la /data/system/theme/ 2>/dev/null | wc -l').Trim()
Sh 'logcat -c' | Out-Null
Sh "input tap $([int]$x) $([int]$y)" | Out-Null
Start-Sleep -Seconds 8

$log = Sh 'logcat -d --pid=$(pidof com.android.thememanager) -t 300'
$appliedLog = ($log -split "`n" | Where-Object { $_ -match 'Applying theme|writeDefaultIconSettings|themeChanged=' }) -join "`n"
$written = (Sh "find /data/system/theme -maxdepth 1 -newermt '-3 minutes' 2>/dev/null").Trim()
Sh 'uiautomator dump /data/local/tmp/ui.xml' | Out-Null
$afterUi = Sh 'cat /data/local/tmp/ui.xml'
$afterScreen = ([regex]::Matches($afterUi, 'text="([^"]{1,})"') | ForEach-Object { $_.Groups[1].Value }) -join ' | '

Write-Host "--- Themes log ---"; Write-Host ($appliedLog | Out-String)
Write-Host "--- components touched in the last 3 minutes ---"; Write-Host $written
Write-Host "--- screen after the tap ---"; Write-Host $afterScreen

if ($appliedLog -match 'Applying theme END' -and $written) {
    Write-Host "`nAPPLIED. On a clean phone this settles section 9." -ForegroundColor Green
} else {
    Write-Warning 'No apply evidence. Capture the screen text above: a rights or purchase prompt here is the interesting negative result.'
}

Sh 'rm -f /data/local/tmp/ui.xml' | Out-Null
