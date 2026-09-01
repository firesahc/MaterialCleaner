# 将禁止访问路径从挂载规则中拆分

- Status: Accepted
- Date: 2026-07-26
- Supersedes: None
- Maturity: COMPONENT_TESTED

`DENY_ALL` 是独立的目录级访问策略，不属于重定向规则，也不由 cache 影子映射模拟。它只承诺父目录的 VFS `readdir` 可以显示受限名称，MediaStore 不返回受限根或后代行；受限目录根及子树的受控新访问必须返回 `EACCES`。READ_ONLY 与 DENY_ALL 采用“入口 × 操作 × 执行器”的能力闭包；目标 PID 直接 VFS、FUSE 和 MediaStore 等承诺入口均被目标身份操作探针覆盖后，访问投影才可进入有效版本。缺少硬性拒绝原语的平台标记 `UNSUPPORTED`，不得只凭 Hook、挂载点或 Android 版本宣称支持。

## Consequences

普通界面和高级设置都必须把“禁止访问目录”与“重定向”“只读路径”分开。新版只接受目录；不支持 DENY 的设备禁止新建规则，已有规则只读保留并标记未生效。规则变化等待该访问安全域中所有身份仍匹配的相关 PID 自然退出，等待期维持旧 access effective projection；首次启用明确尚未保护。保护承诺排除激活前已打开或继承的文件描述符、目录描述符加 `openat`、外部转交描述符和既有 `mmap`；URI grant 不豁免后续新操作；访问安全域采用最严格策略归约。

MediaStore 旧行或多对一映射无法还原 visible path 时允许访问并标记
`DEGRADED_LEGACY_EXCLUSION`；已由 cutover watermark 证明为激活后产生、但 provenance
缺失或损坏的行属于 `DEGRADED_INTEGRITY`，必须在排序、分页、count 和聚合前过滤，
item 操作拒绝。无法区分这两类来源时不得默认按 legacy 放行，而应把该能力单元标记为
不可用。provenance 至少绑定卷身份、row id、物理身份、access projection 和 cutover
watermark。
