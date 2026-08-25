package me.gm.cleaner.runtime.server.observer

/**
 * 为一个 mount namespace 选择唯一的配置代表。
 *
 * Native bind_mount 每次都会重建整个 /storage，所以同一进程只能执行一次。共享进程内
 * 所有包的重定向规则与目录限制处理方式必须完全一致；任一项不同都无法按包隔离，
 * 只能显式降级（拒绝挂载并恢复 baseline），绝不产生依赖遍历顺序的不确定结果。
 */
internal object ProcessMountSelectionPolicy {
    sealed interface Selection {
        data class Selected(val packageName: String) : Selection
        data class Conflict(val packageNames: Set<String>) : Selection
        object None : Selection
    }

    fun resolve(
        packageNames: Array<String>,
        redirectRuleSignature: (String) -> List<Pair<String, String>>?,
        shouldUnmountDataRestriction: (String) -> Boolean,
    ): Selection {
        val distinctPackages = packageNames.distinct()
        if (distinctPackages.isEmpty()) return Selection.None

        val mountSignatures = distinctPackages.associateWith { packageName ->
            MountSignature(
                redirectRules = redirectRuleSignature(packageName).orEmpty(),
                unmountDataRestriction = shouldUnmountDataRestriction(packageName),
            )
        }
        if (mountSignatures.values.distinct().size > 1) {
            return Selection.Conflict(distinctPackages.toCollection(linkedSetOf()))
        }
        return Selection.Selected(distinctPackages.first())
    }

    private data class MountSignature(
        val redirectRules: List<Pair<String, String>>,
        val unmountDataRestriction: Boolean,
    )
}
