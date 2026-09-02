# 通过配置门面渐进收敛策略来源

- Status: Accepted
- Date: 2026-09-02
- Supersedes: None
- Maturity: COMPONENT_TESTED

本阶段保留 `storage_redirect` 和 `read_only` 两个旧文件格式，但由
`ConfiguredPolicyStore` 作为唯一的策略读取和写入门面。`ServicePreferences` 的旧策略
API 只作为 UI 兼容外观，内部委托该门面；`deny_list` 继续作为独立兼容数据，不转换为
`DenyAllRule`。

重定向和只读是两个独立配置域，各自使用内容寻址 revision：
`SHA-256(规范化后的领域正文)`。revision 只表示配置内容身份，不承担运行时顺序；
快照中的 `generation` 仍只用于传输排序和发布诊断。发布批次使用同一份配置快照，避免
重读文件造成 redirect/read-only 内容来自不同读取时刻。

旧 JSON 在进入领域正文时完成一次 POSIX 词法规范化，保留规则顺序和重复项，恒等 Pair
转换为 `PRESERVE`。无法转换的文件报告 `CORRUPT`，不会静默降级为空策略，也不会继续
发布该配置域。写入继续使用 UTF-8 和原子替换，并保留旧文件名以支持回滚。

运行状态只描述配置到执行器的进度，当前允许 `NO_RULE`、`PENDING`、`APPLYING`、
`APPLIED`、`STALE` 和 `UNSUPPORTED`。`APPLIED` 仅表示执行器接受了正文或本地缓存，
不等价于底层行为已被证明；本阶段不生成 `EFFECTIVE`。

后续只有在旧格式转换一致性、损坏处理和运行回归稳定后，才另行评估统一文件格式、
持久化 projection lineage 以及真实生效探针。
