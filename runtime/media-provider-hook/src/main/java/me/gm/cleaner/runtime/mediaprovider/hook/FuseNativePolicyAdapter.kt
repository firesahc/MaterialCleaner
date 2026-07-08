package me.gm.cleaner.runtime.mediaprovider.hook

import android.util.Log

/**
 * FUSE Native Hook 的策略适配器。
 *
 * 负责将 DataBus 中的策略快照同步到 FUSE Native Hook (libinline.so)。
 *
 * ## 数据流
 * ```
 * DataBus configured_mount_points.json
 *   → HookPolicyCache.loadAndPushConfiguredMountPoints()
 *     → FuseNativePolicyAdapter.applyConfiguredMountPoints()
 *       → InlineHookConfig.setMountPoint() [JNI → native]
 * ```
 *
 * ## 刷新路径
 * [HookPolicyCache] 是 configured_mount_points 的唯一分发入口；
 * [HookPolicyRefreshScheduler] 只负责触发 cache 刷新和 native 初始化重试，
 * 不再保留第二条直接读取 DataBus 的分发路径。
 *
 * ## 降级行为
 * - DataBus 快照不可用 → keep 上次有效值（不主动清空 native mountPoint）
 * - 空数组 → 清空 native mountPoint（显式清空操作）
 */
object FuseNativePolicyAdapter {
    private const val TAG = "FuseNativePolicyAdapter"
    private const val MAX_INLINE_RETRY_COUNT = 10
    private val RETRY_DELAYS_MS = longArrayOf(5_000L, 10_000L, 30_000L, 60_000L)

    @Volatile
    private var inlineLibraryLoaded = false

    fun applyConfiguredMountPoints(points: Array<String>, generation: Long) {
        try {
            if (disableInlineIfUnsupportedByPlatform()) {
                throw IllegalStateException(
                    "Native hook disabled by PlatformCapabilities, state=" +
                            NativeHookStatus.currentInlineState()
                )
            }
            if (!retryInlineHookInitialization()) {
                throw IllegalStateException(
                    "Native hook not ready, state=" + NativeHookStatus.currentInlineState()
                )
            }
            applyRecordExternalAppSpecificStorage(HookPolicyCache.recordExternalAppSpecificStorage)
            InlineHookConfig.setMountPoint(points)
            NativeHookStatus.markMountPointsApplySucceeded(generation, points.size)
            Log.i(TAG, "applyConfiguredMountPoints: count=${points.size}, generation=$generation")
        } catch (t: Throwable) {
            NativeHookStatus.markMountPointsApplyFailed(generation, points.size, t)
            Log.e(TAG, "applyConfiguredMountPoints failed: count=${points.size}, generation=$generation", t)
            throw t
        }
    }

    fun applyRecordExternalAppSpecificStorage(value: Boolean): Boolean {
        if (!NativeHookStatus.isInlinePolicyBridgeAvailable()) {
            return false
        }
        return try {
            InlineHookConfig.setRecordExternalAppSpecificStorage(value)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "applyRecordExternalAppSpecificStorage ignored because native hook is unavailable", t)
            false
        }
    }

    fun retryInlineHookInitializationIfDue() {
        if (disableInlineIfUnsupportedByPlatform()) {
            return
        }
        val now = System.currentTimeMillis()
        if (!NativeHookStatus.shouldRetryInlineInitialization(now)) {
            return
        }
        val retryCount = NativeHookStatus.currentInlineRetryCount()
        if (retryCount >= MAX_INLINE_RETRY_COUNT) {
            NativeHookStatus.markInlineRetryExhausted(
                "Inline hook initialization retry exhausted, state=" +
                        NativeHookStatus.currentInlineState()
            )
            return
        }
        try {
            val nativeStatus = initializeInlineHook()
            NativeHookStatus.markInlineLoadSucceeded(nativeStatus)
            if (NativeHookStatus.isInlinePolicyBridgeAvailable()) {
                applyRecordExternalAppSpecificStorage(
                    HookPolicyCache.recordExternalAppSpecificStorage
                )
                HookPolicyCache.tryRefreshNativeMountPoints(force = true)
            } else {
                scheduleNextInlineRetry(now, retryCount)
            }
        } catch (t: Throwable) {
            NativeHookStatus.markInlineLoadFailed(t)
            val delay = scheduleNextInlineRetry(now, retryCount)
            Log.w(TAG, "retryInlineHookInitializationIfDue failed, nextRetry=${delay}ms", t)
        }
    }

    fun initializeInlineHook(): String {
        ensureInlineLibraryLoaded()
        return InlineHookConfig.initializeXHook()
    }

    @Synchronized
    private fun ensureInlineLibraryLoaded() {
        if (inlineLibraryLoaded) {
            return
        }
        System.loadLibrary("inline")
        inlineLibraryLoaded = true
    }

    private fun retryInlineHookInitialization(): Boolean {
        if (NativeHookStatus.isInlinePolicyBridgeAvailable()) {
            return true
        }
        return try {
            val nativeStatus = initializeInlineHook()
            NativeHookStatus.markInlineLoadSucceeded(nativeStatus)
            if (!NativeHookStatus.isInlinePolicyBridgeAvailable()) {
                val now = System.currentTimeMillis()
                if (NativeHookStatus.shouldRetryInlineInitialization(now)) {
                    scheduleNextInlineRetry(now, NativeHookStatus.currentInlineRetryCount())
                }
            }
            NativeHookStatus.isInlinePolicyBridgeAvailable()
        } catch (t: Throwable) {
            NativeHookStatus.markInlineLoadFailed(t)
            scheduleNextInlineRetry(System.currentTimeMillis(), NativeHookStatus.currentInlineRetryCount())
            Log.w(TAG, "retryInlineHookInitialization failed", t)
            false
        }
    }

    private fun disableInlineIfUnsupportedByPlatform(): Boolean {
        if (!HookPolicyCache.platformCapabilitiesLoaded) {
            return false
        }
        val fuseAvailable = HookPolicyCache.fuseAvailableFromCache
        val reason = when {
            !fuseAvailable -> "PlatformCapabilities reports FUSE unavailable"
            HookPolicyCache.supportedNativeHookModeFromCache == "NONE" ->
                "PlatformCapabilities reports native hook unsupported, " +
                        "fuseJniLoadMode=${HookPolicyCache.fuseJniLoadModeFromCache}"
            else -> null
        }
        if (reason == null) {
            if (NativeHookStatus.isInlineDisabledByPlatform()) {
                NativeHookStatus.markInlinePlatformSupported()
            }
            return false
        }
        if (!NativeHookStatus.isInlineDisabled()) {
            NativeHookStatus.markInlineDisabled(
                reason = reason,
                fuseAvailable = fuseAvailable,
                fuseJniLoadMode = HookPolicyCache.fuseJniLoadModeFromCache,
            )
        }
        return true
    }

    private fun scheduleNextInlineRetry(now: Long, retryCount: Int): Long {
        val nextRetryCount = retryCount + 1
        val delay = RETRY_DELAYS_MS[
                retryCount.coerceAtMost(RETRY_DELAYS_MS.size - 1)
        ]
        NativeHookStatus.markInlineRetryScheduled(nextRetryCount, now + delay)
        return delay
    }
}
