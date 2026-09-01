# MaterialCleaner 存储策略领域

本上下文定义 MaterialCleaner 对应用共享存储进行路径映射和访问控制时使用的统一语言。术语描述业务含义，不描述 Hook、挂载或数据格式等实现细节。

## 路径与映射

**可见路径（Visible Path）**：
应用发起访问时所使用、尚未执行任何存储策略的路径。
_避免使用_：原路径、真实路径、物理路径

**规范可见路径（Canonical Visible Path）**：
可见路径经过存储根、用户、分隔符和路径段规范化后得到的唯一比较键；它仍是可见坐标，不是物理位置。
_避免使用_：Canonical Path、Real Path、Backing Path

**派生别名（Derived Alias）**：
根据映射策略从规范可见路径派生出的另一个可达路径；它不是独立配置，也不是访问策略的匹配坐标。
_避免使用_：真实路径、第二条规则

**映射（MAP）**：
把一个可见路径子树的访问导向指定承载位置的有序规则。
_避免使用_：普通挂载规则、重定向开关

**保留（PRESERVE）**：
在更宽泛的映射范围内，明确保持某个可见路径子树原有位置的有序例外规则；它不是无意义的恒等映射。
_避免使用_：No-op、Identity Rule、白名单挂载

## 访问策略

**只读路径（Read-only Path）**：
对受控新访问允许读取、查询和遍历，但递归拒绝内容或元数据变更的可见路径子树。
_避免使用_：只读挂载、只读文件

**禁止访问路径（Denied Path）**：
名称可以出现在父目录中，但目录本身及其子树拒绝受控新访问的可见目录子树。
_避免使用_：Q4 挂载、Cache 重定向、Denylist

**受控新访问（Controlled New Access）**：
受保护 UID 在策略激活后，经平台已验证的路径或媒体入口重新解析路径而发起的访问；不包括激活前已经打开、继承或转交的文件描述符、目录描述符派生访问和既有内存映射。
_避免使用_：绝对隔离、所有文件描述符访问

## 包与运行状态

**提示抑制（Prompt Suppression）**：
禁止为指定包生成存储整理提示的用户体验策略；它不改变该包的存储访问能力。
_避免使用_：Denylist、排除包

**包排除（Package Exclusion）**：
使指定包不参与存储映射和相关运行时处理的策略。
_避免使用_：提示黑名单、禁止访问包

**配置策略（Configured Policy）**：
用户已经保存并期望系统执行的策略集合。
_避免使用_：已生效规则、挂载状态

**配置修订（Configured Revision）**：
一次已经完整保存且内容不可变的配置策略版本；它只表示配置血缘，不表示任何运行域已经生效。
_避免使用_：运行代、有效代、快照 generation

**路由投影（Route Projection）**：
配置策略针对指定挂载激活主体派生出的映射与保留规则集合；它拥有独立的期望版本和有效版本。
_避免使用_：全局重定向快照、访问策略

**访问投影（Access Projection）**：
配置策略针对指定访问安全域派生出的只读与禁止访问规则集合；它独立于路由投影生效。
_避免使用_：挂载规则、全局只读快照

**有效策略（Effective Policy）**：
在最近一次已完成且仍具时效性的激活观测中，指定身份域被证明正在执行的策略；重定向与访问保护分别拥有自己的有效策略，不保证尚未被发现的新进程。
_避免使用_：全局生效规则、配置快照、Configured Mount Points

**状态只读模式（Status-only Mode）**：
系统仍可报告升级、配置和运行诊断，但禁止读取为可编辑配置、提交策略或启动策略执行的受限状态。
_避免使用_：服务离线、Root 丢失、只读策略

**期望投影版本（Desired Projection Version）**：
指定身份域从最新配置策略派生出的目标版本；重定向投影和访问投影可以独立等待激活。
_避免使用_：全局期望代、已生效代、当前挂载

**有效投影版本（Effective Projection Version）**：
指定身份域最近已验证并仍在执行的投影版本；首次启用尚未激活时可以不存在。
_避免使用_：全局有效代、最新配置、期望代

**访问安全域（Access Safety Domain）**：
共享同一 Android UID、因调用身份无法可靠区分而必须一致执行访问策略的一组包。
_避免使用_：共享进程、同包

**包身份盘点（Package Identity Census）**：
在同一采集边界内完整记录 userId、packageName 与 appId 关系的证据；只有完整盘点才能展开 AllUsers 并归约 shared UID 访问安全域。
_避免使用_：已安装包列表、零散 PackageManager 查询、进程盘点

**投影编译（Projection Compilation）**：
从一份不可变配置修订和一份完整包身份盘点确定性地产生独立 route/access 投影；作用域含义不明确、路径无法重基或盘点不完整时必须保留旧 effective 投影。
_避免使用_：发布快照、激活成功、配置保存

