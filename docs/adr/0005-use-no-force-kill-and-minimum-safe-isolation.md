# 规则故障不强杀应用并采用最小安全隔离单元

- Status: Accepted
- Date: 2026-07-26
- Supersedes: None
- Maturity: COMPONENT_TESTED

MaterialCleaner 对所有重定向和访问规则故障都不强制关闭应用，也不因策略、Binder、Hook、迁移或挂载失败停止 `cleaner_server` supervisor。故障处理隔离能够证明语义独立且一致的最小安全单元，而不承诺永远只禁用单条规则：独立且尚未执行的规则可单独拒绝，有序规则相互影响时隔离完整序列，访问闭包失败时隔离对应访问投影，挂载事务状态不明时隔离完整命名空间路由投影并等待成员自然退出或设备重启。

## Consequences

系统不得通过 force-stop、SIGTERM 或 SIGKILL 应用来完成规则切换或清理脏命名空间。隔离范围不得扩展到无关包、无关命名空间或无关策略域；隔离期间保留仍可证明的旧有效投影，无法证明时明确显示未生效或已隔离，并向用户报告故障范围和恢复条件。
