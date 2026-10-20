package me.gm.cleaner.runtime.mediaprovider.hook.policy

import org.json.JSONObject

/**
 * 受防护 hook 方法（AbstractGuardedHook 子类）的熔断观测状态。
 *
 * 职责边界：只维护"熔断计数与最近失败事实"，
 * 不负责发布快照（由 [NativeHookStatus] 承担），
 * 也不负责熔断判定本身（由宿主侧 AbstractGuardedHook 负责）。
 *
 * 与 [NativeHookStatus] 分离的动因：后者聚合五个状态域并负责快照组装，
 * 熔断观测是其中一个独立子域，把它的状态与序列化集中在此，
 * 避免状态字段与序列化代码分散在聚合类各处。
 */
internal class GuardedHookCircuitState {
    @Volatile
    private var circuitOpenCount = 0
    @Volatile
    private var lastFailedHook = ""
    @Volatile
    private var lastFailedMethod = ""
    @Volatile
    private var lastFailureType = ""
    @Volatile
    private var totalOpenTransitions = 0L
    @Volatile
    private var recoveredCount = 0L
    @Volatile
    private var swallowedHostExceptions = 0L
    @Volatile
    private var swallowedLastPublishAt = 0L

    /**
     * 熔断打开（进入冷却）：打开计数与历史转换数同步递增。
     *
     * @param failureType 触发异常的类简名。
     */
    fun markCircuitOpened(hookName: String, method: String, failureType: String) {
        circuitOpenCount += 1
        totalOpenTransitions += 1
        lastFailedHook = hookName
        lastFailedMethod = method
        lastFailureType = failureType.take(NativeHookStatus.MAX_TEXT_LENGTH)
    }

    /** 半开探针成功、熔断恢复闭合。 */
    fun markCircuitRecovered() {
        if (circuitOpenCount > 0) {
            circuitOpenCount -= 1
        }
        recoveredCount += 1
    }

    /**
     * 宿主抛出的预期异常被静默吞掉：仅递增计数。
     *
     * @return 是否达到最小发布间隔（由调用方决定是否发布快照）
     */
    fun markHostExceptionSwallowed(): Boolean {
        swallowedHostExceptions += 1
        val now = System.currentTimeMillis()
        if (now - swallowedLastPublishAt >= SWALLOWED_PUBLISH_INTERVAL_MILLIS) {
            swallowedLastPublishAt = now
            return true
        }
        return false
    }

    /** 快照序列化：`guardedHooks` 段。 */
    fun toJson(): JSONObject = JSONObject().apply {
        put("circuitOpenCount", circuitOpenCount)
        put("lastFailedHook", lastFailedHook)
        put("lastFailedMethod", lastFailedMethod)
        put("lastFailureType", lastFailureType)
        put("totalOpenTransitions", totalOpenTransitions)
        put("recoveredCount", recoveredCount)
        put("swallowedHostExceptions", swallowedHostExceptions)
    }

    companion object {
        /** 宿主异常吞掉计数的快照发布最小间隔。 */
        const val SWALLOWED_PUBLISH_INTERVAL_MILLIS = 30_000L
    }
}
