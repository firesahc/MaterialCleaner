package me.gm.cleaner.client.ui.storageredirect

/**
 * 向导/界面可达性分析：无意义规则标灰与可达路径预览。
 *
 * P8 外迁：原 `MountRules.meaninglessRulesIndices/getAccessiblePlaces` 的逐字
 * 搬运，语义不变。运行时挂载/Hook/推导已改走规范解释器，不再经过这里；
 * 本对象仅服务界面展示，可独立于领域层演进。
 */
object RedirectReachabilityAnalyzer {

    fun mountedPath(rules: List<Pair<String, String>>, path: String): String {
        val lastMatch = rules.indexOfLast { (_, target) ->
            startsWithPath(path, target)
        }
        if (lastMatch == -1) {
            return path
        }
        var mountedPath = path
        rules.subList(lastMatch, rules.size).forEach { (source, target) ->
            if (startsWithPath(mountedPath, target)) {
                mountedPath = source + mountedPath.substring(target.length)
            }
        }
        return mountedPath
    }

    fun redundantIndices(rules: List<Pair<String, String>>): List<Int> {
        val targets = rules.map { it.second }
        val indices = mutableListOf<Int>()
        for (i in targets.indices) {
            val target = targets[i]
            if (targets.subList(i + 1, targets.size).any { startsWithPath(it, target) } ||
                mountedPath(rules.subList(0, i), target) ==
                mountedPath(rules.subList(0, i + 1), target)
            ) {
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
