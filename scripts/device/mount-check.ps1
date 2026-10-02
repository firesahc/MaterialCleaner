#Requires -Version 7
<#
.SYNOPSIS
  mount-check：专用测试包+隔离路径，只查不改。默认 DryRun。
#>
[CmdletBinding()]
param(
    [string]$Serial = '',
    [string]$TestPackage = 'me.gm.cleaner.test',
    [switch]$DryRun = $true,
    [switch]$Force
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/common.ps1')

$Serial = Get-DeviceSerial -Serial $Serial
if ($TestPackage -ne 'me.gm.cleaner.test' -and -not $Force) {
    throw "非测试包需加 -Force 二次确认，拒绝触碰日常包：$TestPackage"
}
$dir = New-ArtifactDir -Serial $Serial -Tag 'mount-check'
Write-Host "pkg=$TestPackage dryRun=$DryRun" -ForegroundColor Cyan

$probe = '/sdcard/MCTest/probe.txt'
if (-not $DryRun) {
    Invoke-AdbShell -Serial $Serial -Command 'mkdir -p /sdcard/MCTest && echo probe > /sdcard/MCTest/probe.txt'
}
try {
    $check = Invoke-AdbShell -Serial $Serial -Command 'ls -l /sdcard/MCTest/probe.txt 2>&1'
} catch {
    $check = "STATUS=MISS $($_.Exception.Message)"
}
Write-ProbeLog -Dir $dir -Name 'mount-check.txt' -Content "pkg=$TestPackage`r`ndryRun=$DryRun`r`n$check" | Out-Null
Write-Host '[PASS] mount-check 只读完成（未 remount 未强停）' -ForegroundColor Green
