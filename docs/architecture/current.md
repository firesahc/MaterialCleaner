# 当前架构事实源

> 本文档为唯一架构事实源，以生产代码、调用链、构建依赖与测试为准。
> `CONTEXT.md` 为术语表（非规范），`docs/adr` 为历史决策记录（状态见
> `docs/architecture/adr-status.md`），与本文冲突处以本文为准。

## 模块与依赖方向

```text
:app → core:* + runtime:*（组装）
:core:storage-redirect-domain 出度 0（零 implementation 依赖）
:core:storage-redirect-databus 零 project 依赖（仅 android.os 收发 uid、org.json 校验）
:core:config-store → domain（持久化与兼容适配）
:runtime:cleaner-server → ipc/common/config-store/domain/databus/hidden-api
:runtime:media-provider-hook → ipc/common/domain/databus/hidden-api（与 server 互斥）
```

## 主链

```text
App（编辑意图，经 Binder 受控触发 remount）
  → ConfiguredPolicyStore（配置事实源，CAS，双文件）
  → RuntimePolicyProjector（唯一投影口，CORRUPT 熔断，generation 自增在校验后）
  → RedirectPolicySnapshot{storage, behavior}（逻辑分拆，聚合发布，同代原子）
  → VfsRuntimePolicy / HookPolicyCache / FuseMountPoints（各域独立投影缓存）
  → Mounter / Insert-Fuse（Query 仅记录） / Mount.cpp
  → DataBus（transport only：快照/信号/事件/cursor/lease/原子写/权限/健康）
```

## 所有权

* 只有 Store 决定保存了什么；只有 Policy 决定规则是什么意思
  （`OrderedRedirectInterpreter`，`MountRules` 仅剩界面可达分析与过渡兜底）。
* 只有 Projector 决定如何变成运行时；只有各执行端决定怎么执行。
* 记录判定归 `RuntimeBehaviorPolicy`；VFS 只做视图委托。
* 兼容读写收拢于 `LegacyStoragePolicyAdapter`，调用方迁移后删除。

## 禁止箭头（门禁 G1 文件级强制）

```text
domain → android / databus / config / runtime / client / platform / api
databus → domain / config / common / runtime / client / api
Hook → server 实现；server → Hook 实现
App → DataBus 直写；server/hook → legacy JSON 直读（经 Store）
Hook/Mounter → 第二套 redirect 解释（经 MountPlanDeriver）
```

## 已知兼容层（只减不增）

`LegacyStoragePolicyAdapter` 双轨、`MountRules` 界面分析与兜底、
`MountWizard` 旧 Parcel 5-bool、`read_snapshot` 非 Safe 热路径（Hook try-parse 兜底）。
