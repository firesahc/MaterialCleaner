# MaterialCleaner — 质感清理

> 一款 Android 存储重定向工具，通过 bind mount + Xposed Hook 实现文件系统级路径重定向。
> 需要 Root（KernelSU / APatch / Magisk）和 LSPosed 框架。
>
> 详细中文文档见 [`MaterialCleaner.wiki/Home.md`](MaterialCleaner.wiki/Home.md)（GitHub Wiki 格式：架构、领域术语、DataBus、三层 Hook、构建部署、诊断排障）。

---

## 功能

- **存储重定向**：拦截应用文件读写，将指定路径重定向到其他目录
- **bind mount 机制**：通过 `mount --bind` 在目标进程 mount namespace 内创建路径映射
- **InsertHooker**：Hook MediaProvider.insertFile()，在 ContentValues 层替换 `_data` 列路径
- **FUSE Native Hook**：通过 xhook 或 embedded GOT patch 拦截 libfuse_jni.so，实现 FUSE 路径兼容
- **DataBus 数据面**：通过文件系统快照和事件队列分发规则、状态、通知和短期会话
- **运行状态诊断**：主界面按 VFS、MediaProvider Hook、FUSE Native Hook、DataBus、控制面展示健康状态
- **文件系统记录**：记录应用的文件操作历史，辅助创建挂载规则
- **挂载规则模板**：预设规则模板，新应用安装时自动应用
- **向导式规则创建**：根据文件系统记录自动建议挂载规则
- **三语言支持**：简体中文、繁体中文、英文

## 架构

### 三进程 + DataBus 数据面

```
┌──────────────────────┐       Binder        ┌──────────────────────┐
│  cleaner_server      │◄───────────────────►│  App Process         │
│  (root, app_process) │ HooksBridgeProvider │  UI / Binder Bridge  │
│  VFS / observers     │                     └──────────┬───────────┘
│  LayerOrchestrator   │                                │ Binder
└──────────┬───────────┘                                ▼
           │ DataBus                          ┌──────────────────────┐
           ▼                                  │  MediaProvider        │
┌────────────────────────────────┐                      │  LSPosed Hook        │
│  /data/local/tmp/cleaner/bus   │◄────────────────────►│  Query/Insert/FUSE   │
│  snapshots/events    │                      │  native status       │
│  leases/status       │                      └──────────────────────┘
└──────────────────────┘
```

控制面 Binder 只承担注册、刷新、诊断兜底；规则快照、挂载点、native 状态、文件事件、重定向提示和 query session lease 通过 DataBus 传递。

### 三层重定向机制

| 机制 | 层 | 原理 | 依赖 |
|------|----|------|------|
| **bind mount** | VFS 层 | `fork() → setns() → mount(MS_BIND)` 在目标进程 namespace 中创建绑定挂载 | root 权限 |
| **MediaProvider Java Hook** | MediaStore 层 | Hook query / insert / scan 相关路径，在 Java 层读取 DataBus 策略快照 | LSPosed |
| **FUSE Native Hook** | FUSE 兼容层 | xhook 或 embedded GOT patch 拦截 libfuse_jni.so，消费 configured_mount_points 快照 | LSPosed + libinline |

### 模块结构

```
MaterialCleaner/
├── app/                         # Android App 主模块（UI / ViewModel / native 打包）
├── core/
│   ├── ipc-contract/            # AIDL 与跨进程模型
│   ├── common/                  # 通用运行时工具
│   ├── config-store/            # 服务配置与安全初始化
│   ├── storage-redirect-domain/ # 存储重定向领域模型与策略推导
│   └── storage-redirect-databus/# DataBus 快照、事件、lease、游标
├── runtime/
│   ├── cleaner-server/          # root server、VFS、observer、consumer、orchestrator
│   └── media-provider-hook/     # LSPosed MediaProvider Hook runtime
├── platform/
│   └── hidden-api/              # Android hidden API 桥接与 SystemService 封装
├── app/src/main/cpp/            # native：libcleaner（VFS/mount）、libinline（FUSE Hook + xhook）、starter
├── app/src/main/assets/         # xposed_init + main.jar（buildXposedMainJar 生成）
├── shared/                      # 历史兼容公共库，迁移中逐步瘦身
├── aidl/                        # 历史 AIDL 目录，迁移源保留
├── server/                      # 历史 server 目录，迁移源保留
├── docs/adr/                    # 架构决策记录（10 篇）
├── scripts/gates/               # 门禁检查脚本
└── MaterialCleaner.wiki/        # GitHub Wiki（中文文档主页）
```

## 环境要求

- **Android 8.0+**（API 26）
- **Root 权限**：KernelSU / APatch / Magisk
- **LSPosed 框架**（用于加载 Xposed 模块到 MediaProvider 进程）
- **NDK**：CMake 3.22.1 + Android NDK 26.1.10909125（版本已 pin，见根 `build.gradle`，不可随意升级）

## 构建指南

### 环境准备

```bash
# Android SDK 路径（Windows）
set ANDROID_HOME=D:\Android\Sdk

# 使用项目自带的 Gradle Wrapper
./gradlew --version
```

### 编译 APK

```bash
# Debug 构建
./gradlew :app:assembleDebug

# Release 构建
./gradlew :app:assembleRelease
```

构建过程会自动执行以下步骤：

1. 编译 `core/*`、`platform/*`、`runtime/*` 模块
2. 运行 `buildXposedMainJar` 任务，将 `runtime:media-provider-hook` 及必要依赖通过 d8 转为 DEX 并打包为 `assets/main.jar`
3. 编译 C++ 本地代码（libinline.so、libcleaner.so、starter）
4. 打包为 APK

