#Requires -Version 7
<#
.SYNOPSIS
  collect-logs：logcat/ErrorJournal/DiagnosticArchive 打包。
#>
[CmdletBinding()]
param([string]$Serial = '')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/common.ps1')

$Serial = Get-DeviceSerial -Serial $Serial
$dir = New-ArtifactDir -Serial $Serial -Tag 'logs'
$log = Invoke-Adb -Serial $Serial -Args @('logcat', '-d', '-s', 'MC_REDIRECT:D', 'StorageRedirectConfigController:D') -TimeoutSec 60
Write-ProbeLog -Dir $dir -Name 'logcat.txt' -Content $log | Out-Null
$diag = Invoke-AdbShell -Serial $Serial -Command "ls -lt /data/local/tmp/cleaner/bus/events 2>&1 | head -20"
Write-ProbeLog -Dir $dir -Name 'events.txt' -Content $diag | Out-Null
Write-Host "[PASS] logs=$dir" -ForegroundColor Green
