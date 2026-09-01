#Requires -Version 7
<#
.SYNOPSIS
  MaterialCleaner 存储重定向结构质量门禁（G1-G6）。
.DESCRIPTION
  用法:
    pwsh scripts/gates/Run-Gates.ps1                     # 跑全部门禁
    pwsh scripts/gates/Run-Gates.ps1 -Gate G2            # 只跑单项
    pwsh scripts/gates/Run-Gates.ps1 -InitBaseline       # 以当前违规生成存量豁免清单
  退出码: 存在 FAIL => 1，否则 0（WARN 不阻断）。
#>
[CmdletBinding()]
param(
    [ValidateSet('All', 'G1', 'G2', 'G3', 'G4', 'G5', 'G6')]
    [string]$Gate = 'All',
    [switch]$InitBaseline
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$baselineFile = Join-Path $PSScriptRoot 'baseline-violations.txt'

# ---------- 公共工具 ----------
$script:findings = [System.Collections.Generic.List[object]]::new()

function Add-Finding([string]$Gate, [string]$Status, [string]$Detail) {
    $script:findings.Add([pscustomobject]@{ Gate = $Gate; Status = $Status; Detail = $Detail })
}

function Get-SourceFiles {
    param([string[]]$Extensions)
    $exclude = @('\.git\', '\build\', '\external\', '\include\android-base\', 'fuse_lowlevel.h')
    Get-ChildItem -LiteralPath $repoRoot -Recurse -File -Include $extensions |
        Where-Object {
            $p = $_.FullName
            -not ($exclude | Where-Object { $p -like "*$_*" }) -and
            $p -notmatch 'linux_syscall_support\.h$'
        }
}

function Get-Baseline {
    if (-not (Test-Path $baselineFile)) { return @{} }
    $map = @{}
    foreach ($line in Get-Content $baselineFile -Encoding UTF8) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $kv = $line -split "`t", 2
        $map[$kv[0]] = $kv[1]
    }
    return $map
}

function Save-Baseline([hashtable]$Entries) {
    $sep = [string][char]9
    $Entries.GetEnumerator() |
        Sort-Object Name |
        ForEach-Object { '{0}{1}{2}' -f $_.Key, $sep, $_.Value } |
        Set-Content -LiteralPath $baselineFile -Encoding UTF8
}

# ---------- G1 模块依赖红线 ----------
function Invoke-G1 {
    $rules = @(
        @{ Module = 'core/storage-redirect-domain'; Allowed = @() },
        @{ Module = 'core/storage-redirect-databus'; Allowed = @(':core:storage-redirect-domain') },
        @{ Module = 'runtime/media-provider-hook'; Forbidden = @(':runtime:cleaner-server') },
        @{ Module = 'runtime/cleaner-server'; Forbidden = @(':runtime:media-provider-hook') }
    )
    foreach ($rule in $rules) {
        $gradle = Join-Path $repoRoot ($rule.Module + '/build.gradle')
        if (-not (Test-Path $gradle)) {
            Add-Finding 'G1' 'WARN' "$($rule.Module): build.gradle 不存在，跳过"
            continue
        }
        $deps = (Select-String -LiteralPath $gradle -Pattern "project\('([^']+)'\)" -AllMatches) |
            ForEach-Object { $_.Matches } | ForEach-Object { $_.Groups[1].Value } | Select-Object -Unique
        foreach ($dep in $deps) {
            if ($rule.Allowed -and $dep -notin $rule.Allowed) {
                $allowedText = if ($rule.Allowed.Count) { $rule.Allowed -join ', ' } else { '无' }
                Add-Finding 'G1' 'FAIL' "$($rule.Module) 非法依赖 $dep（允许: $allowedText）"
            }
            if ($rule.Forbidden -and $dep -in $rule.Forbidden) {
                Add-Finding 'G1' 'FAIL' "$($rule.Module) 禁止依赖 $dep"
            }
        }
    }
}

# ---------- G2 文件粒度 ----------
function Invoke-G2 {
    $limits = @{ '.kt' = 800; '.java' = 800; '.cpp' = 1200; '.h' = 1200 }
    $exts = $limits.Keys | ForEach-Object { '*' + $_ }
    $current = @{}
    foreach ($f in Get-SourceFiles $exts) {
        $limit = $limits[[System.IO.Path]::GetExtension($f.Name)]
        $lines = (Get-Content -LiteralPath $f.FullName -ErrorAction SilentlyContinue | Measure-Object).Count
        if ($lines -gt $limit) {
            $rel = [System.IO.Path]::GetRelativePath($repoRoot, $f.FullName) -replace '\\', '/'
            $current[$rel] = $lines
        }
    }
    if ($InitBaseline) {
        Save-Baseline $current
        Add-Finding 'G2' 'PASS' "存量豁免清单已初始化/更新：$($current.Count) 条（含 G4 共用清单）"
        return
    }
    $baseline = Get-Baseline
    foreach ($k in $current.Keys) {
        if (-not $baseline.ContainsKey($k)) {
            Add-Finding 'G2' 'FAIL' "新增超限文件 $k ($($current[$k]) 行)"
        }
        elseif ([int]$baseline[$k] -lt $current[$k]) {
            Add-Finding 'G2' 'FAIL' "豁免文件增长 ${k}: $($baseline[$k]) → $($current[$k])"
        }
    }
    $stale = $baseline.Keys | Where-Object {
        $_ -like '*/*' -and $_ -notlike 'word:*' -and
        -not $current.ContainsKey($_) -and
        -not (Test-Path (Join-Path $repoRoot $_))
    }
    foreach ($k in $stale) { Add-Finding 'G2' 'WARN' "豁免条目指向已删除文件: $k（建议清理清单）" }
}

# ---------- G3 微类密度 ----------
function Invoke-G3 {
    $groups = Get-SourceFiles @('*.kt') | Group-Object { $_.DirectoryName }
    foreach ($g in $groups) {
        if ($g.Count -lt 5) { continue }
        $small = $g.Group | Where-Object { (Get-Content -LiteralPath $_.FullName | Measure-Object).Count -lt 100 }
        $ratio = [math]::Round($small.Count / $g.Count, 2)
        if ($ratio -ge 0.5) {
            $rel = [System.IO.Path]::GetRelativePath($repoRoot, $g.Name)
            Add-Finding 'G3' 'WARN' "$rel 微类占比 $ratio（$($small.Count)/$($g.Count) <100 行）"
        }
    }
}

# ---------- G4 词汇检查 ----------
function Invoke-G4 {
    $banned = @(
        @{ Pattern = '(?i)denylist'; Label = 'denylist' },
        @{ Pattern = 'ConfiguredMountPoint'; Label = 'ConfiguredMountPoint' },
        @{ Pattern = 'configured_mount_points'; Label = 'configured_mount_points' },
        @{ Pattern = '全局重定向快照'; Label = '全局重定向快照' }
    )
    $counts = @{}
    foreach ($f in Get-SourceFiles @('*.kt', '*.java', '*.cpp', '*.h')) {
        $rel = [System.IO.Path]::GetRelativePath($repoRoot, $f.FullName) -replace '\\', '/'
        $hits = Select-String -LiteralPath $f.FullName -Pattern ($banned.Pattern) -AllMatches -ErrorAction SilentlyContinue
        foreach ($hit in $hits) {
            foreach ($m in $hit.Matches) {
                $label = ($banned | Where-Object { $m.Value -match $_.Pattern } | Select-Object -First 1).Label
                $key = "{0}|{1}" -f $rel, $label
                $counts[$key] = 1 + $(if ($counts.ContainsKey($key)) { $counts[$key] } else { 0 })
            }
        }
    }
    if ($InitBaseline) {
        # G2 已写文件级清单；这里把词汇命中并入同一文件（键带前缀避免冲突）
        $merged = Get-Baseline
        foreach ($k in $counts.Keys) { $merged["word:$k"] = $counts[$k] }
        Save-Baseline $merged
        return
    }
    $baseline = Get-Baseline
    foreach ($k in $counts.Keys) {
        $bk = "word:$k"
        if (-not $baseline.ContainsKey($bk)) {
            Add-Finding 'G4' 'FAIL' "新增禁词命中 $k ×$($counts[$k])"
        }
        elseif ([int]$baseline[$bk] -lt $counts[$k]) {
            Add-Finding 'G4' 'FAIL' "禁词命中增长 ${k}: $($baseline[$bk]) → $($counts[$k])"
        }
    }
}

# ---------- G5 契约一致性 ----------
function Invoke-G5 {
    $ipcFile = Join-Path $repoRoot 'core/ipc-contract/src/main/java/me/gm/cleaner/model/IpcPackageRuntimeState.kt'
    $domFile = Get-ChildItem -LiteralPath (Join-Path $repoRoot 'core/storage-redirect-domain/src/main') -Recurse -Filter 'PackageRuntimeState.kt' -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not (Test-Path $ipcFile) -or -not $domFile) {
        Add-Finding 'G5' 'SKIP' '契约对尚未建立（两侧类型就位后自动生效）'
        return
    }
    $ipcNames = (Select-String -LiteralPath $ipcFile -Pattern 'const val\s+\w+_(\w+)\s*=\s*"(\w+)"' -AllMatches) |
        ForEach-Object { $_.Matches } | ForEach-Object { $_.Groups[2].Value } | Sort-Object -Unique
    $domText = Get-Content -LiteralPath $domFile.FullName -Raw -Encoding UTF8
    # 以 IPC 冻结词汇为基准：每个常量值必须在 domain 权威文件中存在同名词汇
    $missing = @($ipcNames | Where-Object { $domText -notmatch ('\b' + [regex]::Escape($_) + '\b') })
    if ($missing.Count -gt 0) {
        Add-Finding 'G5' 'FAIL' ('IPC 词汇在 domain 缺失: ' + ($missing -join '; '))
    }
}

