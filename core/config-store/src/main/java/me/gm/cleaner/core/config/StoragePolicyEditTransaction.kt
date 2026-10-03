package me.gm.cleaner.core.config

import android.util.Log
import me.gm.cleaner.core.storage.redirect.domain.StoragePolicyEnvelope

/**
 * 存储策略批量编辑事务：暂存多包变更后单次提交。
 *
 * P9 收敛：替代门面批量暂存，Store 直写，无旧 JSON 分支。
 * 未初始化时跳过并告警，与旧行为一致。
 */
class StoragePolicyEditTransaction(
    private val store: ConfiguredPolicyStore = ConfiguredPolicyStoreProvider.instance,
) {
    private var pendingRedirect: StoragePolicyEnvelope? = null
    private var pendingReadOnly: StoragePolicyEnvelope? = null
    private var committed = false

    fun putRedirect(rawRules: List<Pair<String, String>>, packageNames: List<String>) {
        check(!committed) { "事务已提交，不可复用" }
        pendingRedirect = stageRedirect(pendingRedirect, rawRules, packageNames)
    }

    fun putReadOnly(rawRules: List<String>, packageNames: List<String>) {
        check(!committed) { "事务已提交，不可复用" }
        pendingReadOnly = stageReadOnly(pendingReadOnly, rawRules, packageNames)
    }

    fun commit(): Boolean {
        check(!committed) { "事务已提交，不可复用" }
        committed = true
        var ok = true
        pendingRedirect?.let { pending ->
            val result = store.updateRedirect(null) { pending }
            if (!result.success) {
                Log.e(TAG, "Failed to commit redirect policy batch: ${result.error}")
                ok = false
            }
        }
        pendingReadOnly?.let { pending ->
            val result = store.updateReadOnly(null) { pending }
            if (!result.success) {
                Log.e(TAG, "Failed to commit read-only policy batch: ${result.error}")
                ok = false
            }
        }
        return ok
    }

    private fun stageRedirect(
        staged: StoragePolicyEnvelope?,
        rawRules: List<Pair<String, String>>,
        packageNames: List<String>,
    ): StoragePolicyEnvelope {
        val base = staged ?: store.readRedirect().envelope
        return try {
            base.replaceRedirectRules(rawRules, packageNames)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stage redirect policy batch", e)
            base
        }
    }

    private fun stageReadOnly(
        staged: StoragePolicyEnvelope?,
        rawRules: List<String>,
        packageNames: List<String>,
    ): StoragePolicyEnvelope {
        val base = staged ?: store.readReadOnly().envelope
        return try {
            base.replaceReadOnlyRules(rawRules, packageNames)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stage read-only policy batch", e)
            base
        }
    }

    private companion object {
        const val TAG = "StoragePolicyEditTransaction"
    }
}
