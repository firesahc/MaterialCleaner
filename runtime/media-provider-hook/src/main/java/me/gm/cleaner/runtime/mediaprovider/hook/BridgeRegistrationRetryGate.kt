package me.gm.cleaner.runtime.mediaprovider.hook

/**
 * 合并 MediaProvider Binder 重注册请求，防止外部事件重置正在执行的突发预算或冷却期。
 */
class BridgeRegistrationRetryGate {
    private enum class State {
        IDLE,
        WAITING,
        RUNNING,
    }

    private var state = State.IDLE

    @Synchronized
    fun requestSchedule(): Boolean {
        if (state != State.IDLE) return false
        state = State.WAITING
        return true
    }

    @Synchronized
    fun beginScheduledRun(): Boolean {
        if (state != State.WAITING) return false
        state = State.RUNNING
        return true
    }

    @Synchronized
    fun markWaiting() {
        check(state == State.RUNNING)
        state = State.WAITING
    }

    @Synchronized
    fun markIdle() {
        state = State.IDLE
    }
}
