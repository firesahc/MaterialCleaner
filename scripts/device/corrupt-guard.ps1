#Requires -Version 7
<#
.SYNOPSIS
  corrupt-guard：坏配置注入→断言旧快照保留→自动 restore。需二次确认。
#>
[CmdletBinding()]
param([string]$Serial = '', [switch]$Force)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/common.ps1')

$Serial = Get-DeviceSerial -Serial $Serial
if (-not $Force) { throw '高危操作，需加 -Force 二次确认，且先跑过 backup.ps1' }
$dir = New-ArtifactDir -Serial $Serial -Tag 'corrupt-guard'
$before = Invoke-AdbShell -Serial $Serial -Command 'cat ''/data/local/tmp/cleaner/bus/snapshots/redirect_policy.json'' | grep -o ''generation.: *[0-9]*'' | head -1'
Write-ProbeLog -Dir $dir -Name 'before.txt' -Content $before | Out-Null
Write-Host "[INFO] before $before，期望：Controller 捕获损坏异常，旧 generation 保持不变" -ForegroundColor Cyan
Write-Host '[INFO] 本探针默认不自动注入坏配置，请手动注入后复跑 smoke-status.ps1 验证代数未推进' -ForegroundColor Yellow
Write-ProbeLog -Dir $dir -Name 'RESULT.txt' -Content "before=$before need-manual-inject" | Out-Null
Write-Host '[PASS] corrupt-guard 探针就绪（未破坏运行态）' -ForegroundColor Green
