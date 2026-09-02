package me.gm.cleaner.core.config

import me.gm.cleaner.core.storage.redirect.domain.OrderedRedirectPolicy
import me.gm.cleaner.core.storage.redirect.domain.OrderedRedirectRule
import me.gm.cleaner.core.storage.redirect.domain.PackageStorageScope
import me.gm.cleaner.core.storage.redirect.domain.ReadOnlyRule
import me.gm.cleaner.core.storage.redirect.domain.RedirectRuleType
import me.gm.cleaner.core.storage.redirect.domain.RuleId
import me.gm.cleaner.core.storage.redirect.domain.StoragePolicyEnvelope
import me.gm.cleaner.core.storage.redirect.domain.StorageUserScope

/**
 * 用旧编辑 API 的规则正文替换指定包的重定向规则。
 *
 * 这里只负责构造领域正文；路径规范化、校验、revision CAS 和持久化由配置 Store 负责。
 */
fun StoragePolicyEnvelope.replaceRedirectRules(
    rawRules: List<Pair<String, String>>,
    packageNames: List<String>,
): StoragePolicyEnvelope {
    if (rawRules.isEmpty()) {
        return removeRedirectRules(packageNames)
    }
    val uniquePackageNames = packageNames.distinct()
    val retained = redirectPolicies.filterNot { it.scope.packageName in uniquePackageNames }
    val added = uniquePackageNames.map { packageName ->
        OrderedRedirectPolicy(
            scope = PackageStorageScope(packageName, StorageUserScope.AllUsers),
            rules = rawRules.mapIndexed { index, (source, target) ->
                OrderedRedirectRule(
                    ruleId = RuleId("legacy-redirect-$packageName-$index"),
                    type = if (source == target) RedirectRuleType.PRESERVE else RedirectRuleType.MAP,
                    source = source,
                    target = target,
                    orderIndex = index,
                )
            },
        )
    }
    return copy(redirectPolicies = retained + added)
}

/** 删除指定包的全部重定向规则。 */
fun StoragePolicyEnvelope.removeRedirectRules(
    packageNames: List<String>,
): StoragePolicyEnvelope = copy(
    redirectPolicies = redirectPolicies.filterNot { it.scope.packageName in packageNames },
)

/** 用旧编辑 API 的路径正文替换指定包的只读规则。 */
fun StoragePolicyEnvelope.replaceReadOnlyRules(
    rawRules: List<String>,
    packageNames: List<String>,
): StoragePolicyEnvelope {
    val uniquePackageNames = packageNames.distinct()
    val retained = readOnlyRules.filterNot { it.scope.packageName in uniquePackageNames }
    val added = uniquePackageNames.flatMap { packageName ->
        rawRules.mapIndexed { index, path ->
            ReadOnlyRule(
                ruleId = RuleId("legacy-readonly-$packageName-$index"),
                scope = PackageStorageScope(packageName, StorageUserScope.AllUsers),
                visiblePath = path,
            )
        }
    }
    return copy(readOnlyRules = retained + added)
}

/** 删除指定包的全部只读规则。 */
fun StoragePolicyEnvelope.removeReadOnlyRules(
    packageNames: List<String>,
): StoragePolicyEnvelope = copy(
    readOnlyRules = readOnlyRules.filterNot { it.scope.packageName in packageNames },
)
