package me.gm.cleaner.runtime.mediaprovider.hook;

import android.os.SystemClock;
import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;

/**
 * 统一异常防护的 Java Hook 装饰器基类。
 *
 * <p>本类把 {@link FuseJavaGate} 的 {@code installHook} 防护段（经过生产验证的样板）
 * 提炼为 MediaProvider 宿主进程内所有 Java Hook 共享的不变量：
 * <ol>
 *   <li><b>任何 handler 异常不外抛</b> —— 除 {@link VirtualMachineError} 与
 *       {@link ThreadDeath} 外一律吞掉并记录，宿主 MediaProvider 永远不会因
 *       本模块的 Hook 逻辑而崩溃；</li>
 *   <li><b>参数回滚</b> —— handler 抛异常时，{@code guardedArgIndexes}
 *       指定的 args 槽位恢复为进入时的原值，避免半成品参数污染宿主流程；</li>
 *   <li><b>每方法熔断</b> —— 每个 Hook 实例独享一个
 *       {@link HookHandlerCircuitBreaker}，连续失败后冷却，冷却期只放行
 *       一个半开探针，防止异常风暴反复冲击宿主。</li>
 * </ol>
 *
 * <p><b>同进程统一异常策略：</b>FuseJavaGate 的 FUSE 方法族 Hook 与本基类覆盖的
 * query / insertFile / scan_file Hook 采用同一套「可选增强」哲学 —— Hook 失败只
 * 降级本模块自身功能，绝不阻断、更不崩溃宿主。区别仅在于 FuseJavaGate 直接对接
 * NativeHookStatus 上报 fuseJavaGate 段，而本基类通过 {@link #onCircuitOpened} /
 * {@link #onCircuitRecovered} 回调上报 guardedHooks 段：默认实现直接写入
 * NativeHookStatus，使 Query / Insert / Scan 熔断与 FUSE 门同等可见；
 * 子类可覆写回调以追加行为（如额外日志），但无需为可见性而覆写。
 */
public abstract class AbstractGuardedHook extends XC_MethodHook {
    /**
     * Hook 行为契约：以对象形态描述一段受防护的业务逻辑，
     * 供不便继承的场景以 lambda / 匿名类组合复用。
     */
    public interface Handler {
        void handle(XC_MethodHook.MethodHookParam param) throws Throwable;
    }

    /** 快照与日志统一使用的项目 tag。 */
    private static final String LOG_TAG = "MC_REDIRECT";

    private final String tag;
    private final String methodName;
    private final int[] guardedArgIndexes;

    /** 每 Hook 实例独享的熔断器；包私有以便同包测试观测计数。 */
    final HookHandlerCircuitBreaker circuitBreaker = new HookHandlerCircuitBreaker();

    protected AbstractGuardedHook(final String tag, final String methodName,
                                  final int[] guardedArgIndexes) {
        this.tag = tag;
        this.methodName = methodName;
        this.guardedArgIndexes = guardedArgIndexes.clone();
    }

    /**
     * 子类业务逻辑：抛出的任何异常都由防护层兜底，
     * 不会传播到宿主 MediaProvider。
     */
    protected abstract void handleBefore(XC_MethodHook.MethodHookParam param) throws Throwable;

    /**
     * 熔断打开（进入冷却）时触发，openUntilUptimeMillis 为冷却截止时刻。
     * 默认实现上报 NativeHookStatus.guardedHooks 段。
     */
    protected void onCircuitOpened(long openUntilUptimeMillis, Throwable cause) {
        NativeHookStatus.INSTANCE.markGuardedCircuitOpened(
                tag, methodName, failureTypeOf(cause));
    }

    /**
     * 半开探针成功、熔断恢复闭合时触发。
     * 默认实现上报 NativeHookStatus.guardedHooks 段。
     */
    protected void onCircuitRecovered() {
        NativeHookStatus.INSTANCE.markGuardedCircuitRecovered(tag);
    }

    /**
     * 时钟钩子：生产路径即 SystemClock.elapsedRealtime()；
     * 测试覆写以推进熔断冷却与半开探针时序。
     */
    protected long uptimeMillis() {
        return SystemClock.elapsedRealtime();
    }

    private String failureTypeOf(final Throwable cause) {
        return cause.getClass().getSimpleName();
    }

    /**
     * 精确移植 FuseJavaGate.installHook 防护段语义：
     * 熔断准入 → 参数快照 → try/catch 兜底 → 异常分级处理。
     */
    @Override
    protected final void beforeHookedMethod(final XC_MethodHook.MethodHookParam param) {
        final long acquiredAt = uptimeMillis();
        final HookHandlerCircuitBreaker.Permit permit =
                circuitBreaker.tryAcquire(acquiredAt);
        if (permit == null) {
            // 熔断冷却期内：直接放行宿主原方法，handler 不执行。
            return;
        }
        // 快照受保护槽位原值，handler 失败时回滚，保证宿主拿到未污染参数。
        final Object[] originalArgs = new Object[guardedArgIndexes.length];
        for (int i = 0; i < guardedArgIndexes.length; i++) {
            originalArgs[i] = param.args[guardedArgIndexes[i]];
        }
        try {
            handleBefore(param);
            if (circuitBreaker.onSuccess(permit) ==
                    HookHandlerCircuitBreaker.SuccessOutcome.RECOVERED) {
                onCircuitRecovered();
                Log.i(LOG_TAG, "[" + tag + "] handler circuit recovered");
            }
        } catch (final Throwable t) {
            for (int i = 0; i < guardedArgIndexes.length; i++) {
                param.args[guardedArgIndexes[i]] = originalArgs[i];
            }
            if (t instanceof VirtualMachineError || t instanceof ThreadDeath) {
                circuitBreaker.onAbort(permit, uptimeMillis());
                throw (Error) t;
            }
            final long failedAt = uptimeMillis();
            final var outcome = circuitBreaker.onFailure(permit, failedAt);
            final String message = "[" + tag + "] handler failed, failures="
                    + circuitBreaker.consecutiveFailures();
            if (outcome == HookHandlerCircuitBreaker.FailureOutcome.OPENED) {
                onCircuitOpened(failedAt + HookHandlerCircuitBreaker.COOLDOWN_MILLIS, t);
                Log.e(LOG_TAG, message + ", circuit cooldown="
                        + circuitBreaker.remainingCooldownMillis(failedAt) + "ms", t);
            } else if (outcome == HookHandlerCircuitBreaker.FailureOutcome.COUNTED &&
                    circuitBreaker.shouldLogCountedFailure(failedAt)) {
                Log.w(LOG_TAG, message, t);
            }
            // 永不阻断宿主操作 —— Hook 是可选增强，不是系统依赖
            // （对齐 FuseJavaGate 同语义注释）。
        }
    }

    /**
     * 防护契约同样覆盖 after 阶段（审计 O6）：子类逻辑应全部放在 handleBefore；
     * final 空实现阻止旁路熔断与异常兜底的覆写口子。
     */
    @Override
    protected final void afterHookedMethod(final XC_MethodHook.MethodHookParam param) {
    }
}
