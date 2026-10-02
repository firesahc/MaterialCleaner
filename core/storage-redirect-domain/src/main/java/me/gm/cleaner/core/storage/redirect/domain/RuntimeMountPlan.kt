package me.gm.cleaner.core.storage.redirect.domain

/**
 * VFS 执行所需的已投影挂载计划。
 *
 * P4 迁移：VFS/Mounter 不再持有 [MountRules]，只消费这份已决定的数据。
 * [mountPoints] 由 canonical interpreter 推导（[OrderedRedirectInterpreter]），
 * 经 P2 差分护栏证明与旧实现全等；非规范历史数据回退旧实现，永不抛异常。
 */
data class RuntimeMountPlan(
    val packageName: String,
    val userId: Int,
    val sources: List<String>,
    val targets: List<String>,
    val mountPoints: List<String>,
) {
    fun isEmpty(): Boolean = sources.isEmpty()

    /** Mounter 建目录所需：挂载点推导结果 + 承载源目录。 */
    val mkdirList: List<String>
        get() = mountPoints + sources
}

/**
 * 快照裸规则对到挂载计划的唯一投影桥。
 *
 * [RedirectPolicyDeriver]、VFS store、后续 Hook 统一经此入口，
 * 不得各自重写 Pair 解释。空规则返回 null，与旧 `getMountRules` 语义一致。
 */
object MountPlanDeriver {

    fun derive(
        packageName: String,
        userId: Int,
        rules: List<RedirectRule>,
    ): RuntimeMountPlan? {
        if (rules.isEmpty()) return null
        val zipped = rules.map { it.source to it.target }
        return RuntimeMountPlan(
            packageName = packageName,
            userId = userId,
            sources = zipped.map { it.first },
            targets = zipped.map { it.second },
            mountPoints = deriveMountPoints(zipped),
        )
    }

    fun resolveMountedPath(rules: List<RedirectRule>, path: String): String {
        if (rules.isEmpty()) return path
        val zipped = rules.map { it.source to it.target }
        return interpretMountedPath(zipped, path)
    }

    private fun deriveMountPoints(zipped: List<Pair<String, String>>): List<String> {
        val ordered = toOrderedRulesOrNull(zipped) ?: return MountRules(zipped).mountPoint
        return try {
            OrderedRedirectInterpreter.deriveMountPoints(ordered)
                .map(RedirectMountPoint::derivedPath)
        } catch (_: IllegalArgumentException) {
            MountRules(zipped).mountPoint
        }
    }

    private fun interpretMountedPath(zipped: List<Pair<String, String>>, path: String): String {
        val ordered = toOrderedRulesOrNull(zipped) ?: return MountRules(zipped).getMountedPath(path)
        return try {
            OrderedRedirectInterpreter.interpret(path, ordered).derivedPath
        } catch (_: IllegalArgumentException) {
            MountRules(zipped).getMountedPath(path)
        }
    }

    private fun toOrderedRulesOrNull(zipped: List<Pair<String, String>>): List<OrderedRedirectRule>? {
        return try {
            zipped.mapIndexed { index, (source, target) ->
                OrderedRedirectRule(
                    ruleId = RuleId("mount-plan-$index"),
                    type = if (source == target) RedirectRuleType.PRESERVE else RedirectRuleType.MAP,
                    source = source,
                    target = target,
                    orderIndex = index,
                )
            }
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
