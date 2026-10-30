package me.gm.cleaner.runtime.mediaprovider.hook.policy

import android.util.Log
import me.gm.cleaner.core.common.err.ErrorCodes
import me.gm.cleaner.runtime.mediaprovider.hook.bridge.HookDataBusBridge
import me.gm.cleaner.runtime.mediaprovider.hook.bridge.DataBusBridgeResult
import org.json.JSONObject
import me.gm.cleaner.core.storage.redirect.databus.DataBusProtocol

/**
 * 只读策略域消费组件。
 *
 * 消费 [DataBus] 发布的 read_only.json 快照，缓存 packageName → 路径集合的映射。
 * 消费走 [consumeFromDataBus]：读取前捕获信号水位，成功才推进，失败与 bridge
 * 不可用不推进；信号水位由 [HookPolicyCache] 自持，域仅回传 [SignalConsumeResult]。
 * 解析过程中会根据结果调用 [NativeHookStatus.markReadOnlyPolicyApplied] /
 * [NativeHookStatus.markReadOnlyPolicyFailed] / [NativeHookStatus.markPolicyCacheFailed]
 * 记录状态反馈，不属于被动缓存。状态快照的发布仍由 [NativeHookStatus]
 * 统一负责。
 *
 * ## 降级行为
 * - bridge 不可用 → 失败结果携带 op:reason，由调用方统一做状态诊断；
 *   快照缺失 → 失败记录 SNAPSHOT_MISSING；解析失败 → 保留当前缓存并记录失败域。
 */
internal class ReadOnlyPolicyCache {
    private val TAG = "ReadOnlyPolicyCache"

    // ── 按域分组的 copy-on-write holder ──
    private data class ReadOnlyHolder(
        val data: Map<String, Set<String>> = emptyMap(),
        val generation: Long = 0L,
        val publisherEpoch: String = "",
        val revision: String = "",
    )

    @Volatile
    private var readOnly: ReadOnlyHolder = ReadOnlyHolder()

    val generation: Long
        get() = readOnly.generation

    /**
     * 检查指定路径是否在只读规则中。
     *
     * @param packageName 包名
     * @param pathAsUser 按 userId=0 展开的路径
     * @return true 如果路径命中只读规则
     */
    fun isReadOnly(packageName: String, pathAsUser: String): Boolean {
        val readOnlyPaths = readOnly.data[packageName] ?: return false
        return ReadOnlyPathMatcher.matchesAny(readOnlyPaths, pathAsUser)
    }

    /**
     * 获取只读规则的 generation，用于判断缓存是否存在。
     * 返回 0 表示未加载任何快照。
     */
    fun getReadOnlyGeneration(): Long = readOnly.generation

    /**
     * 消费 read_only 快照：在读取前捕获信号水位；成功才推进，
     * 解析失败与 bridge 不可用时保留调用方传入的水位（详见
     * [consumeAfterSignalCapture] 协议）。水位存储由调用方持有。
     *
     * @param acknowledgedTimestamp 调用方当前已确认的信号水位
     */
    fun consumeFromDataBus(acknowledgedTimestamp: Long): SignalConsumeResult = consumeAfterSignalCapture(
        currentAcknowledgedTimestamp = acknowledgedTimestamp,
        captureSignalTimestamp = {
            HookDataBusBridge.getSignalTimestamp(DataBusProtocol.SIGNAL_READ_ONLY_CHANGED)
        },
        consumeSnapshot = { loadFromDataBus() },
    )

