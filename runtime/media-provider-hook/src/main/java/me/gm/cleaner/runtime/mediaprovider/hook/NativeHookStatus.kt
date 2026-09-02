package me.gm.cleaner.runtime.mediaprovider.hook

import android.util.Log
import me.gm.cleaner.core.common.err.ErrorCodes
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import org.json.JSONArray
import org.json.JSONObject

object NativeHookStatus {
    private const val TAG = "NativeHookStatus"
    private const val SCHEMA_VERSION = 2

    /** 快照内受控文本（错误详情等）的最大长度，超出截断。 */
    private const val MAX_TEXT_LENGTH = 200

    /**
     * 宿主预期异常（swallowedHostExceptions）计数的快照发布最小间隔。
     * 该路径可能被宿主高频触发（如 query 对特定参数抛 IllegalArgumentException），
     * 窗口内的增量由下一次任意 mark* 发布顺带带出，避免快照通道风暴。
     */
    private const val SWALLOWED_PUBLISH_INTERVAL_MILLIS = 30_000L

    private const val STATE_NOT_LOADED = "NOT_LOADED"
    private const val STATE_INLINE_LOADED = "INLINE_LOADED"
    private const val STATE_FUSE_WAITING = "FUSE_WAITING"
    private const val STATE_HOOK_READY_FULL = "HOOK_READY_FULL"
    private const val STATE_HOOK_READY_CORE = "HOOK_READY_CORE"
    private const val STATE_HOOK_DEGRADED = "HOOK_DEGRADED"
    private const val STATE_HOOK_UNAVAILABLE = "HOOK_UNAVAILABLE"
    private const val STATE_DISABLED = "DISABLED"

    private const val BRIDGE_STATE_IDLE = "IDLE"
    private const val BRIDGE_STATE_REGISTERING = "REGISTERING"
    private const val BRIDGE_STATE_REGISTERED = "REGISTERED"
    private const val BRIDGE_STATE_RETRYING = "RETRYING"
    private const val BRIDGE_STATE_FAILED = "FAILED"

    // 策略状态只描述“配置到执行器”的进度，不代表底层行为已经被探针证明。
    private const val POLICY_STATE_NO_RULE = "NO_RULE"
    private const val POLICY_STATE_PENDING = "PENDING"
    private const val POLICY_STATE_APPLYING = "APPLYING"
    private const val POLICY_STATE_APPLIED = "APPLIED"
    private const val POLICY_STATE_STALE = "STALE"
    private const val POLICY_STATE_UNSUPPORTED = "UNSUPPORTED"
    private const val REDIRECT_EXECUTOR = "MEDIA_PROVIDER_JAVA_HOOK"
    private const val READ_ONLY_EXECUTOR = "MEDIA_PROVIDER_JAVA_HOOK"
    private const val MOUNT_POINTS_EXECUTOR = "FUSE_NATIVE_HOOK"

    @Volatile
    private var mediaProviderHookLoaded = false
    @Volatile
    private var mediaProviderPackageName = ""
    @Volatile
    private var policyCacheInitialized = false
    @Volatile
    private var policyCacheInitializedAt = 0L

    @Volatile
    private var inlineState = STATE_NOT_LOADED
    @Volatile
    private var inlineLibraryLoaded = false
    @Volatile
    private var inlineHookInitialized = false
    @Volatile
    private var inlineRetryCount = 0
    @Volatile
    private var inlineNextRetryAt = 0L
    @Volatile
    private var inlineRetryExhausted = false
    @Volatile
    private var inlineDisabledByPlatform = false
    @Volatile
    private var lastInlineError = ""
    @Volatile
    private var inlineLastFailureCode = ""

    @Volatile
    private var nativeStatus = NativeStatusSnapshot()

    @Volatile
    private var lastMountPointsApplySuccess = false
    @Volatile
    private var mountPointsGeneration = 0L
    @Volatile
    private var lastMountPointsApplyAt = 0L
    @Volatile
    private var lastMountPointsApplyGeneration = 0L
    @Volatile
    private var lastMountPointsApplyCount = 0
    @Volatile
    private var lastMountPointsApplyError = ""
    @Volatile
    private var mountPointsAppliedRevision = ""
    @Volatile
    private var mountPointsLastAttemptRevision = ""
    @Volatile
    private var mountPointsState = POLICY_STATE_PENDING
    @Volatile
    private var mountPointsConfiguredRevision = ""
    @Volatile
    private var mountPointsPublishedRevision = ""
    @Volatile
    private var mountPointsObservedAt = 0L

