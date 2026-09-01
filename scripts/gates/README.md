# 结构质量门禁（G1-G6）

存储重定向链路的结构性质量门禁：模块依赖、文件粒度、词汇规范、契约一致性的机器化约束。

## 用法

```powershell
# 全量运行（CI 与本地一致）
pwsh scripts/gates/Run-Gates.ps1

# 单项
pwsh scripts/gates/Run-Gates.ps1 -Gate G2

# 生成/更新存量豁免清单（仅在有意接受新存量时使用）
pwsh scripts/gates/Run-Gates.ps1 -InitBaseline
```

退出码：存在 FAIL → `1`（阻断）；WARN/SKIP/PASS → `0`。

## 门禁项

| # | 名称 | 规则 | 动作 |
|---|---|---|---|
| G1 | 模块依赖红线 | domain 出度=0；databus 仅可依赖 domain；hook 与 server 禁止互相依赖 | 违规 FAIL |
| G2 | 文件粒度 | src/main 内 kt/java ≤800 行、cpp/h ≤1200 行；第三方目录（external/、android-base/ 等）排除 | 新增超限或豁免文件增长 → FAIL |
| G3 | 微类密度 | 同目录 ≥5 个 kt 且 <100 行占比 >50% | WARN（不阻断） |
| G4 | 词汇检查 | CONTEXT.md「避免使用」禁词：denylist / ConfiguredMountPoint / configured_mount_points / 全局重定向快照 | 新增命中或计数增长 → FAIL |
| G5 | 契约一致性 | IpcPackageRuntimeState 字符串常量集 == domain enum name 集（类未建立时 SKIP） | 不等 FAIL |
| G6 | 死代码扫描 | 季度手工审查制占位 | SKIP |

## 存量豁免机制

`baseline-violations.txt` 记录门禁启用时点的既有违规快照（文件行数上限与禁词命中计数）。规则：

- **清单外**出现违规 → FAIL（禁止新增）
- **清单内**文件增长 / 命中增加 → FAIL（禁止恶化）
- 重构消除某条存量后，应从清单中**手动删除对应行**（门禁会以 WARN 提示已失效条目）

该清单是有意维护的版本化资产，随代码一起评审——它就是"技术债只减不增"承诺的载体。
