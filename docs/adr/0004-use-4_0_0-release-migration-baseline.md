# 仅以正式 4.0.0 标签定义迁移语法基线

- Status: Accepted
- Date: 2026-07-26
- Supersedes: None
- Maturity: COMPONENT_TESTED

新版只以 `ORACLE_TAG=v4.0.0`（Git 提交 `4927e9e42156dac367ac32943cb224ee5807c3ab`）定义 legacy wire grammar，不声称能证明数据的历史来源，也不支持新版数据库直接降回 4.0.0。版本号只保留在 oracle 标签、来源 fixtures 和明确历史事实中，不进入通用类型名、目录或命名空间；通用旧版适配类型使用 `Legacy*`，不使用 `LegacyV4*`。当前 effective wire 因根发布围栏与恢复证据使用 `schemaVersion=2`，未发布的 schema 1 中间格式严格拒绝且不形成兼容义务。未发布的中间实现、schema、目录和类型不形成兼容义务，可以整体替换；新版实现包统一命名为 `policy`。旧 Pair 按原字符串、重复项和顺序迁入并复刻旧解释器，不推断 Q1-Q4；无法证明等价的非规范路径隔离完整有序序列。旧 Pair 和 read-only 保留正式 v4.0.0 的 AllUsers/主外部卷动态投影；旧只读自动升级为递归规则并提示保护范围扩大；旧 denylist 拆为提示抑制和包排除，历史记录重新显示。Room 仅提供 v2 到 v3 迁移。

## Consequences

正式 v4.0.0 是 direct daemon，不得假设它具备新版 supervisor handoff。迁移协调器必须先确认旧数据库写入者退出，再在普通 getter、UI 或 observer 初始化前取得迁移锁；以 drain 后静止的离线 artifact-set 建立包含已提交 WAL 状态的一致 v2 工作副本。专用 legacy Parcel decoder 恢复全局模板的 Q1/Q2/Q3/Q11/Q12 与四组列表；Q4 不猜测，旧 inaccessible 列表只作为待用户确认的模板输入。无法证明等价的旧输入以结构化 quarantine 随 revision 保留，不得静默丢弃。事件 v2 与 Room v3 schema 必须先冻结，再执行唯一 v2 到 v3 迁移。

App 与 `cleaner_server` 必须在触碰三个旧策略文件或普通 Room 前读取同一共享升级门；仅
`READY` 放行。其他状态进入 status-only，继续监督 `cleaner_server` 并提供控制 Binder，
但不启动 Hook、策略发布、observer 或数据库。v3 工作副本迁移成功不等于升级完成：
协调器必须先把它耐久发布到独立 `filesystem-v3.db`、验证行数和二次打开，再提交策略
revision；正式 v4.0.0 的 `filesystem.db` 及 WAL/SHM 原始集合永久保留。无法确认旧写入者或
旧 Hook drain 时进入 `PENDING_REBOOT_UNCONFIRMED`，禁用新策略编辑和启用入口，但保留
状态与恢复说明界面，并承认旧状态可能持续到重启。

drain 结论必须来自同一轮完整身份 census：任何同正式 v4.0.0 可执行血缘的 direct daemon、
任何旧 FileSystemObserver/Room writer、任何旧 Hook/cache 身份仍存在时都不得确认退出。
cleaner_server census 必须保留 direct/supervised 启动分类，不能把裸 PID 进程集合交给
血缘判断；collector 只纳入已证明属于 MaterialCleaner 的候选，其中任何
`LEGACY_DIRECT` 都阻断 drain，避免第一次空基线与第二次 census 之间新 daemon 穿透。
supervised 分类必须由 state/lease、supervisor 与 child 的 PID/starttime、环境 token
共同证明，任一字段不确定都使 census 不完整。
Binder `exit` 返回、Binder death、void Hook refresh、native generation 或单次失败均只作
诊断，不能替代身份消失证据、推断 Root 授权，也不能停止新版 `cleaner_server`
supervisor。确认快照必须携带 census revision、证据身份和 elapsed realtime 采样点，供
后续迁移锁再次校验。

正式 v4.0.0 Hook 没有 PID/starttime/instance 自证明协议，因此同一 boot 内不能从 Binder
缺失、refresh 结果或 `/proc/maps` 未命中推导 complete empty Hook census。首次观察必须
耐久记录 boot identity；只有后续 cold boot identity 变化，配合完整进程 census，才可
确认旧启动周期的 Hook/cache 已消失。损坏或无法持久化该 epoch 时继续
`PENDING_REBOOT_UNCONFIRMED`。

升级 operation 的进行中 receipt 必须绑定当轮完整 drain proof，禁止在不同 census
证据下沿用同一 operationId 静默重放。完成 receipt 也不是单独的完成证据：快速返回前
必须回读并证明该 operationId 实际提交了 receipt 引用的不可变 revision；仅 HEAD 号相同
或 receipt 自称完成均不足以绕过迁移门控。

revision CAS 前另持久化 operation commit ledger 的 `PREPARED`，绑定 expected HEAD、
规范 envelope contentHash 与首次 createdAt。若 child 在 revision HEAD 落盘后、ledger
完成前崩溃，只能在下一 revision id、contentHash、createdAt 和完整 envelope 全部相等时
恢复为同一 operation；否则保留失败状态，不能把外部 HEAD 推进冒充迁移提交。

Android 8+ 生产迁移不把普通文件复制称为 SQLite backup。完整 drain 后应建立离线
artifact-set 快照：对 db/wal/shm 每项记录 `PRESENT` 或 `ABSENT_PROVEN`，复制存在的
持久制品到 operation 私有工作区，在工作副本吸收 committed WAL，并在复制前后重新验证
fresh drain 与源身份。SHM 可合法缺失且可重建；首次安装可由三项缺失证明形成空 v3。
主数据库缺失但 WAL/SHM 孤立存在时 fail closed。正式 v4.0.0 原始 db/wal/shm 无论存在与否
都不得被覆盖、删除或原位 checkpoint。
