# 使用有序类型化存储策略

- Status: Accepted
- Date: 2026-07-26
- Supersedes: None
- Maturity: COMPONENT_TESTED

MaterialCleaner 将存储策略建模为有序的 `MAP/PRESERVE` 重定向序列和独立的 `READ_ONLY/DENY_ALL` 访问策略，不再把新业务语义全部编码成二元挂载规则。`MAP/PRESERVE` 必须复刻正式 v4.0.0 `MountRules` 的精确解释器：先按原始输入选择最后一条匹配 target 的规则，再按索引递增向列表尾部进行链式映射；`PRESERVE` 精确等价该位置的恒等 Pair，不能被当作 no-op 或绝对提前返回。访问策略始终以规范可见路径匹配并按最严格结果归约，映射产生的派生别名只供受限闭包和执行计划使用。

## Consequences

配置、模板和快照必须保留规则类型、重复项、原始字符串和顺序。正式 4.0.0 的旧二元规则只做结构迁移：恒等规则转为 `PRESERVE`，其余转为 `MAP`，不得根据 cache 路径或列表位置反推 Q1-Q4，也不得自动生成 `DENY_ALL`。差分测试必须同时覆盖路径解释、逐项增量 mount point 和 native mount plan；任一非规范项无法证明等价时，隔离该包和 legacy 用户作用域的完整有序序列，不能删除单条后激活其余规则。
