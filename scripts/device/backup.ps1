#Requires -Version 7
<#
.SYNOPSIS
  backup：拷双配置文件+总线快照+状态基线，输出清单供 restore 比对。
  逐文件容错：缺席或无权限记 MISSING/ERROR，不中断。
#>
[CmdletBinding()]
param([string]$Serial = '')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/common.ps1')

$Serial = Get-DeviceSerial -Serial $Serial
$dir = New-ArtifactDir -Serial $Serial -Tag 'backup'
Write-Host "artifact=$dir" -ForegroundColor Cyan

$targets = @(
    @{ Kind = 'run-as'; Path = 'files/storage_redirect' },
    @{ Kind = 'run-as'; Path = 'files/read_only' },
    @{ Kind = 'run-as'; Path = 'files/deny_list' },
    @{ Kind = 'shell'; Path = '/data/local/tmp/cleaner/bus/snapshots/redirect_policy.json' },
    @{ Kind = 'shell'; Path = '/data/local/tmp/cleaner/bus/snapshots/read_only.json' },
    @{ Kind = 'shell'; Path = '/data/local/tmp/cleaner/bus/snapshots/configured_mount_points.json' }
)
$manifest = @()
foreach ($t in $targets) {
    $safe = ($t.Path -replace '[^A-Za-z0-9_.-]', '_') + '.txt'
    try {
        if ($t.Kind -eq 'run-as') {
            $out = Invoke-AdbShell -Serial $Serial -Command "run-as me.gm.cleaner cat '$($t.Path)' 2>&1"
        } else {
            $out = Invoke-AdbShell -Serial $Serial -Command "cat '$($t.Path)' 2>&1"
        }
        if ($out -match 'Permission denied|No such file') {
            $manifest += "MISS $($t.Path)"
            $out = "STATUS=MISS`r`n$out"
        } else {
            $manifest += "OK $($t.Path)"
        }
    } catch {
        if ($_.Exception.Message -match 'No such file') {
            $out = "STATUS=MISS`r`n$($_.Exception.Message)"
            $manifest += "MISS $($t.Path)"
        } else {
            $out = "STATUS=ERROR`r`n$($_.Exception.Message)"
            $manifest += "ERROR $($t.Path)"
        }
    }
    Write-ProbeLog -Dir $dir -Name $safe -Content $out | Out-Null
}
$gen = Invoke-AdbShell -Serial $Serial -Command 'cat ''/data/local/tmp/cleaner/bus/snapshots/redirect_policy.json'' | grep -o ''generation.: *[0-9]*'' | head -1'
$manifest += "baseline-generation: $gen"
Write-ProbeLog -Dir $dir -Name 'MANIFEST.txt' -Content ($manifest -join "`r`n") | Out-Null
Write-Host ($manifest -join "`n")
Write-Host '[PASS] backup 完成' -ForegroundColor Green
Write-Host $dir