**保护执行单元（Protection Execution Unit）**：
某个访问保护能力实际能够一致施加且不会区分其内部成员的最小运行边界；它可能与访问安全域交叉，但不拥有配置语义。
_避免使用_：访问安全域、挂载激活域

**共享进程（Shared Process）**：
同一 PID 和挂载命名空间中同时承载多个包的运行实例；只有相关包的有效策略等价时才能执行包级映射。
_避免使用_：共享 UID、包进程

**包运行状态（Package Runtime State）**：
挂载激活域与访问安全域的有效策略、覆盖状态和健康状态在指定包、用户上的展示投影；两类域可以独立生效，它不是运行时变更边界。
_避免使用_：单 PID 状态、服务状态

**部分生效（Partially Effective）**：
包关联的重定向投影与访问投影只有部分达到各自期望版本；成功域继续有效，但包整体不得声明完全生效。
_避免使用_：整体 ACTIVE、全部失败

**完全收敛（Fully Converged）**：
指定包关联的期望投影与有效投影已经逐域一致；它只描述版本收敛，不证明平台能力、保护覆盖或证据时效完整。
_避免使用_：完全保护、设备已验证、FULLY_EFFECTIVE

**完全保护（Fully Protected）**：
访问策略处于有效状态，相关保护能力已经证明可用，覆盖完整且证据仍具时效性的展示结论。
_避免使用_：完全收敛、规则已保存、Hook 已连接

**旧数据例外降级（Degraded Legacy Exclusion）**：
cutover 前的 MediaStore 行或多对一映射无法可靠还原可见路径时，保留访问并明确缩小保护覆盖范围的状态。
_避免使用_：完整保护、不支持、完整性损坏

**完整性降级（Degraded Integrity）**：
已证明在 access cutover 后产生，但 provenance 缺失、损坏或与物理身份不一致；相关查询行需在 SQL 排序分页前过滤，item 操作拒绝。
_避免使用_：旧数据例外、自动放行、不支持

**挂载激活域（Mount Activation Domain）**：
因包与共享进程关系而共同归约重定向状态的一组逻辑成员；每个挂载命名空间是不可拆分的路由执行边界。
_避免使用_：单包事务、访问安全域

**命名空间激活批次（Namespace Activation Batch）**：
针对同一激活成员身份和挂载命名空间、以一个操作共同推进的全部路由变化；该批次只能整体验证、整体生效或整体隔离，不允许按包产生中间有效状态。
_避免使用_：批量保存、逐包激活、多个独立规则事务

**激活成员身份（Activation Member Identity）**：
PID、UID、进程启动时间、挂载命名空间设备号/inode 和包集合摘要的组合；任一字段变化都产生新成员。
_避免使用_：PID、进程名

**进程盘点栅栏（Process Census Fence）**：
一次域成员枚举完成时的单调修订，用于界定 ACTIVE 实际证明的成员集合。
_避免使用_：持续监控、全局快照

**发布者化身（Publisher Incarnation）**：
当前被系统承认有权发布运行时策略和激活结果的服务生命周期身份；随机的新标识本身不代表更新。
_避免使用_：任意 UUID、配置修订、Root 授权

**发布者所有权声明（Publisher Ownership Claim）**：
在持久仓库中绑定当前 supervisor token、supervisor PID/starttime、`cleaner_server` PID/starttime
和 boot identity 后单调签发的发布权限。每次发布、native operation 和 ACK 前都必须重验；
失效只冻结策略 mutation，不代表 Root 丢失，也不停止 supervisor。
_避免使用_：一次性启动检查、Root 心跳、任意更大序号

**发布围栏（Publication Fence）**：
在一个有效发布者化身内单调签发、用于拒绝旧发布者和旧操作结果的发布次序事实；相同围栏只允许同一内容的可靠重放。
_避免使用_：配置修订、随机 generation、发布时间

**恢复证据（Recovery Evidence）**：
对发布前全部激活恢复事实已达到可发布终态的稳定审计身份；它证明检查结果，不等同于恢复执行器或保护生效证据。
_避免使用_：恢复成功日志、空 journal、有效策略

**经验证有效策略检查点（Verified Effective Policy Checkpoint）**：
把完整配置修订、有效状态身份、发布者身份和恢复证据绑定为一个可耐久回读的确认事实；空策略也必须拥有检查点。
_避免使用_：非空规则判断、DataBus 文件、配置 HEAD

**投影血缘（Projection Lineage）**：
独立于当前 projection 正文持久保存的 subject 版本高水位、active/tombstone 类型、内容哈希
和 configured 修订。即使 tombstone 已删除旧正文，同一 subject 重建也必须从高水位继续。
_避免使用_：当前 desired map、旧 projection、配置 revision

**持久准备事实（Durable Prepared Fact）**：
不可变正文已经完成文件与目录持久化、并由独立 PREPARED 标记确认可恢复，但尚未进入 HEAD 的提交阶段；没有 PREPARED 的孤儿正文不是可恢复提交。
_避免使用_：临时文件、已生效策略、DataBus 发布

