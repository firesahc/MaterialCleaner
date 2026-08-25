package me.gm.cleaner.runtime.mediaprovider.hook;

/** FUSE Java Hook 单方法异常熔断；冷却后只允许一个半开探针。 */
final class HookHandlerCircuitBreaker {
    static final int FAILURE_THRESHOLD = 3;
    static final long COOLDOWN_MILLIS = 30_000L;
    static final long CLOSED_FAILURE_LOG_INTERVAL_MILLIS = 30_000L;

    enum FailureOutcome {
        COUNTED,
        OPENED,
        STALE,
    }

    enum SuccessOutcome {
        NORMAL,
        RESET_FAILURES,
        RECOVERED,
        STALE,
    }

    static final class Permit {
        private final long epoch;
        private final boolean halfOpenProbe;

        private Permit(long epoch, boolean halfOpenProbe) {
            this.epoch = epoch;
            this.halfOpenProbe = halfOpenProbe;
        }
    }

    private int consecutiveFailures;
    private long openUntilUptimeMillis;
    private boolean probeInFlight;
    private long epoch;
    private long lastCountedFailureLogAt = -1L;

    synchronized Permit tryAcquire(long nowUptimeMillis) {
        if (openUntilUptimeMillis == 0L) return new Permit(epoch, false);
        if (nowUptimeMillis < openUntilUptimeMillis || probeInFlight) return null;
        probeInFlight = true;
        return new Permit(epoch, true);
    }

    synchronized FailureOutcome onFailure(Permit permit, long nowUptimeMillis) {
        if (permit.epoch != epoch) return FailureOutcome.STALE;
        probeInFlight = false;
        consecutiveFailures++;
        if (permit.halfOpenProbe || consecutiveFailures >= FAILURE_THRESHOLD) {
            openUntilUptimeMillis = nowUptimeMillis + COOLDOWN_MILLIS;
            epoch++;
            return FailureOutcome.OPENED;
        }
        return FailureOutcome.COUNTED;
    }

    synchronized SuccessOutcome onSuccess(Permit permit) {
        if (permit.epoch != epoch) return SuccessOutcome.STALE;
        if (permit.halfOpenProbe) {
            consecutiveFailures = 0;
            openUntilUptimeMillis = 0L;
            probeInFlight = false;
            epoch++;
            return SuccessOutcome.RECOVERED;
        }
        final boolean resetFailures = consecutiveFailures > 0;
        consecutiveFailures = 0;
        return resetFailures ? SuccessOutcome.RESET_FAILURES : SuccessOutcome.NORMAL;
    }

    synchronized void onAbort(Permit permit, long nowUptimeMillis) {
        if (permit.epoch != epoch || !permit.halfOpenProbe) return;
        probeInFlight = false;
        openUntilUptimeMillis = nowUptimeMillis + COOLDOWN_MILLIS;
        epoch++;
    }

    synchronized int consecutiveFailures() {
        return consecutiveFailures;
    }

    synchronized long remainingCooldownMillis(long nowUptimeMillis) {
        return Math.max(0L, openUntilUptimeMillis - nowUptimeMillis);
    }

    synchronized boolean shouldLogCountedFailure(long nowUptimeMillis) {
        if (consecutiveFailures >= FAILURE_THRESHOLD - 1) {
            lastCountedFailureLogAt = nowUptimeMillis;
            return true;
        }
        if (lastCountedFailureLogAt < 0L ||
                nowUptimeMillis - lastCountedFailureLogAt >= CLOSED_FAILURE_LOG_INTERVAL_MILLIS) {
            lastCountedFailureLogAt = nowUptimeMillis;
            return true;
        }
        return false;
    }
}