    @Volatile
    private var redirectAppliedRevision = ""
    @Volatile
    private var redirectAppliedGeneration = 0L
    @Volatile
    private var redirectPolicyState = POLICY_STATE_PENDING
    @Volatile
    private var redirectConfiguredRevision = ""
    @Volatile
    private var redirectPublishedRevision = ""
    @Volatile
    private var redirectLastError = ""
    @Volatile
    private var redirectObservedAt = 0L
    @Volatile
    private var readOnlyAppliedRevision = ""
    @Volatile
    private var readOnlyAppliedGeneration = 0L
    @Volatile
    private var readOnlyPolicyState = POLICY_STATE_PENDING
    @Volatile
    private var readOnlyConfiguredRevision = ""
    @Volatile
    private var readOnlyPublishedRevision = ""
    @Volatile
    private var readOnlyLastError = ""
    @Volatile
    private var readOnlyObservedAt = 0L

    @Volatile
    private var fuseJavaGateStatus = FuseJavaGateStatus()

    // ── policyCache 失败语义（错误码引用 ErrorCodes.HOOK_JAVA_CACHE_*） ──
    @Volatile
    private var policyCacheLastFailureCode = ""
    @Volatile
    private var policyCacheLastFailureGeneration = 0L
    @Volatile
    private var policyCacheLastError = ""
    @Volatile
    private var policyCacheLastGoodGeneration = 0L

    // ── hooks callback binder 桥注册状态机 ──
    @Volatile
    private var bridgeState = BRIDGE_STATE_IDLE
    @Volatile
    private var bridgeLastError = ""
    @Volatile
    private var bridgeAttemptCount = 0
    @Volatile
    private var bridgeLastAttemptAt = 0L

    // ── 受防护 hook（AbstractGuardedHook 子类）熔断观测 ──
    @Volatile
    private var guardedCircuitOpenCount = 0
    @Volatile
    private var guardedLastFailedHook = ""
    @Volatile
    private var guardedLastFailedMethod = ""
    @Volatile
    private var guardedLastFailureType = ""
    @Volatile
    private var guardedTotalOpenTransitions = 0L
    @Volatile
    private var guardedRecoveredCount = 0L
    @Volatile
    private var guardedSwallowedHostExceptions = 0L
    @Volatile
    private var guardedSwallowedLastPublishAt = 0L

    fun markMediaProviderHookLoaded(packageName: String) {
        mediaProviderHookLoaded = true
        mediaProviderPackageName = packageName
        publishSnapshot()
    }

    fun markPolicyCacheInitialized() {
        policyCacheInitialized = true
        policyCacheInitializedAt = System.currentTimeMillis()
        publishSnapshot()
    }

    fun markInlineLoadSucceeded(statusJson: String) {
        val parsed = parseNativeStatus(statusJson)
        nativeStatus = parsed
        inlineLibraryLoaded = true
        inlineHookInitialized = parsed.coreAvailable
        lastInlineError = parsed.lastError
        inlineState = deriveInlineState(parsed)
        inlineDisabledByPlatform = false
        if (inlineState == STATE_DISABLED) {
            inlineRetryExhausted = true
            inlineNextRetryAt = 0L
        }
        if (parsed.coreAvailable) {
            inlineRetryCount = 0
            inlineNextRetryAt = 0L
            inlineRetryExhausted = false
            // 核心符号全部就位：清除 FUSE 域失败码。
            inlineLastFailureCode = ""
        } else if (inlineLastFailureCode.isBlank()) {
            // 初始化完成但核心不可用且尚无失败码：由调用方经 markInlineNativeFailure 补充；
            // 此处兜底标记为平台能力问题，避免快照出现"未初始化却无原因"的盲区。
            inlineLastFailureCode = ErrorCodes.HOOK_FUSE_CAPABILITY_UNAVAILABLE
        }
        publishSnapshot()
    }

    /**
     * 记录 FUSE native 初始化的结构化失败原因。
     * 由 [FuseNativePolicyAdapter] 依据 init() 返回的 statusJson.lastError 映射
     * [ErrorCodes.HOOK_FUSE_*] 后调用；lastError 文本仍保留用于人读诊断。
     */
    fun markInlineNativeFailure(code: String, detail: String) {
        inlineLastFailureCode = code
        if (detail.isNotBlank()) {
            lastInlineError = detail.take(MAX_TEXT_LENGTH)
        }
        publishSnapshot()
    }