**升级提交账本（Upgrade Commit Ledger）**：
在从正式 v4.0.0 参考基线执行一次性升级、提交 revision 前，持久绑定 operationId、expected HEAD、规范正文哈希和首次创建时间，并在提交后记录实际 revision 的恢复账本。
_避免使用_：完成 receipt、当前 HEAD、内存幂等标记

**旧 Hook 冷启动边界（Legacy Hook Cold-Boot Boundary）**：
首次观察旧 Hook 迁移风险后，耐久记录当时设备启动身份；只有后续观察到不同启动身份，
才能证明旧启动周期中的 Hook/cache 实例已经消失。同一启动周期内 Binder 缺失、刷新失败
或进程映射未命中都不能形成该证明。
_避免使用_：Hook 未连接、刷新成功、进程列表为空

**共享升级门（Shared Upgrade Gate）**：
App 与 `cleaner_server` 从同一耐久文件读取的、从正式 v4.0.0 参考基线执行一次性升级的状态；只有 `READY` 可读取或
修改旧策略并启动策略运行层。缺失、迁移中、待重启、失败或损坏均进入 status-only，
但不推断 Root 丢失且不停止 supervisor。
_避免使用_：UI 开关、Root 状态、内存 migration flag

**旧版参考基线（Legacy Reference Baseline）**：
仅用于解释和迁移 `ORACLE_TAG=v4.0.0` 对应正式 Git 标签的旧策略语义、fixture 来源、数据与运行边界；它不是新版存储策略的产品版本、实现代号、类型名或命名空间。未发布的中间实现、schema 和目录不属于该基线，也不形成兼容义务。
_避免使用_：新版 v4、当前策略版本、中间实现兼容基线

**旧 Wizard 全局模板（Legacy Wizard Global Template）**：
正式 v4.0.0 默认偏好中保存的全局 Wizard Parcel，包含 Q1/Q2/Q3/Q11/Q12 和四组列表；
Q4 不在旧 wire 中，因此 inaccessible 列表只作为待确认模板输入，不自动生成 DENY_ALL。
_避免使用_：包级 Wizard 草稿、Q4 配置、自动拒绝规则

**旧策略隔离项（Legacy Policy Quarantine）**：
无法证明等价的旧 artifact、包或有序序列及其来源指纹、错误码和诊断；它随不可变
revision 持久保存供手工修复，但不参与投影编译和运行时激活。
_避免使用_：忽略错误、空配置、迁移日志

**解析投影正文键（Resolved Projection Body Key）**：
绑定完整配置修订、完整包身份盘点和解析后 route/access 正文的不可变内容地址；active
desired/effective slot 与激活 journal 必须保存该键，tombstone 不得伪造正文键。
_避免使用_：投影版本、内容哈希、临时缓存路径

**v3 数据库发布（Room v3 Publication）**：
将已经迁移并验证的工作副本耐久安装到独立正式数据库名、完成行数回读和二次打开验证的
提交步骤；它必须先于策略 revision CAS，且不得覆盖或删除正式 v4.0.0 的 v2 原始集合。
_避免使用_：工作副本迁移、Room 打开、数据库版本号

**SQLite 源制品清单（SQLite Source Artifact Manifest）**：
在同一已确认静止边界内，对旧数据库、WAL 和 SHM 分别记录“存在且身份已绑定”或
“缺失且负证明已绑定”的完整集合。首次安装可由三项缺失构成；主数据库缺失但 WAL 或
SHM 孤立存在不是可迁移状态。
_避免使用_：三文件副本、数据库文件列表、已包含 WAL

**稳态一致（Steady-state Consistency）**：
在最近一次进程盘点栅栏记录的成员集合中，所有仍匹配激活成员身份的成员已验证同一有效映射；ACTIVE 是该盘点修订上的有时效采样断言，新进程在被观察前不属于该证明。
_避免使用_：跨 PID 原子、始终一致

**最小安全隔离单元（Minimum Safe Isolation Unit）**：
发生规则故障后，仍能证明内部语义一致且不会影响无关策略的最小隔离范围；只有独立且尚未执行的规则可以是单条规则，其他情况可能是有序规则序列、访问安全域投影或命名空间路由投影。
_避免使用_：永远只处理错误规则、全局停用

**脏命名空间隔离（Quarantined Dirty Namespace）**：
挂载补偿无法确认后，对相关命名空间路由投影停止继续变更并等待成员自然退出或设备重启的状态；它不要求强制关闭应用。
_避免使用_：已回退、杀进程恢复、Root 丢失

**保护能力（Protection Capability）**：
当前平台上已经由目标身份操作验证、能够承载只读或禁止访问语义的执行能力。
_避免使用_：Android 版本支持、Hook 已安装
