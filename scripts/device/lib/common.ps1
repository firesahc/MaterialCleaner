#Requires -Version 7
<#
.SYNOPSIS
  device 探针公共库：serial 解析、adb 超时封装、产物目录、日志。
.DESCRIPTION
  只做传输与落盘，不理解 redirect/readOnly/generation 语义。
  所有超时 300s，与构建超时一致。
#>
$ErrorActionPreference = 'Stop'

$script:DeviceLibRoot = Split-Path -Parent $PSScriptRoot
$script:RepoRoot = Split-Path -Parent (Split-Path -Parent $script:DeviceLibRoot)

function Get-DeviceSerial {
    param([string]$Serial = '')
    if (-not [string]::IsNullOrWhiteSpace($Serial)) { return $Serial.Trim() }
    if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_SERIAL)) { return $env:ANDROID_SERIAL.Trim() }
    $out = & adb devices -l 2>&1 | Out-String
    $lines = @($out -split "`r?`n" | Where-Object { $_ -match '\sdevice(\s|$)' -and $_ -notmatch 'List of devices' })
    if ($lines.Count -eq 0) { throw '未发现已连接设备，请先 adb connect 或检查 USB 调试' }
    if ($lines.Count -gt 1) { throw "发现多台设备，请用 -Serial 或 ANDROID_SERIAL 指定：$($lines -join '; ')" }
    return ($lines[0] -split '\s+')[0]
}

function Invoke-Adb {
    [CmdletBinding()]
    param(
        [string]$Serial,
        [string[]]$AdbArgs,
        [int]$TimeoutSec = 300
    )
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = 'adb'
    $psi.Arguments = "-s $Serial " + ($AdbArgs -join ' ')
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $p = [System.Diagnostics.Process]::Start($psi)
    if (-not $p.WaitForExit($TimeoutSec * 1000)) {
        try { $p.Kill() } catch { }
        throw "adb 超时 ${TimeoutSec}s：adb -s $Serial $($AdbArgs -join ' ')"
    }
    $stdout = $p.StandardOutput.ReadToEnd()
    $stderr = $p.StandardError.ReadToEnd()
    if ($p.ExitCode -ne 0) { throw "adb 失败 exit=$($p.ExitCode)：$stdout $stderr" }
    return $stdout.Trim()
}

function Invoke-AdbShell {
    [CmdletBinding()]
    param([string]$Serial, [string]$Command, [int]$TimeoutSec = 300)
    return Invoke-Adb -Serial $Serial -AdbArgs @('shell', $Command) -TimeoutSec $TimeoutSec
}

function New-ArtifactDir {
    param([string]$Serial, [string]$Tag)
    $ts = Get-Date -Format 'yyyyMMdd-HHmmss'
    $safeSerial = ($Serial -replace '[^A-Za-z0-9_.-]', '_')
    $dir = Join-Path $script:RepoRoot ("artifacts/device/$safeSerial/$ts-$Tag")
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    return $dir
}

function Write-ProbeLog {
    param([string]$Dir, [string]$Name, [string]$Content)
    $path = Join-Path $Dir $Name
    Set-Content -LiteralPath $path -Value $Content -Encoding UTF8
    return $path
}

function Assert-Equal {
    param([string]$Name, [string]$Expected, [string]$Actual)
    if ($Expected -ne $Actual) { throw "断言失败 ${Name}：期望 <$Expected> 实际 <$Actual>" }
}
