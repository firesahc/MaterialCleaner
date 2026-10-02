#Requires -Version 7
<#
.SYNOPSIS
  preflight：设备就绪检查，任一失败即停。
#>
[CmdletBinding()]
param([string]$Serial = '')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/common.ps1')

$Serial = Get-DeviceSerial -Serial $Serial
Write-Host "serial=$Serial" -ForegroundColor Cyan

$state = Invoke-Adb -Serial $Serial -AdbArgs @('get-state')
if ($state -ne 'device') { throw "设备状态异常：$state" }
Write-Host '[PASS] adb device' -ForegroundColor Green

$battery = Invoke-AdbShell -Serial $Serial -Command 'dumpsys battery | grep level'
Write-Host "[INFO] $battery"

$bus = Invoke-AdbShell -Serial $Serial -Command 'ls -ld /data/local/tmp/cleaner/bus 2>&1'
Write-Host "[INFO] bus: $bus"

$pkgs = Invoke-AdbShell -Serial $Serial -Command 'pm list packages me.gm.cleaner 2>&1'
Write-Host "[INFO] $pkgs"
if ($pkgs -notmatch 'me\.gm\.cleaner') { throw '未安装 me.gm.cleaner，请先安装 Debug APK' }

Write-Host '[PASS] preflight 全过' -ForegroundColor Green
