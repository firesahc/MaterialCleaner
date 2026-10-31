package me.gm.cleaner.runtime.mediaprovider.hook.policy
import android.util.Log
import me.gm.cleaner.core.common.err.ErrorCodes
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import me.gm.cleaner.core.storage.redirect.domain.MountPlanDeriver
import me.gm.cleaner.core.storage.redirect.domain.OrderedRedirectInterpreter.isCanonicalAbsolutePath
import me.gm.cleaner.core.storage.redirect.domain.RedirectRule
import me.gm.cleaner.runtime.mediaprovider.hook.bridge.HookDataBusBridge
import me.gm.cleaner.runtime.mediaprovider.hook.bridge.DataBusBridgeResult
import me.gm.cleaner.runtime.mediaprovider.hook.fuse.FuseNativePolicyAdapter
import org.json.JSONObject
import java.util.regex.Pattern
import me.gm.cleaner.core.storage.redirect.databus.DataBusProtocol

/**
 * MediaProvider Java Hook 层的本地策略缓存。
 *
 * 消费 [DataBus] 发布的快照，减少热路径对 Binder 的强依赖。
 *
 * ## 缓存内容
 * - **ReadOnlyCache**：packageName → Set<path>，来自 read_only.json 快照
 * - **RuleCache**：packageName → userId → 规则对，来自 redirect_policy.json 快照，
 *   经 [MountPlanDeriver] 解释，不再持有旧 MountRules
 * - **ConfiguredMountPoints**：挂载点列表，来自 configured_mount_points.json 快照（推送到 native）
 *
 * ## 刷新策略
 * - 初始化时从 DataBus 读取最后一次快照
 * - [refreshFromDataBus] 由外部按需调用（监听 signal 后）
 * - DataBus 是策略和挂载点的唯一分发通道
 *
 * ## 降级行为
 * - 快照不存在 → 返回 null，调用方保持原路径并由状态诊断暴露快照问题
 * - 快照存在但 generation 过期 → 仍使用当前缓存，直到下次刷新
 */
object HookPolicyCache {
    private const val TAG = "HookPolicyCache"

    // ── 按域分组的 copy-on-write holder：每个域独立演进 generation/epoch ──
    // PlatformCapabilities 域已委托给 CapabilitiesCache

    // ── ReadOnly 域（委托给 ReadOnlyPolicyCache，水位由本类维护） ──
    private val readOnlyPolicyCache = ReadOnlyPolicyCache()
    @Volatile
    private var lastReadOnlySignalTimestamp: Long = 0L

    // ── Rule 域（含 denylist） ──
    private data class RuleHolder(
        val data: Map<String, Map<Int, List<RedirectRule>>> = emptyMap(),
        val denylist: Set<String> = emptySet(),
        val generation: Long = 0L,
        val publisherEpoch: String = "",
        val revision: String = "",
    )

    @Volatile
    private var rule: RuleHolder = RuleHolder()
    @Volatile
    private var lastPolicySignalTimestamp: Long = 0L

    // ── ConfiguredMountPoints 域（数据体在 native，本地只保留版本水位） ──
    private data class MountPointsHolder(
        val generation: Long = 0L,
        val publisherEpoch: String = "",
        val revision: String = "",
    )

    @Volatile
    private var mountPoints: MountPointsHolder = MountPointsHolder()
    @Volatile
    private var lastMountSignalTimestamp: Long = 0L

    val redirectPolicyGeneration: Long get() = rule.generation

    fun getNativeHookStatusJson(): String =
        NativeHookStatus.toJson()

    // ── PlatformCapabilities 域（委托给 CapabilitiesCache） ──
    internal val capabilitiesCache = CapabilitiesCache()
    @Volatile
    private var lastCapabilitiesSignalTimestamp: Long = 0L

    /** 缓存的 FUSE BPF 状态，供 MediaProvider Hook 使用 */
    val isFuseBpfEnabledFromCache: Boolean get() = capabilitiesCache.isFuseBpfEnabled
    /** 缓存的 FUSE 可用性，供 MediaProvider Hook 使用 */
    val fuseAvailableFromCache: Boolean get() = capabilitiesCache.fuseAvailable
    /** 是否已经成功加载 platform_capabilities 快照 */
    val platformCapabilitiesLoaded: Boolean get() = capabilitiesCache.platformCapabilitiesLoaded
    /** 缓存的 SDK 版本，仅用于诊断日志 */
    val sdkVersionIntFromCache: Int get() = capabilitiesCache.sdkVersionInt
    val fuseJniLoadModeFromCache: String get() = capabilitiesCache.fuseJniLoadMode
    val supportedNativeHookModeFromCache: String get() = capabilitiesCache.supportedNativeHookMode

