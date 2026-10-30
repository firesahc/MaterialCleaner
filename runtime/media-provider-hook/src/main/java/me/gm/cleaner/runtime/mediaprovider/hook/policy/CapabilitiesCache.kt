package me.gm.cleaner.runtime.mediaprovider.hook.policy

import android.util.Log
import me.gm.cleaner.core.common.err.ErrorCodes
import me.gm.cleaner.core.storage.redirect.databus.DataBusProtocol
import me.gm.cleaner.runtime.mediaprovider.hook.bridge.HookDataBusBridge
import me.gm.cleaner.runtime.mediaprovider.hook.bridge.DataBusBridgeResult
import org.json.JSONObject

/**
 * platform_capabilities 策略域消费组件。
 *
 * 负责读取并解析 platform_capabilities.json 快照，缓存 sdkVersionInt /
 * fuseBpf / fuseAvailable 等关键能力字段。消费过程中会根据解析结果
 * 调用 [NativeHookStatus.markCapabilitiesFresh] / [NativeHookStatus.markCapabilitiesReadUnavailable]
 * / [NativeHookStatus.markPolicyCacheFailed] 记录状态反馈，不属于被动缓存。
 * 状态快照的发布仍由 [NativeHookStatus] 统一负责。
 */
internal class CapabilitiesCache {
    private val TAG = "CapabilitiesCache"

    /** 按域分组的 copy-on-write holder：platform_capabilities 域独立演进 generation/epoch */
    private data class CapabilitiesHolder(
        val sdkVersionInt: Int = 0,
        val isFuseBpfEnabled: Boolean = false,
        val fuseAvailable: Boolean = false,
        val fuseJniLoadMode: String = "UNKNOWN",
        val supportedNativeHookMode: String = "NONE",
        val generation: Long = 0L,
        val publisherEpoch: String = "",
    )

    @Volatile
    private var capabilities: CapabilitiesHolder = CapabilitiesHolder()

    /** platform_capabilities 快照当前代数（供门面诊断登记使用）。 */
    internal val generation: Long get() = capabilities.generation

    /** 缓存的 FUSE BPF 状态，供 MediaProvider Hook 使用 */
    internal val isFuseBpfEnabled: Boolean get() = capabilities.isFuseBpfEnabled
    /** 缓存的 FUSE 可用性，供 MediaProvider Hook 使用 */
    internal val fuseAvailable: Boolean get() = capabilities.fuseAvailable
    /** 是否已经成功加载 platform_capabilities 快照 */
    internal val platformCapabilitiesLoaded: Boolean get() = capabilities.generation > 0
    /** 缓存的 SDK 版本，仅用于诊断日志 */
    internal val sdkVersionInt: Int get() = capabilities.sdkVersionInt
    internal val fuseJniLoadMode: String get() = capabilities.fuseJniLoadMode
    internal val supportedNativeHookMode: String get() = capabilities.supportedNativeHookMode

    /** 从 DataBus 加载 platform_capabilities.json 并缓存关键能力字段。 */
    internal fun loadPlatformCapabilities(): SnapshotConsumeOutcome = when (
        val read = HookDataBusBridge.readSnapshot(DataBusProtocol.SNAPSHOT_PLATFORM_CAPABILITIES)
    ) {
        is DataBusBridgeResult.Unavailable -> SnapshotConsumeOutcome(
            succeeded = false,
            unavailableReason = "${read.operation}:${read.reason}",
        )
        is DataBusBridgeResult.Available -> {
            val json = read.value
            if (json == null) {
                Log.d(TAG, "loadPlatformCapabilities: no snapshot available")
                NativeHookStatus.markCapabilitiesReadUnavailable(
                    IllegalStateException("platform_capabilities snapshot missing"),
                )
                NativeHookStatus.markPolicyCacheFailed(
                    ErrorCodes.HOOK_JAVA_CACHE_SNAPSHOT_MISSING,
                    "platform_capabilities snapshot missing",
                    capabilities.generation,
                    NativeHookStatus.POLICY_DOMAIN_CAPABILITIES,
                )
                SnapshotConsumeOutcome(succeeded = false)
            } else try {
                val root = JSONObject(json)
                val generation = root.optLong("generation", 0L)
                val publisherEpoch = root.optString("publisherEpoch", "")
                val current = capabilities
                if (!shouldAcceptSnapshot(
                        publisherEpoch,
                        generation,
                        current.publisherEpoch,
                        current.generation,
                    )) {
                    if (generation == current.generation && publisherEpoch == current.publisherEpoch) {
                        NativeHookStatus.markCapabilitiesFresh(publisherEpoch, generation)
                    }
                    SnapshotConsumeOutcome(succeeded = true, changed = false)
                } else {
                    val next = CapabilitiesHolder(
                        sdkVersionInt = root.optInt("sdkVersionInt", 0),
                        isFuseBpfEnabled = root.optBoolean("isFuseBpfEnabled", false),
                        fuseAvailable = root.optBoolean("fuseAvailable", false),
                        fuseJniLoadMode = root.optString("fuseJniLoadMode", "UNKNOWN"),
                        supportedNativeHookMode = root.optString("supportedNativeHookMode", "NONE"),
                        generation = generation,
                        publisherEpoch = publisherEpoch,
                    )
                    capabilities = next
                    NativeHookStatus.markCapabilitiesFresh(next.publisherEpoch, next.generation)
                    Log.i(TAG, "loadPlatformCapabilities: sdk=${next.sdkVersionInt}, " +
                            "fuseBpf=${next.isFuseBpfEnabled}, fuse=${next.fuseAvailable}, " +
                            "fuseJniLoadMode=${next.fuseJniLoadMode}, " +
                            "nativeHookMode=${next.supportedNativeHookMode}, " +
                            "epoch=${next.publisherEpoch}, generation=${next.generation}")
                    SnapshotConsumeOutcome(succeeded = true, changed = true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadPlatformCapabilities: failed", e)
                NativeHookStatus.markCapabilitiesReadUnavailable(e)
                NativeHookStatus.markPolicyCacheFailed(
                    ErrorCodes.HOOK_JAVA_CACHE_PARSE_FAILED,
                    describeThrowable(e), capabilities.generation,
                    NativeHookStatus.POLICY_DOMAIN_CAPABILITIES,
                )
                SnapshotConsumeOutcome(succeeded = false)
            }
        }
    }

    /** 供 markPolicyCacheFailed 使用的受控异常描述；截断由 NativeHookStatus 统一处理。 */
    internal fun describeThrowable(e: Throwable): String {
        val message = e.message?.takeIf { it.isNotBlank() }
        return if (message == null) e.javaClass.name else "${e.javaClass.name}: $message"
    }
}
