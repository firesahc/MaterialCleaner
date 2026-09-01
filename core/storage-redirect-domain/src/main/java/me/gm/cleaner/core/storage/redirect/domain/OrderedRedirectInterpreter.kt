package me.gm.cleaner.core.storage.redirect.domain

/**
 * 一次有序重定向解释中实际命中的步骤。
 *
 * [inputPath] 与 [outputPath] 同时保留，以便访问策略对原始可见路径和所有派生 alias
 * 使用相同的安全判定，而不是只检查最终 backing path。
 */
data class RedirectInterpretationStep(
    val ruleId: RuleId,
    val orderIndex: Int,
    val inputPath: String,
    val outputPath: String,
)

data class RedirectInterpretation(
    val originalPath: String,
    val derivedPath: String,
    val steps: List<RedirectInterpretationStep>,
) {
    val matchedRuleIds: List<RuleId>
        get() = steps.map(RedirectInterpretationStep::ruleId)

    /**
     * 本次有序解释可到达的有限 alias 闭包。
     *
     * 每条规则在一次解释中至多执行一次，因此即使配置构成循环，闭包也一定终止。
     */
    val reachableAliases: List<String>
        get() = buildList {
            add(originalPath)
            steps.forEach { step ->
                if (step.outputPath !in this) {
                    add(step.outputPath)
                }
            }
        }
}

/**
 * 挂载一条规则前，该规则 target 在此前规则下解析到的位置。
 */
data class RedirectMountPoint(
    val ruleId: RuleId,
    val orderIndex: Int,
    val configuredTarget: String,
    val derivedPath: String,
)

data class RedirectAliasClosure(
    val paths: List<String>,
    val complete: Boolean,
)

/**
 * 正式 v4.0.0 MountRules 的类型化解释器。
 *
 * 语义不是“依次从头改写”。它先在原始输入路径上选择最后一个匹配 target 的规则，
 * 再从该索引向列表尾部逐条尝试改写。PRESERVE 参与匹配与顺序，只是改写结果保持不变。
 */
object OrderedRedirectInterpreter {

    fun interpret(
        path: String,
        rules: List<OrderedRedirectRule>,
    ): RedirectInterpretation {
        requireCanonicalAbsolutePath(path, "path")
        validateOrderedRules(rules)

        val lastMatch = rules.indexOfLast { rule ->
            startsWithPath(path, rule.target)
        }
        if (lastMatch == -1) {
            return RedirectInterpretation(path, path, emptyList())
        }

        var derivedPath = path
        val steps = mutableListOf<RedirectInterpretationStep>()
        rules.subList(lastMatch, rules.size).forEach { rule ->
            if (startsWithPath(derivedPath, rule.target)) {
                val inputPath = derivedPath
                derivedPath = rule.source + derivedPath.substring(rule.target.length)
                steps += RedirectInterpretationStep(
                    ruleId = rule.ruleId,
                    orderIndex = rule.orderIndex,
                    inputPath = inputPath,
                    outputPath = derivedPath,
                )
            }
        }
        return RedirectInterpretation(path, derivedPath, steps)
    }

    /**
     * 逐前缀计算 mountPoint：第 i 条规则的 target 只能由 [0, i) 的规则解释。
     */
    fun deriveMountPoints(rules: List<OrderedRedirectRule>): List<RedirectMountPoint> {
        validateOrderedRules(rules)
        return rules.mapIndexed { index, rule ->
            RedirectMountPoint(
                ruleId = rule.ruleId,
                orderIndex = rule.orderIndex,
                configuredTarget = rule.target,
                derivedPath = interpret(rule.target, rules.subList(0, index)).derivedPath,
            )
        }
    }

    /**
     * 枚举能由同一有序规则解释到相同最终 backing 的可见 alias。
     *
     * 反向候选只有重新执行完整解释后仍落到同一 backing 才会进入闭包，避免仅凭 source
     * 字符串相似扩大保护范围。循环或恶意扩张规则触及上限时返回 incomplete，调用方不得
     * 把该结果用于声明访问能力已闭合。
     */
    fun deriveAliasClosure(
        path: String,
        rules: List<OrderedRedirectRule>,
        maxPaths: Int = DEFAULT_MAX_ALIAS_PATHS,
    ): RedirectAliasClosure {
        require(maxPaths > 0) { "alias 闭包上限必须为正数" }
        val interpretation = interpret(path, rules)
        val backing = interpretation.derivedPath
        val aliases = linkedSetOf<String>()
        val queue = ArrayDeque<String>()

        fun add(candidate: String): Boolean {
            if (candidate in aliases) return true
            if (aliases.size >= maxPaths) return false
            aliases += candidate
            queue += candidate
            return true
        }

        if (!add(path)) return RedirectAliasClosure(emptyList(), complete = false)
        interpretation.steps.forEach { step ->
            if (!add(step.outputPath)) {
                return RedirectAliasClosure(aliases.toList(), complete = false)
            }
        }

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (rule in rules) {
                if (!startsWithPath(current, rule.source)) continue
                val candidate = rule.target + current.substring(rule.source.length)
                if (interpret(candidate, rules).derivedPath != backing) continue
                if (!add(candidate)) {
                    return RedirectAliasClosure(aliases.toList(), complete = false)
                }
            }
        }
        return RedirectAliasClosure(aliases.toList(), complete = true)
    }

    internal fun startsWithPath(path: String, prefix: String): Boolean =
        path == prefix || path.startsWith(ensureTrailingSeparator(prefix))

    internal fun requireCanonicalAbsolutePath(path: String, label: String) {
        require(path.startsWith('/')) { "$label 必须是绝对路径" }
        require(path == "/" || !path.endsWith('/')) { "$label 不能包含尾部分隔符" }
        require("//" !in path) { "$label 不能包含空路径段" }
        require(path.split('/').none { it == "." || it == ".." }) {
            "$label 不能包含相对路径段"
        }
        require('\u0000' !in path) { "$label 不能包含 NUL" }
    }

    private fun validateOrderedRules(rules: List<OrderedRedirectRule>) {
        require(rules.map(OrderedRedirectRule::orderIndex) == rules.indices.toList()) {
            "有序重定向规则索引必须从 0 开始连续且与声明顺序一致"
        }
        rules.forEach { rule ->
            requireCanonicalAbsolutePath(rule.source, "rule[${rule.orderIndex}].source")
            requireCanonicalAbsolutePath(rule.target, "rule[${rule.orderIndex}].target")
        }
    }

    private fun ensureTrailingSeparator(path: String): String =
        if (path.endsWith('/')) path else "$path/"

    private const val DEFAULT_MAX_ALIAS_PATHS = 256
}