    private inline fun consumeSnapshot(
        name: String,
        onMissing: () -> SnapshotConsumeOutcome,
        onPresent: (String) -> SnapshotConsumeOutcome,
    ): SnapshotConsumeOutcome = when (val result = HookDataBusBridge.readSnapshot(name)) {
        is DataBusBridgeResult.Available -> result.value?.let(onPresent) ?: onMissing()
        is DataBusBridgeResult.Unavailable -> SnapshotConsumeOutcome(
            succeeded = false,
            unavailableReason = "${result.operation}:${result.reason}",
        )
    }

    private fun markDataBusStateUnavailable(
        scope: String,
        reason: String,
        generation: Long,
    ) {
        val detail = "DataBus state unavailable for $scope: $reason"
        val domain = when {
            scope.startsWith("redirect_policy") -> NativeHookStatus.POLICY_DOMAIN_REDIRECT
            scope.startsWith("read_only") -> NativeHookStatus.POLICY_DOMAIN_READ_ONLY
            scope.startsWith("platform_capabilities") -> NativeHookStatus.POLICY_DOMAIN_CAPABILITIES
            scope.startsWith("configured_mount_points") -> NativeHookStatus.POLICY_DOMAIN_MOUNT_READ
            else -> "bridge"
        }
        when (domain) {
            NativeHookStatus.POLICY_DOMAIN_REDIRECT ->
                NativeHookStatus.markRedirectPolicyFailed("", detail)
            NativeHookStatus.POLICY_DOMAIN_READ_ONLY ->
                NativeHookStatus.markReadOnlyPolicyFailed("", detail)
            NativeHookStatus.POLICY_DOMAIN_CAPABILITIES ->
                NativeHookStatus.markCapabilitiesReadUnavailable(IllegalStateException(detail))
            NativeHookStatus.POLICY_DOMAIN_MOUNT_READ ->
                NativeHookStatus.markMountPointsReadUnavailable(IllegalStateException(detail))
        }
        NativeHookStatus.markPolicyCacheFailed(
            ErrorCodes.HOOK_JAVA_CACHE_SNAPSHOT_MISSING,
            detail,
            generation,
            domain,
        )
    }

    // ── 偏好标记域（随 redirect_policy 快照演进，独立 holder 发布） ──
    @Volatile
    private var preferences: PreferencesHolder = PreferencesHolder()

    var recordExternalAppSpecificStorage: Boolean
        get() = preferences.recordExternalAppSpecificStorage
        private set(value) {
            preferences = preferences.copy(recordExternalAppSpecificStorage = value)
        }

    /**
     * FUSE BPF 拦截范围开关：随 redirect_policy 快照透传，
     * native 侧由挂载点全量刷新经 commitPolicy 第三参统一应用。
     */
    var fuseBpfBlockAll: Boolean
        get() = preferences.fuseBpfBlockAll
        private set(value) {
            preferences = preferences.copy(fuseBpfBlockAll = value)
        }

    var aggressivelyPromptForReadingMediaFiles: Boolean
        get() = preferences.aggressivelyPromptForReadingMediaFiles
        private set(value) {
            preferences = preferences.copy(aggressivelyPromptForReadingMediaFiles = value)
        }

    /**
     * 媒体库写入审计总开关：随 redirect_policy 快照透传，
     * InsertHooker 读取此开关决定是否为每次 insert 留结构化审计流水。
     */
    var auditMediaInsert: Boolean
        get() = preferences.auditMediaInsert
        private set(value) {
            preferences = preferences.copy(auditMediaInsert = value)
        }

    /**
     * 媒体库写入拒绝总开关：随 redirect_policy 快照透传，
     * InsertHooker 读取此开关决定是否在命中不可访问/只读规则时短路 insertFile。
     */
    var denyMediaInsertWrites: Boolean
        get() = preferences.denyMediaInsertWrites
        private set(value) {
            preferences = preferences.copy(denyMediaInsertWrites = value)
        }

