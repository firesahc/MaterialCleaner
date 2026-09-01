# 挂载事务回滚与 PID 身份复核

- Status: Accepted
- Date: 2026-08-26
- Supersedes: None
- Maturity: PRODUCTION

## Context

`bind_mount` 是 server 以 root 身份进入目标进程 mount namespace 后执行的全量操作：先重建 `/storage` baseline，再按规则逐条 `MS_BIND|MS_REC`。任何中间失败都会使目标 namespace 处于部分挂载的污染态，且 `fork` 与 `setns` 之间存在 PID 被系统复用的窗口。基线的 fail-fast 直接退出在污染态下无处置，App 侧无分类重试与安全停止语义。

## Decision

1. **事务化回滚**：变异阶段（baseline / bypass / 规则循环）的全部失败路径改走 `rollback_and_fail`，按标志位撤销已产生的 bypass 挂载并调用 `restore_storage_baseline` 重建 baseline；`MNT_DETACH` 的 lazy 语义下“命令成功”不等于“视图一致”，`dataRestrictionModified` 保守判脏。
2. **PID 身份复核**：`read_process_start_time` 解析 `/proc/<pid>/stat` starttime，`read_target_identity` 双读收敛 + UID 校验；`wait_zygote` 后登记 `target_start_time`，`setns` 后二次确认，污染时 `terminate_target_if_same` 仅在身份仍匹配时 SIGKILL。
3. **处置分类**：`MountFailureRetryPolicy.classify` 区分可重试 / 永久失败（EPERM/EACCES/EINVAL 等）/ 污染；污染且 native 未终止时 server 补充 `forceStopPackage` 强停，事件分别映射为 `MOUNT.ROLLBACK_FAILED / MOUNT.IDENTITY.MISMATCH / MOUNT.SAFETY.STOP`。
4. **协议升级**：`MountStatus` 增加 `target_terminated` 与 `schema_version=2`，`namespaceDirty` 保守判定。

## Consequences

- 部分失败不再产生依赖遍历顺序的不确定结果；污染态必伴随可观测的错误码与安全停止。
- `kill -9` / `kill -STOP` 冻结等故障注入均可在真机验证自动恢复。
- 旧 `fail_child` 直退路径全部收敛，无回退分支遗漏需门禁覆盖 `MountFailureRetryPolicyTest`。
