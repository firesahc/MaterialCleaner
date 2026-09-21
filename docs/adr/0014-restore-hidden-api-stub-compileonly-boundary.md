# ADR 0014：恢复 hidden-api-stub 独立模块与 compileOnly 边界（取代 0013 的 dontwarn 容忍）

- 状态：已接受，取代 ADR 0013
- 日期：2026-09-21
- 范围：`hidden-api-stub` 独立模块复位，`platform/hidden-api` 只留 bridge+api 并 compileOnly 引用桩，`app/proguard-rules.pro` 手工三条 `dontwarn` 删除

## 背景（根因）

0013 把 R8 层级误报归因为“桩供给方式固有”，接受三条 `dontwarn` 容忍。该归因不成立。真正的根因是 `ecdde3ff`（合并三个模块为单一 hidden-api）在作用域退化时删掉了两处 `compileOnly` 边界：

1. `hidden-system-api/build.gradle` 的 `compileOnly project(':hidden-api-stub')`；
2. `server/build.gradle` 的 `compileOnly project(':hidden-api-stub')`。

合并把 76 个桩源码以 `srcDirs` 直接编进 `platform/hidden-api` 的 classes.jar，桩随 library 产物进入 `app--runtimeOnly-->cleaner-server--implementation-->hidden-api` 的 `releaseRuntimeClasspath`，最终进入 R8 图。R8 按 program 视角判定 library 桩父类不可用，报超类缺失（`ContextThemeWrapper`、`Service`、`DeadObjectException` 三条手工压制就是这样来的）。桩进 R8 图是依赖配置错误，不是桩链固有缺陷。

## 决策（本次恢复）

1. `hidden-api-stub` java-library 模块复位到顶层（`build.gradle`、`.gitignore` 取 `ecdde3ff^` 原文逐字恢复，`settings.gradle` 恢复 `include ':hidden-api-stub'`），76 个桩文件用 `git mv` 搬回并保持包路径；bridge+api 继续合并在 `platform/hidden-api` 内，保留合并的管理收益。
2. `platform/hidden-api` 的 `srcDirs` 删除 `stub` 项，只留 bridge+api，并加回 `compileOnly project(':hidden-api-stub')`。
3. 直接引用桩类的模块补 `compileOnly`：`runtime:cleaner-server` 恢复被删的那一行。`media-provider-hook`、`shared`、`app`、`core/*` 经全量 survey 确认只有反射字符串、自有同名类或间接引用，不加（有才加、无不加）。
4. `app/proguard-rules.pro` 删除 0013 归档的三条手工 `dontwarn` 及其实验注释（22-25 行），26 行起 AGP 生成段不动。
5. 附带恢复 `android/content/IIntentReceiver.java`（`ecdde3ff^` 原文）：`c041eafd` 为解决 d8 重复定义删过它，改由 aidl 供给；独立模块下 stub 自身编译需要它。复发不了当年的 duplicate——stub 是 compileOnly，不进任何 d8 输入，`mainJarInputs` 映射与锚点类清单保持不动。

## 编译期桩优先（AGP 行为记录）

仅声明 `compileOnly` 不足以让 `platform/hidden-api` 编译通过。实测 `compileDebugJavaWithJavac` 的 javac 参数中 AGP 把 `android.jar` 硬编码在 `-classpath` 首位，jar 形态的桩会被 android.jar 阉割版遮蔽：公开桩类的隐藏成员（`UID_OBSERVER_ACTIVE`、`UserHandle(int)`、`PackageOps` 等）解析失败。合并前时代能工作，是因为 bridge 是没有 android.jar 的 java-library 模块。这是 `platform/hidden-api/build.gradle` 末尾那段 JavaCompile classpath 重排的原因：只把 `hidden-api-stub.jar` 提前到 android.jar 之前，不改变任何依赖归属，桩仍不进 `releaseRuntimeClasspath` 与 R8 图。server 不需要这段，其桩引用恰好避开阉割成员，`compileOnly` 一行即够。

## 后果

- 桩不再进入 R8 图，0013 的三条压制失去存在前提，已删除；R8 最终验证由 release 构建确认。
- `hidden-api` 的 AAR 产物不再含桩类，下游只能通过显式 `compileOnly` 取桩，边界 fail-loud。
- 风险：AGP 若改变 android.jar 首位行为，重排段可能多余但无害（filter 无匹配时顺序不变）。

## 拒绝事项（含理由）

1. **拒绝改 bridge/api 源码适配阉割版**：隐藏成员调用是运行期真实需求，改源码等于自废功能。
2. **拒绝把 stub 改 implementation 蒙混过编译**：那样桩重回 runtimeClasspath，误报复发，根治作废。
3. **拒绝恢复 hidden-api-bridge 独立模块**：合并的管理收益保留，classpath 重排已解决编译问题，不值得再拆。

## 复发信号（满足任一条即重开本 ADR，沿用 0013 三条并加一条）

1. 升级 AGP 或 R8 后，release 构建再次出现同类层级误报，或现有压制（已删）需要加回。
2. 三条之外的 `android.*` 桩父类出现新的继承误报，需要新增压制。
3. 运行期出现与这三类相关的 `NoClassDefFoundError`、`ClassNotFoundException` 或超类加载失败，说明压制掩盖了真实缺类。
4. 新增对桩类的直接引用后，先查 `releaseRuntimeClasspath` 依赖图确认桩未进 R8 图，再怀疑 R8 本身；R8 问题先查依赖图，后谈压制。

重开时请携带：AGP 与 R8 版本、完整 release 误报日志、`releaseRuntimeClasspath` 中桩产物的有无、`app/proguard-rules.pro` 当前行号、是否复现于干净构建。

## 附录

- 锚点文件：`hidden-api-stub/build.gradle`、`settings.gradle`、`platform/hidden-api/build.gradle`、`runtime/cleaner-server/build.gradle`、`app/proguard-rules.pro`、根 `build.gradle` 的 `xposedRuntimeAnchorClasses`（`api/SystemService` 仍在 hidden-api 内，d8 映射不动）。
- 本 ADR 取代 0013；0013 保留归档，仅状态改为被取代。
