package me.gm.cleaner.runtime.mediaprovider.hook.policy

import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hook 层失败事实的收集与快照序列化。
 *
 * 职责边界：本对象只负责"有界地收集 Hook 侧失败事实并序列化"，
 * 不负责发布快照（由 [NativeHookStatus.publishSnapshot] 承担），
 * 也不负责状态快照的其余字段。
 *
 * 与 [NativeHookStatus] 分离的动因：后者是"Hook 运行状态快照"（inline / native /
 * policy / bridge / fuseJavaGate 五个状态域），失败事件流水是另一种数据形态
 * （有界队列 + 淘汰计数 + 独立序列化），生命周期与消费方式都不同。
 *
 * 设计约束：
 * 1. 线程安全 —— Hook 方法可能在宿主多线程并发进入，故用显式锁保护；
 * 2. 容量有界 —— 最多保留 [MAX_HOOK_ERRORS] 条，超出淘汰最旧并累计丢弃数；
 * 3. 永不抛出 —— 记录失败只打日志，不影响 Hook 主流程。
 */
internal class HookErrorCollector {
    companion object {
        /**
         * Hook 层失败事实列表的容量上限，超出淘汰最旧条目。
         * 上限存在的意义是：异常风暴下快照体积仍然有界，且不会挤掉早先的根因线索。
         */
        const val MAX_HOOK_ERRORS = 20

        /**
         * Hook 层失败事实的日志 tag。
         * 与 Hook 侧其它日志（XposedInit / AbstractGuardedHook）保持一致，便于 grep。
         */
        const val LOG_TAG = "MC_REDIRECT"
    }

    private val lock = Any()
    private val entries = java.util.ArrayDeque<HookErrorEntry>()

    /** 因超出 [MAX_HOOK_ERRORS] 被淘汰的条目数；>0 表示列表被截断过。 */
    private var droppedCount = 0L

    /**
     * 记录一条失败事实。
     *
     * @param code 统一错误码，必须引用 [ErrorCodes.HOOK_JAVA_*] 常量，
     *             便于消费方机器归并同类故障。
     * @param detail 受控短文本（阶段名/包名/异常类名等），
     *                禁止写入明文路径或任何用户数据。
     * @return 记录成功的条目；记录过程出错时返回 null（已自行记录日志）。
     */
    fun record(code: String, detail: String?): HookErrorEntry? {
        return runCatching {
            val entry = HookErrorEntry(
                code = code.take(NativeHookStatus.MAX_TEXT_LENGTH),
                atElapsedRealtimeMillis = monotonicMillis(),
                detail = detail?.take(NativeHookStatus.MAX_TEXT_LENGTH).orEmpty(),
            )
            synchronized(lock) {
                while (entries.size >= MAX_HOOK_ERRORS) {
                    entries.removeFirst()
                    droppedCount += 1L
                }
                entries.addLast(entry)
            }
            entry
        }.onFailure {
            runCatching { Log.w(NativeHookStatus.TAG, "recordHookError failed: " + code, it) }
        }.getOrNull()
    }

    /**
     * 快照序列化：`hookErrors` 段。
     * 纯附加段，既有字段语义不变，老消费方忽略即可。
     */
    fun toJson(): JSONObject {
        val (snapshot, dropped) = synchronized(lock) { entries.toList() to droppedCount }
        return JSONObject().apply {
            put("count", snapshot.size)
            put("droppedCount", dropped)
            put("items", JSONArray().apply {
                snapshot.forEach { entry ->
                    put(JSONObject().apply {
                        put("code", entry.code)
                        put("atElapsedRealtimeMillis", entry.atElapsedRealtimeMillis)
                        put("detail", entry.detail)
                    })
                }
            })
        }
    }

    /**
     * 单调时钟毫秒数。
     *
     * 优先使用 Android 的 elapsedRealtime；非 Android 运行时（如 JVM 单测）
     * 回退到 nanoTime，保证该入口在任何环境下都不抛异常。
     */
    private fun monotonicMillis(): Long = runCatching {
        SystemClock.elapsedRealtime()
    }.getOrElse {
        System.nanoTime() / 1_000_000L
    }

    /**
     * 一条 Hook 层失败事实。
     *
     * @param code 统一错误码（[ErrorCodes.HOOK_JAVA_*]）。
     * @param atElapsedRealtimeMillis 单调时钟时间戳（elapsedRealtime）。
     *        单调钟只保证同一次进程生命周期内的先后顺序，不跨重启可比，
     *        这正是"本次 Hook 会话内"事实所需要的语义。
     * @param detail 受控短文本，禁止明文路径与用户数据。
     */
    data class HookErrorEntry(
        val code: String,
        val atElapsedRealtimeMillis: Long,
        val detail: String,
    )
}
