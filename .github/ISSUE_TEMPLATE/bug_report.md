---
name: Bug report
about: 报告一个缺陷，请先附上诊断包
title: "[Bug report] "
labels: ''
assignees: ''

---

<!-- 注意：标题必须以 [Bug report] 开头，否则会被自动关闭 -->

**问题描述**
清晰简要地描述 bug 是什么（中文即可）。

**复现步骤**
1. 打开 '...'
2. 点击 '....'
3. 看到错误 '....'

**运行状态（必填，请从应用内状态页逐项粘贴）**
- VFS：
- MediaProvider Java Hook：
- FUSE Native Hook：
- DataBus：
- 控制面：
- 总览（HEALTHY / DEGRADED）：

**运行环境（请完整填写）**
- Device: [e.g. Xiaomi 14]
- OS: [e.g. Android 14]
- Root 方案及版本: [e.g. KernelSU 1.0 / APatch / Magisk 28]
- LSPosed 版本及模块启用状态: [e.g. 已启用，作用域已勾选 com.android.providers.media.module / 未启用]
- Cleaner Version: [e.g. 4.1.0]

**诊断包（必填）**
请从应用内导出诊断包并随 issue 上传（设置 → 导出诊断包）。
诊断包内已包含 `summary_zh-CN`、五层运行状态、DataBus 健康与关键日志，
无诊断包且无法在维护者环境复现的 bug 将被直接关闭。

**挂载规则（如与重定向相关，请附上）**
- 涉及包名：
- 规则截图（源 → 目标）或文字描述：

**截图**
如有助于说明问题可补充截图（注意：截图链接会过期，关键信息请以文字/诊断包为准）。

**补充说明**
其他相关信息。
