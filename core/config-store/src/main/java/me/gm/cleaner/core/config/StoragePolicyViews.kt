package me.gm.cleaner.core.config

import java.util.regex.Pattern

/**
 * 存储策略读取视图：Store 直读加用户展开。
 *
 * P9 收敛：调用方经此读配置，不再经过含旧 JSON 双轨的门面。
 * 旧格式分享导出等互操作仍走旧适配器，不在此列。
 */
fun ConfiguredPolicyStore.getPackageSrZipped(packageName: String, userId: Int = 0): List<Pair<String, String>> =
    readRedirect().envelope.redirectPolicies
        .firstOrNull { it.scope.packageName == packageName }?.rules.orEmpty()
        .map { expandPathAsUser(it.source, userId) to expandPathAsUser(it.target, userId) }

/** @see getPackageSrZipped */
fun ConfiguredPolicyStore.getPackageSr(packageName: String, userId: Int): Pair<List<String>, List<String>> {
    val rules = readRedirect().envelope.redirectPolicies
        .firstOrNull { it.scope.packageName == packageName }?.rules.orEmpty()
    return rules.map { expandPathAsUser(it.source, userId) } to
        rules.map { expandPathAsUser(it.target, userId) }
}

/** @see getPackageSrZipped */
fun ConfiguredPolicyStore.getPackageSrCount(packageName: String): Int =
    readRedirect().envelope.redirectPolicies
        .firstOrNull { it.scope.packageName == packageName }?.rules?.size ?: 0

/** @see getPackageSrZipped */
val ConfiguredPolicyStore.srPackages: Set<String>
    get() = readRedirect().envelope.redirectPolicies
        .map { it.scope.packageName }.toSet()

/** @see getPackageSrZipped */
val ConfiguredPolicyStore.srRulesCount: Int
    get() = readRedirect().envelope.redirectPolicies.sumOf { it.rules.size }

/** @see getPackageSrZipped */
fun ConfiguredPolicyStore.getUninstalledSrPackages(installedPackages: Set<String>): List<String> {
    val packages = readRedirect().envelope.redirectPolicies
        .map { it.scope.packageName }.toSet()
    return (packages - installedPackages).toList()
}

/** @see getPackageSrZipped */
fun ConfiguredPolicyStore.getPackageReadOnly(packageName: String, userId: Int = 0): List<String> =
    readReadOnly().envelope.readOnlyRules
        .filter { it.scope.packageName == packageName }
        .map { expandPathAsUser(it.visiblePath, userId) }

/** @see getPackageSrZipped */
fun ConfiguredPolicyStore.getUninstalledReadOnlyPackages(installedPackages: Set<String>): List<String> {
    val packages = readReadOnly().envelope.readOnlyRules
        .map { it.scope.packageName }.toSet()
    return (packages - installedPackages).toList()
}

private val VIEW_APP_DATA_DIR_PATHS: Pattern by lazy {
    Pattern.compile("(?i)(^/[^/]+/[^/]+/)([0-9]+)(/)?([^/]+)?(/.*)?")
}

private fun expandPathAsUser(path: String, userId: Int): String {
    if (userId == 0) {
        return path
    }
    val matcher = VIEW_APP_DATA_DIR_PATHS.matcher(path)
    if (!matcher.matches()) {
        return path
    }
    val builder = StringBuilder()
    for (i in 1..matcher.groupCount()) {
        val group = matcher.group(i) ?: continue
        if (group.all { it.isDigit() }) {
            builder.append(userId)
        } else {
            builder.append(group)
        }
    }
    return builder.toString()
}
