# 半自动实机探针（单机冒烟+围栏）

只读优先，写操作需二次确认。默认 `--dry-run` 只演练不落盘外写。

```powershell
pwsh scripts/device/preflight.ps1 -Serial emulator-5554
pwsh scripts/device/backup.ps1 -Serial <serial>
pwsh scripts/device/smoke-status.ps1 -Serial <serial>
pwsh scripts/device/mount-check.ps1 -Serial <serial> -TestPackage me.gm.cleaner.test -DryRun
pwsh scripts/device/wizard-roundtrip.ps1 -Serial <serial>
pwsh scripts/device/corrupt-guard.ps1 -Serial <serial>   # 需二次确认，失败自动 restore
pwsh scripts/device/collect-logs.ps1 -Serial <serial>
pwsh scripts/device/restore.ps1 -Serial <serial> -BackupDir artifacts/device/<serial>/<ts>-backup
```

安全 rails：禁自动重启；`forceStop` 仅测试包；超时 300s 单步即停；
日常包零触碰；测试放第二用户/工作资料+隔离路径 `/sdcard/MCTest`。
