#Requires -Version 7
<#
.SYNOPSIS
  smoke-status 只读：三快照同代+总线健康，漂移即 FAIL。
#>
[CmdletBinding()]
param([string]$Serial = '')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/common.ps1')

$Serial = Get-DeviceSerial -Serial $Serial
$dir = New-ArtifactDir -Serial $Serial -Tag 'smoke'
$gens = @{}
foreach ($name in @('redirect_policy.json', 'read_only.json', 'configured_mount_points.json')) {
    $content = Invoke-AdbShell -Serial $Serial -Command "cat '/data/local/tmp/cleaner/bus/snapshots/$name' 2>&1"
    Write-ProbeLog -Dir $dir -Name $name -Content $content | Out-Null
    if ($content -match '"generation"\s*:\s*([0-9]+)') { $gens[$name] = $Matches[1] }
    else { throw "快照缺 generation：$name" }
}
$uniq = ($gens.Values | Sort-Object -Unique)
Write-Host ("generations: " + (($gens.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ' '))
if ($uniq.Count -ne 1) { throw "三快照不同代，存在混合状态：$($uniq -join ',')" }
Write-ProbeLog -Dir $dir -Name 'RESULT.txt' -Content "PASS generation=$($uniq[0])" | Out-Null
Write-Host "[PASS] smoke 同代 generation=$($uniq[0])" -ForegroundColor Green