    /** 从 DataBus 加载 read_only.json 快照并更新缓存。 */
    private fun loadFromDataBus(): SnapshotConsumeOutcome {
        val read = HookDataBusBridge.readSnapshot(DataBusProtocol.SNAPSHOT_READ_ONLY)
        when (read) {
            is DataBusBridgeResult.Unavailable -> return SnapshotConsumeOutcome(
                succeeded = false,
                unavailableReason = "${read.operation}:${read.reason}",
            )
            is DataBusBridgeResult.Available -> {
                if (read.value == null) {
                    Log.d(TAG, "loadReadOnly: no snapshot available")
                    NativeHookStatus.markReadOnlyPolicyFailed(
                        "", "read_only snapshot missing at init"
                    )
                    NativeHookStatus.markPolicyCacheFailed(
                        ErrorCodes.HOOK_JAVA_CACHE_SNAPSHOT_MISSING,
                        "read_only snapshot missing at init", 0L,
                        NativeHookStatus.POLICY_DOMAIN_READ_ONLY,
                    )
                    // 快照缺失是消费失败，不能返回 succeeded = true，
                    // 否则 consumeAfterSignalCapture 会错误推进信号水位，
                    // 导致后续快照恢复但无新 signal 时不再被主动读取。
                    return SnapshotConsumeOutcome(succeeded = false, changed = false)
                }
                return try {
                    val root = JSONObject(read.value)
                    val generation = root.optLong("generation", 0L)
                    val publisherEpoch = root.optString("publisherEpoch", "")
                    val revision = root.optString("readOnlyRevision", "")
                    val current = readOnly
                    val sameIdentity = publisherEpoch == current.publisherEpoch &&
                            generation == current.generation && revision == current.revision
                    if (sameIdentity && NativeHookStatus.markReadOnlyPolicyFreshIfApplied(
                            publisherEpoch, generation, revision,
                        )) {
                        return SnapshotConsumeOutcome(succeeded = true, changed = false)
                    }
                    if (!sameIdentity && !shouldAcceptSnapshot(
                            publisherEpoch, generation, current.publisherEpoch, current.generation,
                        )) {
                        Log.d(TAG, "loadReadOnly: snapshot not newer " +
                                "(epoch=$publisherEpoch generation=$generation, " +
                                "currentEpoch=${current.publisherEpoch} currentGeneration=${current.generation})")
                        return SnapshotConsumeOutcome(succeeded = true, changed = false)
                    }
                    val newCache = mutableMapOf<String, Set<String>>()
                    val roObj = root.optJSONObject("readOnlyRules")
                    if (roObj != null) {
                        for (pkg in roObj.keys()) {
                            val arr = roObj.optJSONArray(pkg) ?: continue
                            val paths = mutableSetOf<String>()
                            for (i in 0 until arr.length()) {
                                paths.add(arr.getString(i))
                            }
                            if (paths.isNotEmpty()) {
                                newCache[pkg] = paths.toSet()
                            }
                        }
                    }

                    readOnly = ReadOnlyHolder(
                        data = newCache.toMap(),
                        generation = generation,
                        publisherEpoch = publisherEpoch,
                        revision = revision,
                    )
                    NativeHookStatus.markReadOnlyPolicyApplied(
                        revision, generation, newCache.isNotEmpty(), publisherEpoch,
                    )
                    Log.i(TAG, "loadReadOnly: loaded read_only, generation=$generation, " +
                            "packages=${readOnly.data.size}")
                    SnapshotConsumeOutcome(succeeded = true, changed = true)
                } catch (e: Exception) {
                    Log.e(TAG, "loadReadOnly: failed", e)
                    NativeHookStatus.markReadOnlyPolicyFailed(
                        extractSnapshotRevision(read.value ?: "", "readOnlyRevision"),
                        describeThrowable(e),
                    )
                    NativeHookStatus.markPolicyCacheFailed(
                        ErrorCodes.HOOK_JAVA_CACHE_PARSE_FAILED,
                        describeThrowable(e), readOnly.generation,
                        NativeHookStatus.POLICY_DOMAIN_READ_ONLY,
                    )
                    SnapshotConsumeOutcome(succeeded = false)
                }
            }
        }
    }

    /** 供 markPolicyCacheFailed 使用的受控异常描述；截断由 NativeHookStatus 统一处理。 */
    private fun describeThrowable(e: Throwable): String {
        val message = e.message?.takeIf { it.isNotBlank() }
        return if (message == null) e.javaClass.name else "${e.javaClass.name}: $message"
    }

    private fun extractSnapshotRevision(json: String, key: String): String =
        runCatching { JSONObject(json).optString(key, "") }.getOrDefault("")
}