    fun markInlineLoadFailed(error: Throwable) {
        inlineLibraryLoaded = false
        inlineHookInitialized = false
        nativeStatus = NativeStatusSnapshot(lastError = describe(error))
        lastInlineError = describe(error)
        inlineState = STATE_NOT_LOADED
        inlineDisabledByPlatform = false
        // 动态库加载/JNI 注册层失败：区别于符号缺失类失败。
        inlineLastFailureCode = ErrorCodes.HOOK_FUSE_LIB_LOAD_FAILED
        publishSnapshot()
    }

    fun markInlineRetryScheduled(retryCount: Int, nextRetryAt: Long) {
        inlineRetryCount = retryCount
        inlineNextRetryAt = nextRetryAt
        inlineRetryExhausted = false
        if (inlineState == STATE_NOT_LOADED || inlineState == STATE_INLINE_LOADED) {
            inlineState = STATE_FUSE_WAITING
        }
        publishSnapshot()
    }

    fun markInlineRetryExhausted(error: String) {
        inlineRetryExhausted = true
        inlineNextRetryAt = 0L
        lastInlineError = error
        inlineState = STATE_HOOK_UNAVAILABLE
        inlineDisabledByPlatform = false
        publishSnapshot()
    }

    fun markInlineDisabled(
        reason: String,
        fuseAvailable: Boolean,
        fuseJniLoadMode: String = "UNKNOWN",
    ) {
        inlineLibraryLoaded = false
        inlineHookInitialized = false
        inlineRetryExhausted = true
        inlineNextRetryAt = 0L
        lastInlineError = reason
        inlineState = STATE_DISABLED
        inlineDisabledByPlatform = true
        nativeStatus = NativeStatusSnapshot(
            fuseAvailable = fuseAvailable,
            hookMode = "NONE",
            fuseJniLoadMode = fuseJniLoadMode,
            lastError = reason,
        )
        publishSnapshot()
    }

    fun resetInlineRetryState() {
        inlineRetryCount = 0
        inlineNextRetryAt = 0L
        inlineRetryExhausted = false
        publishSnapshot()
    }

    fun markInlinePlatformSupported() {
        if (inlineState != STATE_DISABLED) {
            return
        }
        inlineState = STATE_NOT_LOADED
        inlineLibraryLoaded = false
        inlineHookInitialized = false
        inlineRetryCount = 0
        inlineNextRetryAt = 0L
        inlineRetryExhausted = false
        inlineDisabledByPlatform = false
        lastInlineError = ""
        nativeStatus = NativeStatusSnapshot()
        mountPointsState = POLICY_STATE_PENDING
        publishSnapshot()
    }

    fun shouldRetryInlineInitialization(now: Long): Boolean {
        if (inlineRetryExhausted) return false
        if (inlineState == STATE_DISABLED) return false
        if (isInlinePolicyBridgeAvailable()) return false
        return inlineNextRetryAt <= 0L || now >= inlineNextRetryAt
    }

    fun currentInlineRetryCount(): Int = inlineRetryCount

    fun currentInlineState(): String = inlineState

    fun isInlineDisabled(): Boolean = inlineState == STATE_DISABLED

    fun isInlineDisabledByPlatform(): Boolean =
        inlineState == STATE_DISABLED && inlineDisabledByPlatform

    fun isInlinePolicyBridgeAvailable(): Boolean =
        inlineState == STATE_HOOK_READY_FULL ||
                inlineState == STATE_HOOK_READY_CORE ||
                inlineState == STATE_HOOK_DEGRADED

    fun markMountPointsApplySucceeded(
        generation: Long,
        count: Int,
        redirectRevision: String,
    ) {
        lastMountPointsApplySuccess = true
        mountPointsGeneration = generation
        lastMountPointsApplyAt = System.currentTimeMillis()
        lastMountPointsApplyGeneration = generation
        lastMountPointsApplyCount = count
        lastMountPointsApplyError = ""
        mountPointsAppliedRevision = redirectRevision
        mountPointsLastAttemptRevision = redirectRevision
        mountPointsConfiguredRevision = redirectRevision
        mountPointsPublishedRevision = redirectRevision
        mountPointsObservedAt = System.currentTimeMillis()
        mountPointsState = when {
            count == 0 -> POLICY_STATE_NO_RULE
            redirectRevision.isBlank() -> POLICY_STATE_PENDING
            else -> POLICY_STATE_APPLIED
        }
        publishSnapshot()
    }

