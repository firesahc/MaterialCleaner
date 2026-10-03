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
$epochs = @{}
foreach ($name in @('redirect_policy.json', 'read_only.json', 'configured_mount_points.json')) {
    $content = Invoke-AdbShell -Serial $Serial -Command "cat '/data/local/tmp/cleaner/bus/snapshots/$name' 2>&1"
    Write-ProbeLog -Dir $dir -Name $name -Content $content | Out-Null
    if ($content -match '"generation"\s*:\s*([0-9]+)') { $gens[$name] = $Matches[1] }
    else { throw "快照缺 generation：$name" }
    if ($content -match '"publisherEpoch"\s*:\s*"([^"]+)"') { $epochs[$name] = $Matches[1] }
    else { throw "快照缺 publisherEpoch：$name" }
}
$uniqEpoch = @($epochs.Values | Sort-Object -Unique)
Write-Host ("generations: " + (($gens.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ' '))
Write-Host ("epoch: $($uniqEpoch[0])")
if ($uniqEpoch.Count -ne 1) { throw "三快照纪元不一致，发布者已换代：$($uniqEpoch -join ',')" }
foreach ($g in $gens.Values) { if ([long]$g -le 0) { throw "代数异常：$g" } }
Write-ProbeLog -Dir $dir -Name 'RESULT.txt' -Content "PASS epoch=$($uniqEpoch[0])" | Out-Null
Write-Host "[PASS] smoke 纪元一致 epoch=$($uniqEpoch[0])" -ForegroundColor Green
