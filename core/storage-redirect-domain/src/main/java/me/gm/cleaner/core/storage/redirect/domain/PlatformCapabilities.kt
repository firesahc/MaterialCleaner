package me.gm.cleaner.core.storage.redirect.domain

/**
 * 平台能力探测快照。
 *
 * 隔离 Android 版本、厂商变体和系统特性差异。
 * 由 [PlatformCapabilitiesDetector] 在 server 端采集，通过 DataBus 发布。
 *
 * 所有三层拦截能力都从该快照获知平台状态，而非分散在各处判断版本号。
 * 各层只消费自己需要的字段：
 * - VFS Layer 查询 [isFuseBpfEnabled] 决定 mount 策略
 * - MediaProvider Java Hook Layer 查询 [fuseAvailable] 决定是否注册 query/FUSE Hook
 * - FUSE Native Hook Layer 查询 [isFuseBpfEnabled] 决定 BPF 兼容行为
 *
 * @property schemaVersion 快照结构版本（当前为 1）
 * @property generation 快照代数
 * @property createdAt 快照创建时间戳
 * @property publisher 发布者标识
 * @property sdkVersionInt API level（如 33, 34, 35）
 * @property isFuseBpfEnabled 是否启用 FUSE BPF（ro.fuse.bpf.is_running 等）
 * @property fuseAvailable 是否启用 FUSE（SDK >= 30 或 persist.sys.fuse=true）
 * @property usesSdcardfs 是否使用 sdcardfs
 * @property hyperOsVariant 是否为 HyperOS 变体
 * @property specialAndroidDataHandlingRequired 是否需要特殊 Android/data 处理
 * @property xhookSymbolsAvailable Hook 符号是否可用（libfuse_jni.so 是否存在）
 */
data class PlatformCapabilities(
    val schemaVersion: Int = 1,
    val generation: Long = 0L,
    val createdAt: Long = 0L,
    val publisher: String = "",
    val sdkVersionInt: Int = 0,
    val isFuseBpfEnabled: Boolean = false,
    val fuseAvailable: Boolean = false,
    val usesSdcardfs: Boolean = false,
    val hyperOsVariant: Boolean = false,
    val specialAndroidDataHandlingRequired: Boolean = false,
    val xhookSymbolsAvailable: Boolean = false,
)
