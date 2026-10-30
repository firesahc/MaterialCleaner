package me.gm.cleaner.client.ui.storageredirect

import me.gm.cleaner.core.storage.redirect.domain.MountPlanDeriver
import me.gm.cleaner.core.storage.redirect.domain.OrderedRedirectInterpreter
import me.gm.cleaner.core.storage.redirect.domain.RedirectRule

/**
 * 向导/界面可达性分析：无意义规则标灰与可达路径预览。
 *
 * P8 外迁：原 `MountRules.meaninglessRulesIndices/getAccessiblePlaces` 的逐字
 * 搬运，语义不变。运行时挂载/Hook/推导已改走规范解释器，不再经过这里；
 * 本对象仅服务界面展示，展示分析复用领域解释器。
 */
object RedirectReachabilityAnalyzer {

    fun mountedPath(rules: List<Pair<String, String>>, path: String): String {
        val domainRules = rules.map { (source, target) -> RedirectRule(source, target) }
        return MountPlanDeriver.resolveMountedPath(domainRules, path)
    }

    /**
     * 判定哪些规则对运行时路径解释无实质贡献，因而可在界面上标灰。
     *
     * 判定口径与运行时规范解释器 [OrderedRedirectInterpreter] 对齐：对每条规则，构造有限探针路径集
     *（所有规则的 target 本身 + 每个 target 的直接子路径），若移除该规则后，探针集上每条路径的
     * derivedPath 均不变，则说明该规则对运行时无影响，标记为冗余。
     *
     * PRESERVE 规则（source == target）虽然不改变匹配路径本身，但它会占据顺序位置并参与
     * indexOfLast 前缀匹配，可能截断改写链、阻止前面规则对子路径的重定向。因此不应一概而论
     * 标灰，而应与其他规则一样经过探针必要性判定。
     */
    fun redundantIndices(rules: List<Pair<String, String>>): List<Int> {
        val orderedRules = MountPlanDeriver.toOrderedRules(rules)
        // 探针路径集：所有 target 本身 + 每个 target 的直接子路径。
        // OrderedRedirectInterpreter 首步即按 target 前缀匹配，此集合足以探测规则是否被命中并产生改写。
        val probePaths = buildList {
            rules.forEach { (_, t) ->
                add(t)
                add("$t/_probe")
            }
        }
        val indices = mutableListOf<Int>()
        for (i in rules.indices) {
            val rulesWithoutI = rules.filterIndexed { idx, _ -> idx != i }
            val orderedWithoutI = MountPlanDeriver.toOrderedRules(rulesWithoutI)
            var redundant = true
            for (probe in probePaths) {
                // OrderedRedirectInterpreter 要求规范绝对路径，非规范探针跳过
                if (!OrderedRedirectInterpreter.isCanonicalAbsolutePath(probe)) continue
                val withPath = OrderedRedirectInterpreter.interpret(probe, orderedRules).derivedPath
                val withoutPath = OrderedRedirectInterpreter.interpret(probe, orderedWithoutI).derivedPath
                if (withPath != withoutPath) {
                    redundant = false
                    break
                }
            }
            if (redundant) {
                indices += i
            }
        }
        return indices
    }

    fun accessiblePlaces(rules: List<Pair<String, String>>, path: String): List<String> {
        val effective = rules.toMutableList().apply {
            redundantIndices(rules).asReversed().forEach { index ->
                removeAt(index)
            }
        }
        val paths = mutableListOf<String>()
        if (effective.unzip().second.none { startsWithPath(it, path) }) {
            paths += path
        }
        for (i in effective.indices) {
            val (source, target) = effective[i]
            if (startsWithPath(source, path)) {
                val maybeAccessiblePath = target + path.substring(source.length)
                if (maybeAccessiblePath ==
                    mountedPath(effective.subList(i + 1, effective.size), maybeAccessiblePath)
                ) {
                    if (maybeAccessiblePath !in paths) {
                        paths += maybeAccessiblePath
                    }
                }
            }
        }
        return paths
    }

    private fun startsWithPath(path: String, prefix: String): Boolean =
        path == prefix || path.startsWith(ensureTrailingSeparator(prefix))

    private fun ensureTrailingSeparator(path: String): String =
        if (path.endsWith('/')) path else "$path/"
}