    /**
     * 从 DataBus 加载最后一次快照初始化缓存。
     * 应在 MediaProvider 进程初始化时调用一次。
     */
    fun initFromDataBus() {
        Log.i(TAG, "initFromDataBus: loading snapshots...")

        // 读取 redirect_policy.json：游标在读取前捕获水位，
        // 消费期间新到的 signal 不会被误标为已处理。
        val policyOutcome = consumeAfterSignalCapture(
            currentAcknowledgedTimestamp = lastPolicySignalTimestamp,
            captureSignalTimestamp = {
                HookDataBusBridge.getSignalTimestamp(DataBusProtocol.SIGNAL_REDIRECT_POLICY_CHANGED)
            },
            consumeSnapshot = {
                consumeSnapshot(
                    DataBusProtocol.SNAPSHOT_REDIRECT_POLICY,
                    onMissing = {
                        Log.w(TAG, "initFromDataBus: no redirect_policy snapshot available")
                        NativeHookStatus.markRedirectPolicyFailed(
                            "", "redirect_policy snapshot missing at init"
                        )
                        NativeHookStatus.markPolicyCacheFailed(
                            ErrorCodes.HOOK_JAVA_CACHE_SNAPSHOT_MISSING,
                            "redirect_policy snapshot missing at init", 0L,
                            NativeHookStatus.POLICY_DOMAIN_REDIRECT,
                        )
                        SnapshotConsumeOutcome(succeeded = true, changed = false)
                    },
                    onPresent = { policyJson ->
                        try {
                            parseRedirectPolicy(policyJson)
                            Log.i(TAG, "initFromDataBus: loaded redirect_policy, generation=${rule.generation}, " +
                                    "packages=${rule.data.size}, users=${rule.data.values.sumOf { it.size }}")
                            SnapshotConsumeOutcome(succeeded = true, changed = true)
                        } catch (e: Exception) {
                            Log.e(TAG, "initFromDataBus: failed to parse redirect_policy", e)
                            NativeHookStatus.markRedirectPolicyFailed(
                                extractSnapshotRevision(policyJson, "redirectRevision"),
                                describeThrowable(e),
                            )
                            NativeHookStatus.markPolicyCacheFailed(
                                ErrorCodes.HOOK_JAVA_CACHE_PARSE_FAILED,
                                describeThrowable(e),
                                rule.generation,
                                NativeHookStatus.POLICY_DOMAIN_REDIRECT,
                            )
                            SnapshotConsumeOutcome(succeeded = false)
                        }
                    },
                )
            },
        )
        policyOutcome.unavailableReason?.let { reason ->
            markDataBusStateUnavailable("redirect_policy init", reason, rule.generation)
        }
        lastPolicySignalTimestamp = policyOutcome.acknowledgedTimestamp

        // 读取 read_only.json：游标协议在域内（读取前捕获水位，成功才推进），
        // 水位由本类自持，域仅回传消费结果。
        val roOutcome = readOnlyPolicyCache.consumeFromDataBus(lastReadOnlySignalTimestamp)
        roOutcome.unavailableReason?.let { reason ->
            markDataBusStateUnavailable("read_only init", reason, readOnlyPolicyCache.generation)
        }
        lastReadOnlySignalTimestamp = roOutcome.acknowledgedTimestamp

        // 读取 platform_capabilities.json → 缓存关键能力
        val capsOutcome = consumeAfterSignalCapture(
            currentAcknowledgedTimestamp = lastCapabilitiesSignalTimestamp,
            captureSignalTimestamp = {
                HookDataBusBridge.getSignalTimestamp(DataBusProtocol.SIGNAL_PLATFORM_CAPABILITIES_CHANGED)
            },
            consumeSnapshot = {
                capabilitiesCache.loadPlatformCapabilities()
            },
        )
        capsOutcome.unavailableReason?.let { reason ->
            markDataBusStateUnavailable("platform_capabilities init", reason, capabilitiesCache.generation)
        }
        lastCapabilitiesSignalTimestamp = capsOutcome.acknowledgedTimestamp

        // 读取 configured_mount_points.json → 推送到 native
        val mountOutcome = consumeAfterSignalCapture(
            currentAcknowledgedTimestamp = lastMountSignalTimestamp,
            captureSignalTimestamp = {
                HookDataBusBridge.getSignalTimestamp(DataBusProtocol.SIGNAL_CONFIGURED_MOUNT_POINTS_CHANGED)
            },
            consumeSnapshot = {
                loadAndPushConfiguredMountPoints(force = false)
            },
        )
        mountOutcome.unavailableReason?.let { reason ->
            markDataBusStateUnavailable("configured_mount_points init", reason, mountPoints.generation)
        }
        lastMountSignalTimestamp = mountOutcome.acknowledgedTimestamp
    }