    fun markMountPointsApplyStarted(redirectRevision: String) {
        mountPointsLastAttemptRevision = redirectRevision
        mountPointsConfiguredRevision = redirectRevision
        mountPointsPublishedRevision = redirectRevision
        mountPointsObservedAt = System.currentTimeMillis()
        mountPointsState = POLICY_STATE_APPLYING
        publishSnapshot()
    }

    fun markMountPointsApplyUnsupported(
        redirectRevision: String,
        count: Int,
        error: Throwable,
    ) {
        lastMountPointsApplySuccess = false
        lastMountPointsApplyAt = System.currentTimeMillis()
        lastMountPointsApplyGeneration = 0L
        lastMountPointsApplyCount = count
        lastMountPointsApplyError = describe(error)
        mountPointsLastAttemptRevision = redirectRevision
        mountPointsConfiguredRevision = redirectRevision
        mountPointsPublishedRevision = redirectRevision
        mountPointsObservedAt = System.currentTimeMillis()
        mountPointsState = POLICY_STATE_UNSUPPORTED
        publishSnapshot()
    }

    fun markMountPointsApplyFailed(
        generation: Long,
        count: Int,
        redirectRevision: String,
        error: Throwable,
    ) {
        if (mountPointsState == POLICY_STATE_UNSUPPORTED) {
            // 平台明确不支持时，适配器和上层消费器可能各自记录一次失败；
            // 保留 UNSUPPORTED，不能被通用异常路径降级成普通 PENDING。
            return
        }
        lastMountPointsApplySuccess = false
        lastMountPointsApplyAt = System.currentTimeMillis()
        lastMountPointsApplyGeneration = generation
        lastMountPointsApplyCount = count
        lastMountPointsApplyError = describe(error)
        mountPointsLastAttemptRevision = redirectRevision
        mountPointsConfiguredRevision = redirectRevision
        mountPointsPublishedRevision = redirectRevision
        mountPointsObservedAt = System.currentTimeMillis()
        mountPointsState = if (mountPointsAppliedRevision.isNotBlank()) {
            POLICY_STATE_STALE
        } else {
            POLICY_STATE_PENDING
        }
        publishSnapshot()
    }

    /** Java Hook 已接受重定向正文；这不是行为层面的 EFFECTIVE 证明。 */
    fun markRedirectPolicyApplied(revision: String, generation: Long, hasRules: Boolean) {
        val observedAt = System.currentTimeMillis()
        redirectConfiguredRevision = revision
        redirectPublishedRevision = revision
        redirectAppliedRevision = revision
        redirectAppliedGeneration = generation
        redirectLastError = ""
        redirectObservedAt = observedAt
        redirectPolicyState = when {
            !hasRules -> POLICY_STATE_NO_RULE
            revision.isBlank() -> POLICY_STATE_PENDING
            else -> POLICY_STATE_APPLIED
        }
        publishSnapshot()
    }

    fun markRedirectPolicyFailed(revision: String, error: String) {
        redirectConfiguredRevision = revision
        redirectPublishedRevision = revision
        redirectObservedAt = System.currentTimeMillis()
        redirectLastError = error.take(MAX_TEXT_LENGTH)
        redirectPolicyState = if (redirectAppliedRevision.isNotBlank()) {
            POLICY_STATE_STALE
        } else {
            POLICY_STATE_PENDING
        }
        publishSnapshot()
    }

    /** Java Hook 已接受只读正文；这不是行为层面的 EFFECTIVE 证明。 */
    fun markReadOnlyPolicyApplied(revision: String, generation: Long, hasRules: Boolean) {
        val observedAt = System.currentTimeMillis()
        readOnlyConfiguredRevision = revision
        readOnlyPublishedRevision = revision
        readOnlyAppliedRevision = revision
        readOnlyAppliedGeneration = generation
        readOnlyLastError = ""
        readOnlyObservedAt = observedAt
        readOnlyPolicyState = when {
            !hasRules -> POLICY_STATE_NO_RULE
            revision.isBlank() -> POLICY_STATE_PENDING
            else -> POLICY_STATE_APPLIED
        }
        publishSnapshot()
    }

