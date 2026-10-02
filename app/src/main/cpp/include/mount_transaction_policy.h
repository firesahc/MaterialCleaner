#pragma once

namespace mount_transaction {

enum class Phase : int {
    PREPARE = 0,
    NAMESPACE_ENTERED = 1,
    MUTATING_BASELINE = 2,
    BASELINE_READY = 3,
    APPLYING_RULES = 4,
    ROLLING_BACK = 5,
};

constexpr int kSafePhaseTimeoutSeconds = 3;
constexpr int kMutationPhaseTimeoutSeconds = 15;

constexpr bool phase_may_have_dirty_namespace(Phase phase) {
    // P0：BASELINE_READY 正处 MNT_DETACH 异步回收窗口（Mount.cpp 自注），
    // 超时杀 child 后立即重试会叠加进中间态 namespace，保守判脏。
    return phase == Phase::MUTATING_BASELINE ||
            phase == Phase::BASELINE_READY ||
            phase == Phase::APPLYING_RULES ||
            phase == Phase::ROLLING_BACK;
}

constexpr int timeout_seconds(Phase phase) {
    return phase_may_have_dirty_namespace(phase)
            ? kMutationPhaseTimeoutSeconds
            : kSafePhaseTimeoutSeconds;
}

static_assert(timeout_seconds(Phase::ROLLING_BACK) > timeout_seconds(Phase::PREPARE));

}  // namespace mount_transaction