    /**
     * 检查快照是否比当前缓存更新。
     * signal timestamp 表示通知发生时间，snapshot generation 表示策略代数。
     * 两者独立追踪，不混用。
     */
    fun isStale(): Boolean {
        if (!NativeHookStatus.isRedirectPolicyFresh() ||
            !NativeHookStatus.isReadOnlyPolicyFresh() ||
            !NativeHookStatus.isCapabilitiesFresh() ||
            !NativeHookStatus.isMountPointsFresh()
        ) return true
        val signals = listOf(
            HookDataBusBridge.getSignalTimestamp(DataBusProtocol.SIGNAL_READ_ONLY_CHANGED),
            HookDataBusBridge.getSignalTimestamp(DataBusProtocol.SIGNAL_REDIRECT_POLICY_CHANGED),
            HookDataBusBridge.getSignalTimestamp(DataBusProtocol.SIGNAL_CONFIGURED_MOUNT_POINTS_CHANGED),
            HookDataBusBridge.getSignalTimestamp(DataBusProtocol.SIGNAL_PLATFORM_CAPABILITIES_CHANGED),
        )
        if (signals.any { it is DataBusBridgeResult.Unavailable }) return true
        val timestamps = signals.map { (it as DataBusBridgeResult.Available).value }
        val (roSignalTime, policySignalTime, mountSignalTime, capsSignalTime) = timestamps
        return roSignalTime > lastReadOnlySignalTimestamp
                || policySignalTime > lastPolicySignalTimestamp
                || mountSignalTime > lastMountSignalTimestamp
                || capsSignalTime > lastCapabilitiesSignalTimestamp
    }

    // ═══════════════════════════════════════════════════════════
    // PlatformCapabilities

    /**
     * 从 DataBus 刷新全部缓存并同步 native 挂载点。
     */
    fun refreshFromDataBus() {
        Log.d(TAG, "refreshFromDataBus")
        initFromDataBus()
    }

    /**
     * 只刷新发生变更的快照。
     *
     * signal timestamp 只用于判断“是否有新通知”，snapshot generation 才表示策略代数。
     * 两者不能混用，否则时间戳会长期大于 generation，导致状态判断失真。
     */
    fun refreshChangedSnapshotsFromDataBus() {
        // 外层粗判避免无谓 IO；游标在真正消费前再次捕获水位，
        // 吸收粗判与读取之间新到的 signal，防止新一代被误标已处理。
        val policyObserved = HookDataBusBridge.getSignalTimestamp(DataBusProtocol.SIGNAL_REDIRECT_POLICY_CHANGED)
        when (policyObserved) {
            is DataBusBridgeResult.Unavailable -> markDataBusStateUnavailable(
                "redirect_policy signal refresh", "${policyObserved.operation}:${policyObserved.reason}", rule.generation,
            )
            is DataBusBridgeResult.Available -> {
                if (policyObserved.value > lastPolicySignalTimestamp ||
                    !NativeHookStatus.isRedirectPolicyFresh()
                ) {
                    val policyResult = consumeAfterSignalCapture(
                        currentAcknowledgedTimestamp = lastPolicySignalTimestamp,
                        captureSignalTimestamp = {
                            HookDataBusBridge.getSignalTimestamp(
                                DataBusProtocol.SIGNAL_REDIRECT_POLICY_CHANGED,
                            )
                        },
                        consumeSnapshot = {
                            consumeSnapshot(
                                DataBusProtocol.SNAPSHOT_REDIRECT_POLICY,
                                onMissing = {
                                    NativeHookStatus.markRedirectPolicyFailed(
                                        "", "redirect_policy snapshot missing during refresh",
                                    )
                                    SnapshotConsumeOutcome(succeeded = true, changed = false)
                                },
                                onPresent = { policyJson ->
                                    try {
                                        parseRedirectPolicy(policyJson)
                                        SnapshotConsumeOutcome(succeeded = true, changed = true)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "refreshChangedSnapshots: failed to parse redirect_policy", e)
                                        NativeHookStatus.markRedirectPolicyFailed(
                                            extractSnapshotRevision(policyJson, "redirectRevision"),
                                            describeThrowable(e),
                                        )
                                        NativeHookStatus.markPolicyCacheFailed(
                                            ErrorCodes.HOOK_JAVA_CACHE_PARSE_FAILED,
                                            describeThrowable(e),
                                            rule.generation,
                                            NativeHookStatus.POLICY_DOMAIN_REDIRECT,
                                        )
                                        SnapshotConsumeOutcome(succeeded = false)
                                    }
                                },
                            )
                        },
                    )
                    policyResult.unavailableReason?.let { reason ->
                        markDataBusStateUnavailable("redirect_policy refresh", reason, rule.generation)
                    }
                    lastPolicySignalTimestamp = policyResult.acknowledgedTimestamp
                }
            }
        }