# ---------- G6 死代码扫描（季度报告制） ----------
function Invoke-G6 {
    Add-Finding 'G6' 'SKIP' '季度手工审查制占位——后续接入 detekt UnusedPrivateMember 与全局引用扫描'
}

# ---------- 主流程 ----------
$selected = if ($Gate -eq 'All') { @('G1', 'G2', 'G3', 'G4', 'G5', 'G6') } else { @($Gate) }
foreach ($g in $selected) {
    try { & ("Invoke-$g") }
    catch { Add-Finding $g 'FAIL' "门禁异常: $($_.Exception.Message)" }
}

Write-Host "`n===== 门禁报告 ($repoRoot) =====" -ForegroundColor Cyan
$exit = 0
foreach ($group in $script:findings | Group-Object Gate) {
    $worst = if ($group.Group.Status -contains 'FAIL') { 'FAIL' }
        elseif ($group.Group.Status -contains 'WARN') { 'WARN' }
        else { 'PASS' }
    $color = @{ FAIL = 'Red'; WARN = 'Yellow'; PASS = 'Green'; SKIP = 'DarkGray' }[$worst]
    Write-Host ("[{0}] {1}" -f $worst, $group.Name) -ForegroundColor $color
    foreach ($item in $group.Group | Where-Object Status -ne 'PASS') {
        Write-Host ("      {0}: {1}" -f $item.Status, $item.Detail) -ForegroundColor $color
    }
    if ($worst -eq 'FAIL') { $exit = 1 }
}
if (-not $script:findings) { Write-Host '[PASS] 全部门禁无发现' -ForegroundColor Green }
if ($InitBaseline) { Write-Host "`n存量豁免清单已写入: $baselineFile" -ForegroundColor Cyan }
exit $exit

