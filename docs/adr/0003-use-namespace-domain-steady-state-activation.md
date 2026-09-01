# 使用挂载激活域采样状态和补偿式激活

- Status: Accepted
- Date: 2026-07-26
- Supersedes: None
- Maturity: COMPONENT_TESTED

重定向以挂载激活域归约状态，但实际变更单位始终是单个挂载命名空间。`ACTIVE` 只断言最近一次进程盘点栅栏捕获且身份仍匹配的全部成员已验证同一 route effective projection；它不是连续监控保证。新成员被观察后创建新盘点修订并退出 `ACTIVE`。跨 PID 应用和恢复采用可持久恢复的补偿 saga，`APPLYING/COMPENSATING` 允许混合视图；补偿无法确认时隔离完整命名空间路由投影，而不是处置单一 PID。

## Consequences

共享进程的重定向策略必须等价；访问安全域只归约 READ_ONLY/DENY_ALL，不作为重定向挂载域。成员身份包含 PID、UID、启动时间、挂载命名空间设备号/inode 和包集合摘要。激活记录使用 `PublisherIncarnation + ActivationEpoch + ActivationRevision + CensusRevision + TransitionId + OperationId` 拒绝陈旧或重复结果，并在 native operation 前重新验证 ownership lease。零成员只能进入 `ARMED_NO_MEMBERS`，首个真实成员完成应用和验证后才能成为 `ACTIVE`。补偿无法确认时进入 `QUARANTINED_DIRTY`，停止该命名空间的后续变更并等待自然退出或设备重启；不强制关闭应用，也不停止 supervisor。
