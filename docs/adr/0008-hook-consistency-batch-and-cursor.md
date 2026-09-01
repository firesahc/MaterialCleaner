# Hook 一致性：批量提交与信号游标

- Status: Accepted
- Date: 2026-08-26
- Supersedes: None
- Maturity: PRODUCTION

## Context

Hook 侧策略消费与 server 侧发布分属不同进程，通过 `DataBus` 文件与 signal 时间戳异步解耦。基线存在两类不一致：其一是发布端逐快照 write+signal 导致 Hook 观察到半批状态；其二是消费端先读快照后取水位，消费期间到达的新一代信号被旧值覆盖确认而永久跳过。

## Decision

1. **批量提交边界**（server）：`SnapshotBatchCommitter` 先逐个原子写入全部快照，全部成功后才统一发送信号；`publishAll / publishStopped / publishStorageRedirectPolicySet` 三个多快照入口全覆盖。
2. **信号游标**（hook）：`consumeAfterSignalCapture` 在读取前捕获水位，消费成功才推进 `max(旧, 捕获值)`，失败不推进；`initFromDataBus` 与 `refreshChangedSnapshotsFromDataBus` 四组消费全接入，保留“未变化跳过 IO”优化。
3. **FUSE 原子交换**：`setMountPoint` 锁外构建新表、异常保留旧表、临界区内 `swap`；`commitPolicy` 单次 JNI 原子应用挂载点集合与记录偏好。

## Consequences

- 启动链路与变更链路的代际回环消除；`SnapshotSignalCursorPolicyTest` 覆盖捕获顺序、失败不推进、回退不倒退等 8 例。
- `buildXposedMainJar` 后续无需为半批窗口加补偿逻辑。