    fun markReadOnlyPolicyFailed(revision: String, error: String) {
        readOnlyConfiguredRevision = revision
        readOnlyPublishedRevision = revision
        readOnlyObservedAt = System.currentTimeMillis()
        readOnlyLastError = error.take(MAX_TEXT_LENGTH)
        readOnlyPolicyState = if (readOnlyAppliedRevision.isNotBlank()) {
            POLICY_STATE_STALE
        } else {
            POLICY_STATE_PENDING
        }
        publishSnapshot()
    }

    fun markFuseJavaGateScanned(
        discoveredCount: Int,
        hookedMethods: List<String>,
        unknownMethods: List<String>,
        failedMethods: List<String>,
    ) {
        fuseJavaGateStatus = FuseJavaGateStatus(
            discoveredCount = discoveredCount,
            hookedMethods = hookedMethods,
            unknownMethods = unknownMethods,
            failedMethods = failedMethods,
        )
        publishSnapshot()
    }

    /**
     * 标记策略缓存一次加载失败。
     *
     * [code] 必须引用 [me.gm.cleaner.core.common.err.ErrorCodes.HOOK_JAVA_CACHE_*]
     * 常量；[generation] 为失败发生时已知的策略代际（与 ErrorEvent.generation
     * 语义一致），仅作诊断对照，不推进 [policyCacheLastGoodGeneration]。
     */
    fun markPolicyCacheFailed(code: String, detail: String?, generation: Long) {
        policyCacheLastFailureCode = code
        policyCacheLastFailureGeneration = generation
        policyCacheLastError = detail?.take(MAX_TEXT_LENGTH) ?: ""
        publishSnapshot()
    }

    /** 标记策略缓存加载成功：清空失败字段并单调推进最后成功代际。 */
    fun markPolicyCacheHealthy(generation: Long) {
        if (generation > policyCacheLastGoodGeneration) {
            policyCacheLastGoodGeneration = generation
        }
        policyCacheLastFailureCode = ""
        policyCacheLastFailureGeneration = 0L
        policyCacheLastError = ""
        publishSnapshot()
    }

    /** hooks callback binder 桥注册开始。 */
    fun markBridgeRegistering() {
        bridgeState = BRIDGE_STATE_REGISTERING
        bridgeLastAttemptAt = System.currentTimeMillis()
        publishSnapshot()
    }

    /** hooks callback binder 桥注册成功：重置失败文本与重试进度。 */
    fun markBridgeRegistered() {
        bridgeState = BRIDGE_STATE_REGISTERED
        bridgeLastError = ""
        bridgeAttemptCount = 0
        bridgeLastAttemptAt = System.currentTimeMillis()
        publishSnapshot()
    }

    /**
     * 重注册重试已调度（风暴抑制路径）。
     *
     * [attempt] 为当前已失败的尝试次数；下次实际尝试将由
     * [markBridgeRegistering] 走 REGISTERING 状态。
     */
    fun markBridgeRetryScheduled(attempt: Int) {
        bridgeState = BRIDGE_STATE_RETRYING
        bridgeAttemptCount = attempt
        bridgeLastAttemptAt = System.currentTimeMillis()
        publishSnapshot()
    }

    /** hooks callback binder 桥注册失败（含重试路径）。 */
    fun markBridgeFailed(error: String) {
        bridgeState = BRIDGE_STATE_FAILED
        bridgeLastError = error.take(MAX_TEXT_LENGTH)
        bridgeLastAttemptAt = System.currentTimeMillis()
        publishSnapshot()
    }

    /**
     * 受防护 hook 熔断打开（进入冷却）。
     * [failureType] 为触发异常的类简名；打开计数与历史转换数同步递增。
     */
    fun markGuardedCircuitOpened(hookName: String, method: String, failureType: String) {
        guardedCircuitOpenCount += 1
        guardedTotalOpenTransitions += 1
        guardedLastFailedHook = hookName
        guardedLastFailedMethod = method
        guardedLastFailureType = failureType.take(MAX_TEXT_LENGTH)
        publishSnapshot()
    }

    /** 受防护 hook 半开探针成功、熔断恢复闭合。 */
    fun markGuardedCircuitRecovered(hookName: String) {
        if (guardedCircuitOpenCount > 0) {
            guardedCircuitOpenCount -= 1
        }
        guardedRecoveredCount += 1
        publishSnapshot()
    }