### 安装

```bash
# 安装 Debug APK
adb install -r app/build/outputs/apk/debug/Cleaner_*-debug.apk

# 强制重启服务器（安装后需要）
adb shell am force-stop me.gm.cleaner
adb shell am start -n me.gm.cleaner/.client.ui.ServiceSettingsActivity
```

### LSPosed 配置

1. 安装 APK 后打开 LSPosed Manager
2. 启用 MaterialCleaner 模块
3. 作用域勾选当前设备的 MediaProvider 包，常见为 `com.android.providers.media.module`、`com.google.android.providers.media.module` 或 `com.android.providers.media`
4. 重启设备

## 技术栈

| 层级 | 技术 |
|------|------|
| UI | Kotlin + Material Design 3 + DataBinding + Navigation |
| 架构 | ViewModel + LiveData + Flow + Coroutines |
| 进程通信 | Binder AIDL + ContentProvider + DataBus 文件系统数据面 |
| Xposed | LSPosed + Xposed Framework API 82 |
| 本地代码 | C++（xhook / embedded GOT patch、mount namespace 操作） |
| 数据库 | Room + SQLite |
| Root | libsu（topjohnwu） |
| 构建 | Gradle + CMake + d8 |

## 关键技术实现

### main.jar 自动构建

Xposed 模块以 `assets/main.jar`（DEX 格式）打包在 APK 中。Gradle task `buildXposedMainJar` 自动把 Hook runtime 及必要依赖转换为 DEX：

```bash
# 手动方式（如不通过 Gradle）：
d8 --min-api 26 --output /tmp/dex runtime/media-provider-hook/build/intermediates/aar_main_jar/debug/classes.jar
cd /tmp/dex && zip main.jar classes.dex
```

### 服务器进程启动

```
App → Shell.cmd(Starter.command) → start.sh → starter (ELF 二进制)
  → execvp app_process → CleanerServerLoader.main()
    → System.loadLibrary("android|compiler_rt|jnigraphics")
    → CleanerServer() → onStorageManagerServiceReady()
      → LayerOrchestrator.initialize()
      → ObserverManager.startAllObservers()
      → SnapshotPublisher.publishAll()
      → DataBus consumers / status scheduler
      → BinderSender.register() → sendBinderToManger()
```

## 已知限制

- **LSPosed 必需**：MediaProvider Java Hook 和 FUSE Native Hook 需要 LSPosed 加载 Xposed 模块到 MediaProvider
- **定制 ROM 兼容性**：部分定制系统（MIUI/HyperOS）可能因 SELinux 策略导致 bind mount 失败
- **FUSE Native Hook 依赖平台形态**：Android 版本、APEX MediaProvider 与 libfuse_jni.so 加载方式会影响可用 Hook 模式
- **main.jar 需随代码更新**：修改 `runtime/media-provider-hook` 后需重新构建，Gradle task 会自动处理

## 原作者的话

> 由于以下原因，此项目停止开发：
> 
> 这个项目自在 GitHub 发布第一个版本已经两年多了，其中一年多的时间都是在按上班强度开发。我知道这是一个过于小众的需求，注定无法进入大众视野。然而如果所有人都在做大众项目，小的需求谁来满足呢？总有人要做吃亏的那一个。
> 
> 是的，我明白这个就是从一开始就注定要亏本的项目。虽然安卓的设计没有非常强调“文件”的概念（苹果系统的早期版本甚至根本没有“文件”的概念），然而受 Windows 影响，我还是会经常使用文件管理器，一个自由定制文件存放的功能非常令我着迷。以普遍理性而言，找一个程序员定制开发需要每月付至少 2000 多元（按照国内法定最低工资标准），这个功能逻辑较为复杂，不知需要多久才能好用，定制的价格是普通人难以承受的，找一个现成的产品显然更加划算。没错，现成的产品就是存储空间隔离，起初我认为这个应用有着良好的提示和完善的逻辑，然而我使用一段时间后发现操作有些繁琐，而且最令我难以接受的是强制要求导出在二级目录和不能重定向私有目录，所以我决定还是自己做一个。
>
> 两年多时间过去了，我自己的软件终于有了完善的逻辑，也接入了 Google Play 的支付系统。虽然项目收入很低，总收入约 8600 元（相关资料已放在最后的 release 中），平均下来连低保水平都不到，但是看着与我有同样需求的用户付费支持、在群内积极反馈应用问题，自己的需求也得到了满足，我还是非常开心。然而在我项目巨大亏损的情况下，却被酷安大神带节奏，质疑定价太贵。虽然这个应用的功能也许确实会让多数用户认为不值得（也许这也就是手机厂商都不愿意做的原因吧，性价比太低），但是我认为考虑到做小众项目更加困难，70 人民币永久的价格还算合理。然而即便如此，当我看到评论区一些用户也说太贵，我决定还是开源并放出一个完全免费的版本。然而在得罪两百多个付费用户之后，得到的结果是评论区把带节奏的人当做英雄，带节奏之人的回复是“既然有能力改变”，有能力说的是我吗？恐怕是在炫耀有能力轻松地让一个弱小项目破产的你。
>
> 现在这个项目已经大概率要停更了，如果你还在寻找替代品，我还是会推荐存储空间隔离，第一是因为据用户反馈它对各种定制系统有良好的兼容性（这是质感清理现在还没有做到的），第二是因为我不觉得谁还有动力和实力克服众多困难开发下一个替代品了。
