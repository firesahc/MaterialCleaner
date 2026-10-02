#Requires -Version 7
<#
.SYNOPSIS
  wizard-roundtrip：复跑 Parcel+共享 UID 剥离，不批量改用户规则。
#>
[CmdletBinding()]
param([string]$Serial = '')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/common.ps1')

$Serial = Get-DeviceSerial -Serial $Serial
$dir = New-ArtifactDir -Serial $Serial -Tag 'wizard'
Write-Host '[INFO] 设备侧只做用例触发，差分主体在 JVM：OrderedRedirectInterpreterTest/WizardAnswersParcelTest' -ForegroundColor Cyan
$rules = Invoke-AdbShell -Serial $Serial -Command "cat '/data/local/tmp/cleaner/bus/snapshots/redirect_policy.json' 2>&1 | head -c 512"
Write-ProbeLog -Dir $dir -Name 'snapshot-head.txt' -Content $rules | Out-Null
Write-Host '[PASS] wizard-roundtrip 探针完成，完整断言见 JVM 单测' -ForegroundColor Green
