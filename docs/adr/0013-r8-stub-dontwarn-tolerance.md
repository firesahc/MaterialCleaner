# ADR 0013：R8 桩层级误报三条 dontwarn 容忍决策

- 状态：已被 ADR 0014 取代（2026-09-21，根因为依赖配置错误而非桩链固有，已根治）
- 日期：2026-09-17
- 范围：release 混淆链路，`app/proguard-rules.pro` 手工三条 `dontwarn`，`platform/hidden-api` 桩供给

## 背景（现象）

release 构建开启混淆（`app/build.gradle:37` 处 `minifyEnabled true`），R8 在层级检查阶段报超类缺失类误报。

触发结构是桩供给方式：`platform/hidden-api` 以 library 形态提供 `android.*` 桩类，app 侧 program 类继承这些桩父类。R8 按 program 视角做父类可用性判定，library 桩父类在裁剪视角下被视为不可用，于是子类继承关系被标为误报。报错集中在 release，debug 因未开启混淆不可复现，这是 release 线可复现性缺口。

本决策归档的三条手工规则位于 `app/proguard-rules.pro:23-25`：

```
-dontwarn android.view.ContextThemeWrapper
-dontwarn android.app.Service
-dontwarn android.os.DeadObjectException
```

`app/proguard-rules.pro:22` 注释标明这是 R8 层级实验结论，`app/proguard-rules.pro:26-72` 为 AGP 自动生成部分，与本次决策无关。`shared/consumer-rules.pro` 只保留 `me.gm.cleaner.**`，`core/ipc-contract/consumer-rules.pro` 只保留 AIDL 与 `IInterface` 实现，均不覆盖上述三条，压制点只留在 app 侧。

具体误报日志原文：待补（以某次 release 构建 R8 输出为准，后人补链时请贴完整类名与缺失超类行）。

## 决策

接受三条 `dontwarn` 作为容忍，不做根治。

理由是成本不对称。根治需要动桩生成链，例如调整桩模块的 library 与 program 划分，重排桩类打包与依赖方向，或让 R8 正确识别桩父类可用性。这类改动牵动 hidden-api 桥接、Xposed 运行时打包与 main.jar 构建链，回归面覆盖多版本平台形态。当前三条压制已让 release 构建通过，运行期行为无异常，根治投入与当前收益不成比例。

因此结论是容忍而非根治：保留三条原样，不扩散到其他类，不下沉到 library 模块的 `consumer-rules.pro`，保持压制点单一可见。

## 后果

- release 构建可通过，压制意图沉淀在本 ADR，不再只活在 proguard 注释里。
- 压制点收敛在 app 侧三行，后人可直接引用本 ADR 解释来源。
- 风险：`dontwarn` 会同时压住真实缺类告警。若后续三类之一出现真实缺类，构建不会提前失败，要靠运行期测试与复发信号补位。
- 非目标：本 ADR 不承诺桩链重构时间表，不扩大压制范围。

## 拒绝事项（含理由）

1. **拒绝全量 `-keep`**：曾考虑对 `android.view.*`、`android.app.*`、`android.os.*` 做全量保留。拒绝理由是保留面过大，会直接削弱 release 裁剪与优化效果，且与 `shared`、`ipc-contract` 现有最小保留风格冲突。

2. **拒绝关闭 `minify`**：曾考虑关闭 release 混淆以消除误报。拒绝理由是因小失大，关闭后包体积与优化回退是确定性损失，而误报只是构建期噪声。`app/build.gradle:37` 保持 `minifyEnabled true` 不变。

3. **拒绝逐条细分压制**：曾考虑按缺失超类逐条拆更细的 `-dontwarn` 或 `-keepclassmembers`。拒绝理由是桩父类集合随平台版本漂移，细分规则脆弱且难维护。当前三条为最小可用集合，新增压制需另开决策，不在本 ADR 顺手追加。

## 复发信号（满足任一条即重开本 ADR）

1. 升级 AGP 或 R8 后，release 构建再次出现同类层级误报，或现有三条压制失效。
2. 三条之外的 `android.*` 桩父类出现新的继承误报，需要新增第四条压制。
3. 运行期出现与这三类相关的 `NoClassDefFoundError`、`ClassNotFoundException` 或超类加载失败，说明压制掩盖了真实缺类。

重开时请携带：AGP 与 R8 版本、完整 release 误报日志、`app/proguard-rules.pro` 当前行号、是否复现于干净构建。

## 附录

- 锚点文件：`app/proguard-rules.pro:22-72`、`app/build.gradle:37`、`shared/consumer-rules.pro`、`core/ipc-contract/consumer-rules.pro`。
- 本 ADR 只归档决策，不修改上述任何文件本体。
