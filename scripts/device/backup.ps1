#Requires -Version 7
<#
.SYNOPSIS
  backup：拷双配置文件+总线快照+状态基线，输出清单供 restore 比对。
#>
[CmdletBinding()]
param([string]$Serial = '')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/common.ps1')

$Serial = Get-DeviceSerial -Serial $Serial
$dir = New-ArtifactDir -Serial $Serial -Tag 'backup'
Write-Host "artifact=$dir" -ForegroundColor Cyan

$files = @(
    '/data/data/me.gm.cleaner/files/storage_redirect',
    '/data/data/me.gm.cleaner/files/read_only',
    '/data/local/tmp/cleaner/bus/snapshots/redirect_policy.json',
    '/data/local/tmp/cleaner/bus/snapshots/read_only.json',
    '/data/local/tmp/cleaner/bus/snapshots/configured_mount_points.json'
)
$manifest = @()
foreach ($f in $files) {
    $safe = ($f -replace '[^A-Za-z0-9_.-]', '_') + '.txt'
    $out = Invoke-AdbShell -Serial $Serial -Command "cat '$f' 2>&1"
    $p = Write-ProbeLog -Dir $dir -Name $safe -Content $out
    $manifest += "$f -> $safe"
}
$gen = Invoke-AdbShell -Serial $Serial -Command "cat '/data/local/tmp/cleaner/bus/snapshots/redirect_policy.json' 2>&1 | grep -o '\"generation\":[0-9]*' | head -1"
$manifest += "baseline-generation: $gen"
Write-ProbeLog -Dir $dir -Name 'MANIFEST.txt' -Content ($manifest -join "`r`n") | Out-Null
Write-Host '[PASS] backup 完成' -ForegroundColor Green
Write-Host $dir
