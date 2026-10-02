package me.gm.cleaner.core.storage.redirect.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PathPolicyEvaluatorTest {
    private val scope = PackageStorageScope(
        packageName = "com.example",
        users = StorageUserScope.AllUsers,
    )

    @Test
    fun `写入在原始路径与派生alias上共同匹配只读规则`() {
        val redirect = listOf(redirect(0, "/backing/private", "/visible/public"))
        val result = PathPolicyEvaluator.evaluate(
            footprint = OperationFootprint.single(
                PathOperation.WRITE_CONTENT,
                "/visible/public/file",
            ),
            redirectRules = redirect,
            readOnlyRules = listOf(readOnly("ro", "/backing/private")),
        )

        assertEquals(PathPolicyDecision.READ_ONLY, result.decision)
        assertEquals("/backing/private/file", result.derivedPath)
        assertEquals(
            listOf("/visible/public/file", "/backing/private/file"),
            result.paths.single().reachableAliases,
        )
        assertEquals(listOf(RuleId("redirect-0"), RuleId("ro")), result.matchedRuleIds)
        assertTrue(result.aliasClosureComplete)
    }

    @Test
    fun `直接访问backing仍会匹配映射后的可见路径策略`() {
        val redirect = listOf(redirect(0, "/backing/private", "/visible/public"))

        val result = PathPolicyEvaluator.evaluate(
            footprint = OperationFootprint.single(
                PathOperation.WRITE_CONTENT,
                "/backing/private/file",
            ),
            redirectRules = redirect,
            readOnlyRules = listOf(readOnly("ro", "/visible/public")),
        )

        assertEquals(PathPolicyDecision.READ_ONLY, result.decision)
        assertEquals(
            listOf("/backing/private/file", "/visible/public/file"),
            result.paths.single().reachableAliases,
        )
        assertTrue(result.aliasClosureComplete)
    }

    @Test
    fun `读取不受只读限制但写入命中只读规则`() {
        val read = PathPolicyEvaluator.evaluate(
            footprint = OperationFootprint.single(PathOperation.READ_CONTENT, "/protected/file"),
            redirectRules = emptyList(),
            readOnlyRules = listOf(readOnly("ro", "/protected")),
        )
        val write = PathPolicyEvaluator.evaluate(
            footprint = OperationFootprint.single(PathOperation.WRITE_CONTENT, "/protected/file"),
            redirectRules = emptyList(),
            readOnlyRules = listOf(readOnly("ro", "/protected")),
        )

        assertEquals(PathPolicyDecision.ALLOW, read.decision)
        assertEquals(PathPolicyDecision.READ_ONLY, write.decision)
        assertTrue(RuleId("ro") in write.matchedRuleIds)
    }

    @Test
    fun `rename双端任一端命中只读采取严格结果`() {
        val result = PathPolicyEvaluator.evaluate(
            footprint = OperationFootprint.rename(
                source = "/ordinary/source",
                destination = "/visible",
            ),
            redirectRules = emptyList(),
            readOnlyRules = listOf(readOnly("ro", "/ordinary/source")),
        )

        assertEquals(PathPolicyDecision.READ_ONLY, result.decision)
        assertEquals(
            listOf("/ordinary/source", "/visible"),
            result.paths.map(EvaluatedOperationPath::derivedPath),
        )
        assertTrue(RuleId("ro") in result.matchedRuleIds)
    }

    @Test
    fun `路径段边界避免误伤同名前缀`() {
        val result = PathPolicyEvaluator.evaluate(
            footprint = OperationFootprint.single(
                PathOperation.WRITE_CONTENT,
                "/visible/private-copy/file",
            ),
            redirectRules = emptyList(),
            readOnlyRules = listOf(readOnly("ro", "/visible/private")),
        )

        assertEquals(PathPolicyDecision.ALLOW, result.decision)
        assertEquals(emptyList<RuleId>(), result.matchedRuleIds)
    }

    private fun redirect(
        index: Int,
        source: String,
        target: String,
    ): OrderedRedirectRule = OrderedRedirectRule(
        ruleId = RuleId("redirect-$index"),
        type = RedirectRuleType.MAP,
        source = source,
        target = target,
        orderIndex = index,
    )

    private fun readOnly(id: String, path: String): ReadOnlyRule =
        ReadOnlyRule(RuleId(id), scope, path)
}
