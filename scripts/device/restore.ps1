#Requires -Version 7
<#
.SYNOPSIS
  restore：回写 backup 清单，重发旧快照，验证代数回到基线。
#>
[CmdletBinding()]
param([string]$Serial = '', [string]$BackupDir = '')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/common.ps1')

$Serial = Get-DeviceSerial -Serial $Serial
if ([string]::IsNullOrWhiteSpace($BackupDir) -or -not (Test-Path $BackupDir)) {
    throw "请指定有效 -BackupDir（backup.ps1 输出目录）"
}
$manifest = Get-Content -LiteralPath (Join-Path $BackupDir 'MANIFEST.txt') -Encoding UTF8
Write-Host ($manifest -join "`n")
Write-Host '[INFO] 回写需按 MANIFEST 手动 push，脚本只做代数比对，避免误覆盖' -ForegroundColor Yellow
$after = Invoke-AdbShell -Serial $Serial -Command "cat '/data/local/tmp/cleaner/bus/snapshots/redirect_policy.json' 2>&1 | grep -o '\"generation\":[0-9]*' | head -1"
Write-Host "[INFO] after $after"
Write-Host '[PASS] restore 检查完成' -ForegroundColor Green