    /**
     * 宿主抛出的预期异常被静默吞掉（如 query 对特定参数抛出的
     * IllegalArgumentException）：仅递增计数并按最小间隔发布快照，
     * 不改变任何控制流。
     */
    fun markGuardedHostExceptionSwallowed() {
        guardedSwallowedHostExceptions += 1
        val now = System.currentTimeMillis()
        if (now - guardedSwallowedLastPublishAt >= SWALLOWED_PUBLISH_INTERVAL_MILLIS) {
            guardedSwallowedLastPublishAt = now
            publishSnapshot()
        }
    }

    fun publishSnapshot() {
        runCatching {
            val json = toJson()
            if (HookDataBusBridge.writeSnapshot(DataBus.SNAPSHOT_NATIVE_HOOK_STATUS, json)) {
                HookDataBusBridge.signal(DataBus.SIGNAL_NATIVE_HOOK_STATUS_CHANGED)
            }
        }.onFailure {
            Log.w(TAG, "publishSnapshot failed", it)
        }
    }

    fun toJson(): String {
        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("createdAt", System.currentTimeMillis())
            put("publisher", "NativeHookStatus")
            put("mediaProvider", JSONObject().apply {
                put("loaded", mediaProviderHookLoaded)
                put("packageName", mediaProviderPackageName)
            })
            put("policyCache", JSONObject().apply {
                put("initialized", policyCacheInitialized)
                put("initializedAt", policyCacheInitializedAt)
                put("lastFailureCode", policyCacheLastFailureCode)
                put("lastFailureGeneration", policyCacheLastFailureGeneration)
                put("lastError", policyCacheLastError)
                put("lastGoodGeneration", policyCacheLastGoodGeneration)
                put("applicationState", aggregatePolicyState())
                put("redirectState", redirectPolicyState)
                put("readOnlyState", readOnlyPolicyState)
                put("redirectConfiguredRevision", redirectConfiguredRevision)
                put("redirectPublishedRevision", redirectPublishedRevision)
                put("redirectAppliedRevision", redirectAppliedRevision)
                put("redirectAppliedGeneration", redirectAppliedGeneration)
                put("redirectExecutor", REDIRECT_EXECUTOR)
                put("redirectLastError", redirectLastError)
                put("redirectObservedAt", redirectObservedAt)
                put("readOnlyConfiguredRevision", readOnlyConfiguredRevision)
                put("readOnlyPublishedRevision", readOnlyPublishedRevision)
                put("readOnlyAppliedRevision", readOnlyAppliedRevision)
                put("readOnlyAppliedGeneration", readOnlyAppliedGeneration)
                put("readOnlyExecutor", READ_ONLY_EXECUTOR)
                put("readOnlyLastError", readOnlyLastError)
                put("readOnlyObservedAt", readOnlyObservedAt)
            })
            put("bridgeRegistration", JSONObject().apply {
                put("state", bridgeState)
                put("lastError", bridgeLastError)
                put("attemptCount", bridgeAttemptCount)
                put("lastAttemptAt", bridgeLastAttemptAt)
            })
            put("guardedHooks", JSONObject().apply {
                put("circuitOpenCount", guardedCircuitOpenCount)
                put("lastFailedHook", guardedLastFailedHook)
                put("lastFailedMethod", guardedLastFailedMethod)
                put("lastFailureType", guardedLastFailureType)
                put("totalOpenTransitions", guardedTotalOpenTransitions)
                put("recoveredCount", guardedRecoveredCount)
                put("swallowedHostExceptions", guardedSwallowedHostExceptions)
            })
            put("inline", JSONObject().apply {
                put("state", inlineState)
                put("loaded", inlineLibraryLoaded)
                put("initialized", inlineHookInitialized)
                put("retryCount", inlineRetryCount)
                put("nextRetryAt", inlineNextRetryAt)
                put("retryExhausted", inlineRetryExhausted)
                put("disabledByPlatform", inlineDisabledByPlatform)
                put("lastFailureCode", inlineLastFailureCode)
                put("lastError", lastInlineError)
            })
            put("native", nativeStatus.toJson())
            put("policy", JSONObject().apply {
                put("mountPointsGeneration", mountPointsGeneration)
                put("lastApplySuccess", lastMountPointsApplySuccess)
                put("appliedToExecutor", lastMountPointsApplySuccess)
                put("applicationState", mountPointsState)
                put("state", mountPointsState)
                put("configuredRevision", mountPointsConfiguredRevision)
                put("publishedRevision", mountPointsPublishedRevision)
                put("appliedRedirectRevision", mountPointsAppliedRevision)
                put("executor", MOUNT_POINTS_EXECUTOR)
                put("lastError", lastMountPointsApplyError)
                put("observedAt", mountPointsObservedAt)
                put("lastAttemptRedirectRevision", mountPointsLastAttemptRevision)
                put("lastApplyAt", lastMountPointsApplyAt)
                put("lastApplyGeneration", lastMountPointsApplyGeneration)
                put("lastApplyCount", lastMountPointsApplyCount)
                put("lastApplyError", lastMountPointsApplyError)
            })
            put("fuseJavaGate", fuseJavaGateStatus.toJson())
        }.toString()
    }

    private fun aggregatePolicyState(): String = when {
        mountPointsState == POLICY_STATE_APPLYING ||
                redirectPolicyState == POLICY_STATE_APPLYING ||
                readOnlyPolicyState == POLICY_STATE_APPLYING -> POLICY_STATE_APPLYING
        mountPointsState == POLICY_STATE_STALE -> POLICY_STATE_STALE
        redirectPolicyState == POLICY_STATE_STALE || readOnlyPolicyState == POLICY_STATE_STALE ->
            POLICY_STATE_STALE
        mountPointsState == POLICY_STATE_UNSUPPORTED ||
                redirectPolicyState == POLICY_STATE_UNSUPPORTED ||
                readOnlyPolicyState == POLICY_STATE_UNSUPPORTED -> POLICY_STATE_UNSUPPORTED
        mountPointsState == POLICY_STATE_NO_RULE &&
                redirectPolicyState == POLICY_STATE_NO_RULE &&
                readOnlyPolicyState == POLICY_STATE_NO_RULE -> POLICY_STATE_NO_RULE
        mountPointsState == POLICY_STATE_PENDING ||
                redirectPolicyState == POLICY_STATE_PENDING ||
                readOnlyPolicyState == POLICY_STATE_PENDING -> POLICY_STATE_PENDING
        mountPointsState == POLICY_STATE_APPLIED ||
                redirectPolicyState == POLICY_STATE_APPLIED ||
                readOnlyPolicyState == POLICY_STATE_APPLIED -> POLICY_STATE_APPLIED
        else -> POLICY_STATE_PENDING
    }

    private fun deriveInlineState(status: NativeStatusSnapshot): String = when {
        !status.fuseAvailable -> STATE_DISABLED
        !status.fuseLibraryLoaded -> STATE_FUSE_WAITING
        status.fullAvailable -> STATE_HOOK_READY_FULL
        status.coreAvailable && status.startsWithHooked -> STATE_HOOK_READY_CORE
        status.coreAvailable -> STATE_HOOK_DEGRADED
        status.fuseLibraryLoaded -> STATE_HOOK_UNAVAILABLE
        inlineLibraryLoaded -> STATE_INLINE_LOADED
        else -> STATE_NOT_LOADED
    }

    private fun parseNativeStatus(json: String): NativeStatusSnapshot {
        return try {
            val root = JSONObject(json)
            val symbols = root.optJSONObject("symbols")
            val symbolMethods = root.optJSONObject("symbolMethods")
            NativeStatusSnapshot(
                fuseAvailable = root.optBoolean("fuseAvailable", true),
                fuseLibraryLoaded = root.optBoolean("fuseLibraryLoaded", false),
                fuseLibraryName = root.optString("fuseLibraryName", ""),
                hookMode = root.optString("hookMode", "UNKNOWN"),
                fuseJniLoadMode = root.optString("fuseJniLoadMode", "UNKNOWN"),
                embeddedFuseJniFound = root.optBoolean("embeddedFuseJniFound", false),
                containsMountHooked = symbols?.optBoolean("containsMount", false) ?: false,
                startsWithHooked = symbols?.optBoolean("startsWith", false) ?: false,
                isFuseBpfEnabledHooked = symbols?.optBoolean("isFuseBpfEnabled", false) ?: false,
                fuseReqUserdataHooked = symbols?.optBoolean("fuseReqUserdata", false) ?: false,
                fuseBpfInstallHooked = symbols?.optBoolean("fuseBpfInstall", false) ?: false,
                containsMountMethod = symbolMethods?.optString("containsMount", "") ?: "",
                startsWithMethod = symbolMethods?.optString("startsWith", "") ?: "",
                isFuseBpfEnabledMethod = symbolMethods?.optString("isFuseBpfEnabled", "") ?: "",
                fuseReqUserdataMethod = symbolMethods?.optString("fuseReqUserdata", "") ?: "",
                fuseBpfInstallMethod = symbolMethods?.optString("fuseBpfInstall", "") ?: "",
                xhookRefreshCalled = root.optBoolean("xhookRefreshCalled", false),
                lastError = root.optString("lastError", ""),
            )
        } catch (e: Exception) {
            NativeStatusSnapshot(lastError = "Invalid native status: ${describe(e)}")
        }
    }

    private fun describe(error: Throwable): String {
        val message = error.message?.takeIf { it.isNotBlank() }
        return if (message == null) error.javaClass.name else "${error.javaClass.name}: $message"
    }

    private data class NativeStatusSnapshot(
        val fuseAvailable: Boolean = true,
        val fuseLibraryLoaded: Boolean = false,
        val fuseLibraryName: String = "",
        val hookMode: String = "UNKNOWN",
        val fuseJniLoadMode: String = "UNKNOWN",
        val embeddedFuseJniFound: Boolean = false,
        val containsMountHooked: Boolean = false,
        val startsWithHooked: Boolean = false,
        val isFuseBpfEnabledHooked: Boolean = false,
        val fuseReqUserdataHooked: Boolean = false,
        val fuseBpfInstallHooked: Boolean = false,
        val containsMountMethod: String = "",
        val startsWithMethod: String = "",
        val isFuseBpfEnabledMethod: String = "",
        val fuseReqUserdataMethod: String = "",
        val fuseBpfInstallMethod: String = "",
        val xhookRefreshCalled: Boolean = false,
        val lastError: String = "",
    ) {
        val coreAvailable: Boolean
            get() = containsMountHooked

        val fullAvailable: Boolean
            get() = containsMountHooked &&
                    startsWithHooked &&
                    isFuseBpfEnabledHooked &&
                    fuseReqUserdataHooked &&
                    fuseBpfInstallHooked

        private val missingSymbols: List<String>
            get() = buildList {
                if (!fuseLibraryLoaded) return@buildList
                if (!containsMountHooked) add("containsMount")
                if (!startsWithHooked) add("startsWith")
                if (!isFuseBpfEnabledHooked) add("isFuseBpfEnabled")
                if (!fuseReqUserdataHooked) add("fuseReqUserdata")
                if (!fuseBpfInstallHooked) add("fuseBpfInstall")
            }

        fun toJson(): JSONObject = JSONObject().apply {
            put("fuseAvailable", fuseAvailable)
            put("fuseLibraryLoaded", fuseLibraryLoaded)
            put("fuseLibraryName", fuseLibraryName)
            put("hookMode", hookMode)
            put("fuseJniLoadMode", fuseJniLoadMode)
            put("embeddedFuseJniFound", embeddedFuseJniFound)
            put("xhookRefreshCalled", xhookRefreshCalled)
            put("coreAvailable", coreAvailable)
            put("fullAvailable", fullAvailable)
            put("symbols", JSONObject().apply {
                put("containsMount", containsMountHooked)
                put("startsWith", startsWithHooked)
                put("isFuseBpfEnabled", isFuseBpfEnabledHooked)
                put("fuseReqUserdata", fuseReqUserdataHooked)
                put("fuseBpfInstall", fuseBpfInstallHooked)
            })
            put("symbolMethods", JSONObject().apply {
                put("containsMount", containsMountMethod)
                put("startsWith", startsWithMethod)
                put("isFuseBpfEnabled", isFuseBpfEnabledMethod)
                put("fuseReqUserdata", fuseReqUserdataMethod)
                put("fuseBpfInstall", fuseBpfInstallMethod)
            })
            put("missingSymbols", JSONArray(missingSymbols))
            put("lastError", lastError)
        }
    }

    private data class FuseJavaGateStatus(
        val discoveredCount: Int = 0,
        val hookedMethods: List<String> = emptyList(),
        val unknownMethods: List<String> = emptyList(),
        val failedMethods: List<String> = emptyList(),
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("discoveredCount", discoveredCount)
            put("hookedCount", hookedMethods.size)
            put("unknownCount", unknownMethods.size)
            put("failedCount", failedMethods.size)
            put("hookedMethods", JSONArray(hookedMethods))
            put("unknownMethods", JSONArray(unknownMethods))
            put("failedMethods", JSONArray(failedMethods))
        }
    }
}
