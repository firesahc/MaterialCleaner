package me.gm.cleaner.runtime.mediaprovider.hook

import android.util.Log
import me.gm.cleaner.core.common.err.ErrorCodes

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

    fun applyConfiguredMountPoints(
        points: Array<String>,
        generation: Long,
        redirectRevision: String,
    ) {
        NativeHookStatus.markMountPointsApplyStarted(redirectRevision)
        var unsupported = false
        try {
            if (disableInlineIfUnsupportedByPlatform()) {
                unsupported = true
                val unsupportedError = IllegalStateException(
                    "Native hook disabled by PlatformCapabilities, state=" +
                            NativeHookStatus.currentInlineState()
                )
                NativeHookStatus.markMountPointsApplyUnsupported(
                    redirectRevision,
                    points.size,
                    unsupportedError,
                )
                throw unsupportedError
            }
            if (!retryInlineHookInitialization()) {
                throw IllegalStateException(
                    "Native hook not ready, state=" + NativeHookStatus.currentInlineState()
                )
            }
            // 单次 JNI 调用原子应用两维度，消除分次调用的不一致窗口。
            InlineHookConfig.commitPolicy(
                points, HookPolicyCache.recordExternalAppSpecificStorage
            )
            NativeHookStatus.markMountPointsApplySucceeded(
                generation,
                points.size,
                redirectRevision,
            )
            Log.i(TAG, "applyConfiguredMountPoints: count=${points.size}, generation=$generation")
        } catch (t: Throwable) {
            if (!unsupported) {
                NativeHookStatus.markMountPointsApplyFailed(
                    generation,
                    points.size,
                    redirectRevision,
                    t,
                )
            }
            Log.e(TAG, "applyConfiguredMountPoints failed: count=${points.size}, generation=$generation", t)
            throw t
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
                // 记录偏好变化经全量刷新统一应用（commitPolicy 原子生效）。
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
        val statusJson = InlineHookConfig.initializeXHook()
        reportNativeFailureIfAny(statusJson)
        return statusJson
    }

    /**
     * 将 native init() 返回的 lastError 自由文本映射为 [ErrorCodes.HOOK_FUSE_*] 统一错误码，
     * 写入 NativeHookStatus.inline 段；未匹配的文本不强行归类。
     */
    private fun reportNativeFailureIfAny(statusJson: String) {
        val failureCode = mapNativeLastErrorToCode(statusJson) ?: return
        NativeHookStatus.markInlineNativeFailure(failureCode, extractNativeLastError(statusJson))
    }

    internal fun mapNativeLastErrorToCode(statusJson: String): String? {
        val root = runCatching { org.json.JSONObject(statusJson) }.getOrNull() ?: return null
        if (root.optBoolean("coreAvailable", false)) {
            return null
        }
        val message = root.optString("lastError")
        return when {
            message.contains("dlopen", ignoreCase = true) ||
                    message.contains("symbol handle unavailable", ignoreCase = true) ->
                ErrorCodes.HOOK_FUSE_LIB_LOAD_FAILED
            message.contains("GOT hook failed", ignoreCase = true) ->
                ErrorCodes.HOOK_FUSE_GOT_PATCH_FAILED
            message.contains("failed to find", ignoreCase = true) ||
                    message.contains("symbols missing", ignoreCase = true) ->
                ErrorCodes.HOOK_FUSE_SYMBOL_MISSING
            message.contains("FUSE not available", ignoreCase = true) ->
                ErrorCodes.HOOK_FUSE_CAPABILITY_UNAVAILABLE
            else -> null
        }
    }

    private fun extractNativeLastError(statusJson: String): String = runCatching {
        org.json.JSONObject(statusJson).optString("lastError")
    }.getOrDefault("")

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