        val roObserved = HookDataBusBridge.getSignalTimestamp(DataBusProtocol.SIGNAL_READ_ONLY_CHANGED)
        when (roObserved) {
            is DataBusBridgeResult.Unavailable -> markDataBusStateUnavailable(
                "read_only signal refresh", "${roObserved.operation}:${roObserved.reason}", readOnlyPolicyCache.generation,
            )
            is DataBusBridgeResult.Available -> {
                if (roObserved.value > lastReadOnlySignalTimestamp ||
                    !NativeHookStatus.isReadOnlyPolicyFresh()
                ) {
                    val roResult = readOnlyPolicyCache.consumeFromDataBus(lastReadOnlySignalTimestamp)
                    roResult.unavailableReason?.let { reason ->
                        markDataBusStateUnavailable("read_only refresh", reason, readOnlyPolicyCache.generation)
                    }
                    lastReadOnlySignalTimestamp = roResult.acknowledgedTimestamp
                }
            }
        }

        // platform_capabilities 变更（极少发生，但仍支持运行时重检测）
        val capsObserved = HookDataBusBridge.getSignalTimestamp(DataBusProtocol.SIGNAL_PLATFORM_CAPABILITIES_CHANGED)
        var forceMountRefresh = false
        when (capsObserved) {
            is DataBusBridgeResult.Unavailable -> markDataBusStateUnavailable(
                "platform_capabilities signal refresh",
                "${capsObserved.operation}:${capsObserved.reason}",
                capabilitiesCache.generation,
            )
            is DataBusBridgeResult.Available -> {
                if (capsObserved.value > lastCapabilitiesSignalTimestamp ||
                    !NativeHookStatus.isCapabilitiesFresh()
                ) {
                    val capsResult = consumeAfterSignalCapture(
                        currentAcknowledgedTimestamp = lastCapabilitiesSignalTimestamp,
                        captureSignalTimestamp = {
                            HookDataBusBridge.getSignalTimestamp(
                                DataBusProtocol.SIGNAL_PLATFORM_CAPABILITIES_CHANGED,
                            )
                        },
                        consumeSnapshot = {
                            capabilitiesCache.loadPlatformCapabilities()
                        },
                    )
                    capsResult.unavailableReason?.let { reason ->
                        markDataBusStateUnavailable("platform_capabilities refresh", reason, capabilitiesCache.generation)
                    }
                    lastCapabilitiesSignalTimestamp = capsResult.acknowledgedTimestamp
                    forceMountRefresh = capsResult.changed
                }
            }
        }

