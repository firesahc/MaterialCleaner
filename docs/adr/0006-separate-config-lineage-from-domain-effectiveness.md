# 将配置血缘与各域生效状态分离

- Status: Accepted
- Date: 2026-07-26
- Supersedes: None
- Maturity: COMPONENT_TESTED

MaterialCleaner 使用不可变配置 revision 表示用户配置血缘，并为挂载激活域和访问安全域分别派生 route/access desired 与 effective projection。两个域独立激活：一域失败不回退已成功的另一域，包运行状态只组合展示为 `PARTIALLY_EFFECTIVE`，不得用单一 generation 或整体 `ACTIVE` 驱动运行时。

不可变配置提交采用 `revision -> PREPARED -> HEAD` 的持久化顺序。revision 文件完成
原子 rename、revision 目录 fsync 和回读后，才能发布并 fsync PREPARED；恢复流程只允许
由有效 PREPARED 推进 HEAD。没有 PREPARED 的孤儿 revision 不代表提交成功，必须清理并
同步目录，不能自动激活。configured、desired 和 effective 分别推进；DataBus 写入、
desired 发布或配置文件可读均不能隐式推进 effective。

## Consequences

旧 effective projection 仍被运行成员或恢复 journal 引用时必须保留完整规则正文。Java、VFS、FUSE 和 MediaStore 只需在同一访问安全域内消费同一 access effective projection；重定向不与其强行同步。零成员只表示下一成员已经布防，不构成运行时保护证据。

同一 subject 的投影版本不得回退或在同版本替换内容；新 publisher incarnation 可以用
相同版本和内容重新确认 desired，但完整 fence 与 payload hash 必须一致。全局配置发生
与某个域无关的变化时，该域沿用原投影版本与内容哈希、同时把 desired 血缘更新到最新
configured revision；不得为追齐全局 revision 伪造新的域版本，也不得把 configured 修订
参与“同版本换内容”的判定。

publisher incarnation 和 operation sequence 必须由持久 ownership 仓库在当前 supervisor
lease 下签发。claim 同时绑定 supervisor token、supervisor PID/starttime、`cleaner_server`
PID/starttime 和 boot identity；同 child 重放幂等，新 child 单调推进 incarnation。每次发布、
native operation 与 ACK 前重验 claim，失效只拒绝策略 mutation，不推断 Root 状态或停止
supervisor。operationId 与规范 payload hash 持久绑定，同 ID 换正文必须在 configured 推进前
fail closed。
## 投影编译约束

- `AllUsers` 必须在一份完整的包身份盘点上展开，因此新建 Android 用户只会在下一次完整编译后进入 desired。
- 完整盘点中尚未安装的配置包保持 dormant，不产生执行投影，也不阻断其他包；后续盘点发现其身份时再展开。
- route 主体是 `(userId, packageName)`；access 主体是 `(userId, appId)`，共享 UID 成员的只读和禁止规则取安全并集。
- 包排除使该包不贡献 route/access 规则；但它不能把包从不可拆分的 shared UID
  执行域移除。若同 UID 的其他成员贡献访问规则，在产品明确接受连带限制前将该 access
  投影视为不可解析冲突，不得激活。
- 同一规则族的 `AllUsers` 与 `SpecificUser` 同时命中同一实际包时，在明确覆盖优先级前拒绝编译。
- 投影内容哈希未变时沿用该主体的投影版本，但仍记录最新 configured revision；内容变化才推进对应域的版本。
- 编译聚合必须携带完整 `sourceRevision`，发布工厂只接受该聚合，不能由调用方另传一个
  同 revisionId、不同 schema/hash/createdAt 的配置身份。
- 旧主体从新结果消失时产生显式 retired 集合，后续激活层必须发布 tombstone，不能依赖“快照中不存在”推断删除。
- tombstone 携带最新 configured revision、前一内容哈希以及从前一投影推进的版本；旧删除
  不能覆盖后来重新创建的主体。
- active 与 tombstone 的版本高水位必须作为独立 projection lineage 持久化；tombstone 后
  重建从高水位加一，不能因旧 projection 正文已删除而回到 v1。lineage 与 previous effective
  证据冲突时整批编译 fail closed。
- desired 发布必须由唯一 canonical factory 生成确定顺序、独立 tombstone hash 和完整批次
  payload hash；空投影结果只观察 configured，不制造非法空发布。
- canonical publisher 必须先把每个 active 解析正文写入不可变正文库，再把正文键写入
  desired slot；effective checkpoint 和 activation journal 同样持有该键。版本与哈希不足以
  在崩溃恢复后重建完整执行正文，tombstone 则不得持有正文键。
- 编译入口只接受 revision repository 回读得到的 `StoredPolicyRevision` 聚合事实，并拒绝
  比 previous projection 更旧的修订以及 map key/subject 错配。
