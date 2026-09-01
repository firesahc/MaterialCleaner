package me.gm.cleaner.core.storage.redirect.domain

/** 路径操作的安全等级；声明顺序即从宽松到严格的归约顺序。 */
enum class PathPolicyDecision {
    ALLOW,
    READ_ONLY,
    DENY,
}

/**
 * 策略求值所需的操作类别。
 *
 * 只读规则仅约束 [mutatesStorage] 操作。DENY_ALL 对读取和写入都生效。
 */
enum class PathOperation(
    val mutatesStorage: Boolean,
) {
    LOOKUP(false),
    READ_METADATA(false),
    READ_CONTENT(false),
    WRITE_CONTENT(true),
    CREATE(true),
    DELETE(true),
    RENAME(true),
    WRITE_METADATA(true),
}

/**
 * 一次操作涉及的一条路径。
 *
 * [mayAffectDescendants] 表示操作可能改变该路径下整棵子树。此时即使操作路径是保护根的
 * 祖先，也必须保守命中；普通 lookup/readdir 不设置它，从而保留 DENY 根的父目录名称可见性。
 */
data class OperationPath(
    val visiblePath: String,
    val mayAffectDescendants: Boolean = false,
)

data class OperationFootprint(
    val operation: PathOperation,
    val paths: List<OperationPath>,
) {
    init {
        require(paths.isNotEmpty()) { "操作路径集合不能为空" }
        paths.forEach { path ->
            OrderedRedirectInterpreter.requireCanonicalAbsolutePath(
                path.visiblePath,
                "operationPath",
            )
        }
        if (operation == PathOperation.RENAME) {
            require(paths.size == 2) { "RENAME 必须同时提供 source 与 destination" }
            require(paths.all(OperationPath::mayAffectDescendants)) {
                "RENAME 两端都必须标记为可能影响后代"
            }
        }
    }

    companion object {
        fun single(
            operation: PathOperation,
            visiblePath: String,
            mayAffectDescendants: Boolean = operation in DESCENDANT_AFFECTING_OPERATIONS,
        ): OperationFootprint = OperationFootprint(
            operation = operation,
            paths = listOf(OperationPath(visiblePath, mayAffectDescendants)),
        )

        fun rename(
            source: String,
            destination: String,
        ): OperationFootprint = OperationFootprint(
            operation = PathOperation.RENAME,
            paths = listOf(
                OperationPath(source, mayAffectDescendants = true),
                OperationPath(destination, mayAffectDescendants = true),
            ),
        )

        private val DESCENDANT_AFFECTING_OPERATIONS = setOf(
            PathOperation.DELETE,
            PathOperation.RENAME,
            PathOperation.WRITE_METADATA,
        )
    }
}

data class EvaluatedOperationPath(
    val originalVisiblePath: String,
    val derivedPath: String,
    val reachableAliases: List<String>,
    val aliasClosureComplete: Boolean,
)

data class PathPolicyEvaluation(
    val decision: PathPolicyDecision,
    val paths: List<EvaluatedOperationPath>,
    val matchedRuleIds: List<RuleId>,
) {
    /** 单路径调用的便捷结果；多路径操作应读取 [paths]。 */
    val derivedPath: String
        get() = paths.first().derivedPath

    val aliasClosureComplete: Boolean
        get() = paths.all(EvaluatedOperationPath::aliasClosureComplete)
}

/**
 * 纯路径策略求值器。
 *
 * 调用方必须先按包、userId/appId/shared UID 与卷筛选出同一 AccessSafetyDomain 的规则。
 * 本类对操作 footprint 的每一端分别建立 有序重定向轨迹，并在原始可见路径与轨迹中
 * 所有派生 alias 上求值。最终按 DENY > READ_ONLY > ALLOW 归约。
 */
object PathPolicyEvaluator {

    fun evaluate(
        footprint: OperationFootprint,
        redirectRules: List<OrderedRedirectRule>,
        readOnlyRules: List<ReadOnlyRule>,
        denyAllRules: List<DenyAllRule>,
    ): PathPolicyEvaluation {
        readOnlyRules.forEach { rule ->
            OrderedRedirectInterpreter.requireCanonicalAbsolutePath(
                rule.visiblePath,
                "readOnlyRule[${rule.ruleId.value}]",
            )
        }
        denyAllRules.forEach { rule ->
            OrderedRedirectInterpreter.requireCanonicalAbsolutePath(
                rule.visiblePath,
                "denyAllRule[${rule.ruleId.value}]",
            )
        }

        val matchedRuleIds = linkedSetOf<RuleId>()
        var decision = PathPolicyDecision.ALLOW
        val evaluatedPaths = footprint.paths.map { operationPath ->
            val redirect = OrderedRedirectInterpreter.interpret(
                path = operationPath.visiblePath,
                rules = redirectRules,
            )
            val aliasClosure = OrderedRedirectInterpreter.deriveAliasClosure(
                path = operationPath.visiblePath,
                rules = redirectRules,
            )
            matchedRuleIds += redirect.matchedRuleIds

            denyAllRules.forEach { rule ->
                if (aliasClosure.paths.any { alias ->
                        intersectsProtectedRoot(
                            operationPath = alias,
                            protectedRoot = rule.visiblePath,
                            mayAffectDescendants = operationPath.mayAffectDescendants,
                        )
                    }
                ) {
                    matchedRuleIds += rule.ruleId
                    decision = PathPolicyDecision.DENY
                }
            }

            if (footprint.operation.mutatesStorage) {
                readOnlyRules.forEach { rule ->
                    if (aliasClosure.paths.any { alias ->
                            intersectsProtectedRoot(
                                operationPath = alias,
                                protectedRoot = rule.visiblePath,
                                mayAffectDescendants = operationPath.mayAffectDescendants,
                            )
                        }
                    ) {
                        matchedRuleIds += rule.ruleId
                        if (decision != PathPolicyDecision.DENY) {
                            decision = PathPolicyDecision.READ_ONLY
                        }
                    }
                }
            }

            EvaluatedOperationPath(
                originalVisiblePath = operationPath.visiblePath,
                derivedPath = redirect.derivedPath,
                reachableAliases = aliasClosure.paths,
                aliasClosureComplete = aliasClosure.complete,
            )
        }

        return PathPolicyEvaluation(
            decision = decision,
            paths = evaluatedPaths,
            matchedRuleIds = matchedRuleIds.toList(),
        )
    }

    private fun intersectsProtectedRoot(
        operationPath: String,
        protectedRoot: String,
        mayAffectDescendants: Boolean,
    ): Boolean =
        OrderedRedirectInterpreter.startsWithPath(operationPath, protectedRoot) ||
            mayAffectDescendants &&
            OrderedRedirectInterpreter.startsWithPath(protectedRoot, operationPath)
}