        tryRefreshNativeMountPoints(force = forceMountRefresh)
    }

    /**
     * 尝试从 DataBus 刷新 native 挂载点。
     * 先比较 signal timestamp（上次通知时间），如果未变化则跳过；
     * 如果变化则经信号游标读取 snapshot，内部再比较 snapshot generation（策略代数）。
     */
    fun tryRefreshNativeMountPoints(force: Boolean = false) {
        val mountSignalTime = HookDataBusBridge.getSignalTimestamp(
            DataBusProtocol.SIGNAL_CONFIGURED_MOUNT_POINTS_CHANGED,
        )
        when (mountSignalTime) {
            is DataBusBridgeResult.Unavailable -> {
                markDataBusStateUnavailable(
                    "configured_mount_points signal refresh",
                    "${mountSignalTime.operation}:${mountSignalTime.reason}",
                    mountPoints.generation,
                )
                return
            }
            is DataBusBridgeResult.Available -> if (
                !force && NativeHookStatus.isMountPointsFresh() &&
                        mountSignalTime.value <= lastMountSignalTimestamp && lastMountSignalTimestamp > 0
            ) {
                return  // signal 未变更
            }
        }
        val mountResult = consumeAfterSignalCapture(
            currentAcknowledgedTimestamp = lastMountSignalTimestamp,
            captureSignalTimestamp = {
                HookDataBusBridge.getSignalTimestamp(DataBusProtocol.SIGNAL_CONFIGURED_MOUNT_POINTS_CHANGED)
            },
            consumeSnapshot = {
                loadAndPushConfiguredMountPoints(force)
            },
        )
        mountResult.unavailableReason?.let { reason ->
            markDataBusStateUnavailable("configured_mount_points refresh", reason, mountPoints.generation)
        }
        lastMountSignalTimestamp = mountResult.acknowledgedTimestamp
    }

    // ═══════════════════════════════════════════════════════════
    // Configured Mount Points → Native
    // ═══════════════════════════════════════════════════════════

    /**
     * 从 DataBus 读取 configured_mount_points.json，
     * 解析 points 数组，并通过 [InlineHookConfig.setMountPoint] 推送到 native。
     * 此路径是 native 挂载点配置的唯一分发路径。
     */
    private fun loadAndPushConfiguredMountPoints(force: Boolean): SnapshotConsumeOutcome {
        val json = when (
            val read = HookDataBusBridge.readSnapshot(DataBusProtocol.SNAPSHOT_CONFIGURED_MOUNT_POINTS)
        ) {
            is DataBusBridgeResult.Unavailable -> return SnapshotConsumeOutcome(
                succeeded = false,
                unavailableReason = "${read.operation}:${read.reason}",
            )
            is DataBusBridgeResult.Available -> read.value ?: run {
                Log.d(TAG, "loadConfiguredMountPoints: no snapshot available")
                return SnapshotConsumeOutcome(
                    succeeded = false,
                    unavailableReason = "configured_mount_points_snapshot_missing",
                )
            }
        }

        var nativeApplyAttempted = false
        try {
            val root = JSONObject(json)
            val generation = root.optLong("generation", 0L)
            val publisherEpoch = root.optString("publisherEpoch", "")
            val revision = root.optString("redirectRevision", "")
            val current = mountPoints
            val sameIdentity = publisherEpoch == current.publisherEpoch &&
                    generation == current.generation && revision == current.revision
            if (!force && sameIdentity && NativeHookStatus.markMountPointsFreshIfApplied(
                    publisherEpoch, generation, revision,
                )) {
                return SnapshotConsumeOutcome(succeeded = true, changed = false)
            }
            if (!force && !sameIdentity && !shouldAcceptSnapshot(
                    publisherEpoch,
                    generation,
                    current.publisherEpoch,
                    current.generation
                )) {
                Log.d(TAG, "loadConfiguredMountPoints: snapshot not newer " +
                        "(epoch=$publisherEpoch generation=$generation, " +
                        "currentEpoch=${current.publisherEpoch} " +
                        "currentGeneration=${current.generation})")
                return SnapshotConsumeOutcome(succeeded = true, changed = false)
            }
            val pointsArr = root.optJSONArray("points")
                ?: throw IllegalArgumentException("configured_mount_points.points is missing or invalid")
            val points = Array(pointsArr.length()) { pointsArr.getString(it) }

            // 只有完整解析出 points 后才确认 snapshot freshness；非法正文不能被解释成空策略。
            NativeHookStatus.markMountPointsSnapshotFresh(publisherEpoch, generation, revision)
            if (points.isEmpty()) {
                Log.i(TAG, "loadConfiguredMountPoints: empty points, clearing native mountPoint, generation=$generation")
                // 空数组必须显式推送到 native，用于清除残留 mountPoint。
                nativeApplyAttempted = true
                FuseNativePolicyAdapter.applyConfiguredMountPoints(emptyArray(), generation, revision, publisherEpoch)
                mountPoints = MountPointsHolder(
                    generation = generation,
                    publisherEpoch = publisherEpoch,
                    revision = revision,
                )
                return SnapshotConsumeOutcome(succeeded = true, changed = true)
            }

            nativeApplyAttempted = true
            FuseNativePolicyAdapter.applyConfiguredMountPoints(points, generation, revision, publisherEpoch)
            mountPoints = MountPointsHolder(
                generation = generation,
                publisherEpoch = publisherEpoch,
                revision = revision,
            )

            Log.i(TAG, "loadConfiguredMountPoints: pushed ${points.size} points to native, " +
                    "epoch=$publisherEpoch, generation=$generation")
            return SnapshotConsumeOutcome(succeeded = true, changed = true)
        } catch (e: Throwable) {
            Log.e(TAG, "loadConfiguredMountPoints: failed", e)
            if (!nativeApplyAttempted) NativeHookStatus.markMountPointsReadUnavailable(e)
            NativeHookStatus.markPolicyCacheFailed(
                if (nativeApplyAttempted) ErrorCodes.HOOK_JAVA_CACHE_COMMIT_NATIVE_FAILED
                else ErrorCodes.HOOK_JAVA_CACHE_PARSE_FAILED,
                describeThrowable(e),
                extractSnapshotGeneration(json),
                if (nativeApplyAttempted) NativeHookStatus.POLICY_DOMAIN_MOUNT_APPLY
                else NativeHookStatus.POLICY_DOMAIN_MOUNT_READ,
            )
            return SnapshotConsumeOutcome(succeeded = false)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // ReadOnly 查询
    // ═══════════════════════════════════════════════════════════

    /**
     * 检查指定路径是否在只读规则中。
     *
     * @param packageName 包名
     * @param path 要检查的路径（已规范化）
     * @param pathAsUser 按 userId=0 展开的路径
     * @return true 如果路径命中只读规则
     */
    fun isReadOnly(packageName: String, pathAsUser: String): Boolean {
        return readOnlyPolicyCache.isReadOnly(packageName, pathAsUser)
    }

    /**
     * 获取只读规则的 generation，用于判断缓存是否存在。
     * 返回 0 表示未加载任何快照。
     */
    fun getReadOnlyGeneration(): Long = readOnlyPolicyCache.generation

    // ═══════════════════════════════════════════════════════════
    // Rule 查询（路径重定向）
    // ═══════════════════════════════════════════════════════════

    /**
     * 从本地缓存计算挂载后路径。
     *
     * @param packageName 包名
     * @param path 原始路径
     * @return 挂载后路径；如果本地策略快照未加载或包无规则则返回 null
     */
    fun getMountedPath(packageName: String, path: String): String? {
        return getMountedPath(packageName, extractUserIdFromPath(path), path)
    }

    /** 从本地缓存按用户计算挂载后路径。 */
    fun getMountedPath(packageName: String, userId: Int, path: String): String? {
        val snapshot = rule
        val userRules = snapshot.data[packageName] ?: return null
        val rules = userRules[userId] ?: return null
        return MountPlanDeriver.resolveMountedPath(rules, path)
    }

    /** 检查指定包是否在 denylist 中。 */
    fun isDenied(packageName: String): Boolean = rule.denylist.contains(packageName)

    /**
     * 调用方无 uid 时的策略存在性快判，供 Query 等热路径在重型分析前旁路。
     * 只查本地不可变缓存，不做 IO/Binder/JSON。
     */
    fun hasRedirectRules(packageName: String): Boolean = rule.data.containsKey(packageName)

    // ═══════════════════════════════════════════════════════════
    // JSON 解析
    // ═══════════════════════════════════════════════════════════

    /** 供 markPolicyCacheFailed 使用的受控异常描述；截断由 NativeHookStatus 统一处理。 */
    internal fun describeThrowable(e: Throwable): String {
        val message = e.message?.takeIf { it.isNotBlank() }
        return if (message == null) e.javaClass.name else "${e.javaClass.name}: $message"
    }

    internal fun extractSnapshotRevision(json: String, key: String): String = runCatching {
        JSONObject(json).optString(key, "")
    }.getOrDefault("")

    private fun extractSnapshotGeneration(json: String): Long = runCatching {
        JSONObject(json).optLong("generation", 0L)
    }.getOrDefault(0L)

    fun getNativeMountPointsGeneration(): Long = mountPoints.generation

    private fun parseRedirectPolicy(json: String) {
        val root = JSONObject(json)
        val generation = root.optLong("generation", 0L)
        val publisherEpoch = root.optString("publisherEpoch", "")
        val revision = root.optString("redirectRevision", "")
        val current = rule
        val sameIdentity = publisherEpoch == current.publisherEpoch &&
                generation == current.generation && revision == current.revision
        if (sameIdentity && NativeHookStatus.markRedirectPolicyFreshIfApplied(
                publisherEpoch, generation, revision,
            )) {
            return
        }
        if (!sameIdentity && !shouldAcceptSnapshot(
                publisherEpoch, generation, current.publisherEpoch, current.generation,
            )) {
            Log.d(TAG, "loadRedirectPolicy: snapshot not newer " +
                    "(epoch=$publisherEpoch generation=$generation, " +
                    "currentEpoch=${current.publisherEpoch} currentGeneration=${current.generation})")
            return
        }
        val newCache = mutableMapOf<String, Map<Int, List<RedirectRule>>>()
        val rulesObj = root.optJSONObject("storageRedirectRules")
        if (rulesObj != null) {
            for (pkg in rulesObj.keys()) {
                val userObj = rulesObj.optJSONObject(pkg)
                if (userObj == null) continue

                val userCache = mutableMapOf<Int, List<RedirectRule>>()
                for (userKey in userObj.keys()) {
                    val userId = userKey.toIntOrNull() ?: continue
                    val rulesArr = userObj.optJSONArray(userKey)
                    if (rulesArr == null || rulesArr.length() == 0) continue

                    val zipped = mutableListOf<RedirectRule>()
                    for (i in 0 until rulesArr.length()) {
                        val ruleObj = rulesArr.getJSONObject(i)
                        val source = ruleObj.optString("source", "")
                        val target = ruleObj.optString("target", "")
                        if (source.isNotEmpty() && target.isNotEmpty()) {
                            if (!isCanonicalAbsolutePath(source) || !isCanonicalAbsolutePath(target)) {
                                Log.w(TAG, "loadRedirectPolicy: drop non-canonical rule pkg=$pkg")
                                continue
                            }
                            zipped.add(RedirectRule(source, target))
                        }
                    }
                    if (zipped.isNotEmpty()) {
                        userCache[userId] = zipped
                    }
                }
                if (userCache.isNotEmpty()) {
                    newCache[pkg] = userCache
                }
            }
        }

        // 解析 denylist：缺席则沿用当前域快照，保持 copy-on-write 原子发布。
        val denyArr = root.optJSONArray("denylist")
        val newDenylist = if (denyArr != null) {
            val denySet = mutableSetOf<String>()
            for (i in 0 until denyArr.length()) {
                denySet.add(denyArr.getString(i))
            }
            denySet.toSet()
        } else {
            current.denylist
        }

        rule = RuleHolder(
            data = newCache.toMap(),
            denylist = newDenylist,
            generation = generation,
            publisherEpoch = publisherEpoch,
            revision = revision,
        )
        NativeHookStatus.markRedirectPolicyApplied(
            revision, generation, newCache.isNotEmpty(), publisherEpoch,
        )

        // 解析偏好标记；native 侧应用由挂载点全量刷新（commitPolicy）统一原子完成。
        preferences = PolicyPreferencesParser.parse(json)

        Log.i(TAG, "loadRedirectPolicy: loaded redirect_policy, generation=$generation, " +
                "packages=${rule.data.size}, users=${rule.data.values.sumOf { it.size }}")
    }
}

/**
 * 快照验收规则（纯函数，可 JVM 单测）。
 *
 * 首载（currentGeneration<=0）接受；epoch 变化接受；同 epoch 要求 generation 严格递增。
 */
internal fun shouldAcceptSnapshot(
    newEpoch: String,
    newGeneration: Long,
    currentEpoch: String,
    currentGeneration: Long,
): Boolean {
    if (currentGeneration <= 0) return true
    if (newEpoch.isNotBlank() && currentEpoch.isNotBlank() && newEpoch != currentEpoch) {
        return true
    }
    if (newEpoch.isNotBlank() && currentEpoch.isBlank()) {
        return true
    }
    return newGeneration > currentGeneration
}

/** 从路径推断所属 userId（纯函数，可 JVM 单测）。 */
internal fun extractUserIdFromPath(path: String): Int {
    val fast = extractEmulatedUserIdFast(path)
    if (fast != null) {
        return fast
    }
    val matcher = PATHS_HAVE_USER_ID.matcher(path)
    if (!matcher.matches()) {
        return 0
    }
    return matcher.group(2)?.toIntOrNull() ?: 0
}

/** 快检 /storage/emulated/ 前缀的用户 ID（纯函数，可 JVM 单测）。 */
internal fun extractEmulatedUserIdFast(path: String): Int? {
    if (path.length < EMULATED_PREFIX.length) {
        return null
    }
    if (!path.startsWith(EMULATED_PREFIX, ignoreCase = true)) {
        return null
    }
    if (path.length == EMULATED_PREFIX.length) {
        return 0
    }
    var end = EMULATED_PREFIX.length
    while (end < path.length && path[end].isDigit()) {
        end++
    }
    if (end == EMULATED_PREFIX.length) {
        return 0
    }
    if (end < path.length && path[end] != '/') {
        return 0
    }
    return path.substring(EMULATED_PREFIX.length, end).toIntOrNull() ?: 0
}

private val PATHS_HAVE_USER_ID: Pattern =
    Pattern.compile("(?i)(^/[^/]+/[^/]+/)([0-9]+)(/.*)?")

private const val EMULATED_PREFIX = "/storage/emulated/"

