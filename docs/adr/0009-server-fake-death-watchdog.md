# 服务端假死监督与看门狗恢复

- Status: Accepted
- Date: 2026-08-26
- Supersedes: None
- Maturity: PRODUCTION

## Context

`cleaner_server` 的存活不能仅靠 Binder 死亡回调判断。进程假死（存活但 Binder 无响应）不会触发 `DeathRecipient`，且同步 `pingBinder()` 可能永久阻塞监督方。本分支的 `ServerStateMachine` 已具备崩溃恢复（`crashCount` 上限与指数退避）与代际防护（`launchGeneration`），但缺少主动假死探测。

## Decision

1. **带超时的存活探测**：`CleanerClient.pingBinderWithTimeout(3s)` 将同步 Binder 调用隔离到专用守护串行线程并限时等待，超时返回 `false` 而非阻塞。
2. **看门狗循环**：`ServerStateMachine` 常驻 `WATCHDOG_INTERVAL_MS=5s` 周期，仅在目标态为运行且状态机已进入 `RUNNING` 时探测；连续 `WATCHDOG_FAILURE_THRESHOLD=3` 次超时判定假死。
3. **处置闭环**：判定后记 `SUP.WATCHDOG.RESTART` 事件（`ClientErrorJournal`，与启动失败事件同格式），强停进程（`killServerProcess` 已做 cmdline 身份校验）并走既有 `recoverIfTargetRunning(RECOVERY)` 退避重启；任一次成功探测即复位计数。
4. **不引入常驻 root 监督进程**：当前 App 进程侧状态机已覆盖死亡与假死恢复；`starter` 常驻化与四个 policy 头文件的完整移植推迟至确有“App 长期死亡后 server 自治”需求时再议。

## Consequences

- `kill -STOP` 冻结等故障注入可在真机验证 15-20 秒内自动恢复。
- 看门狗为最后防线，正常恢复路径（如 `kill -9` 死亡）仍由更快的 Binder 死亡回调抢先完成。
- 监督域事件与挂载处置事件共享同一错误码体系与诊断导出通道。
