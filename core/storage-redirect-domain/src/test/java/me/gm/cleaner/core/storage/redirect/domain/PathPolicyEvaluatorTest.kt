package me.gm.cleaner.core.storage.redirect.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            denyAllRules = emptyList(),
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
            denyAllRules = emptyList(),
        )

        assertEquals(PathPolicyDecision.READ_ONLY, result.decision)
        assertEquals(
            listOf("/backing/private/file", "/visible/public/file"),
            result.paths.single().reachableAliases,
        )
        assertTrue(result.aliasClosureComplete)
    }

    @Test
    fun `读取不受只读限制但DENY优先于只读`() {
        val read = PathPolicyEvaluator.evaluate(
            footprint = OperationFootprint.single(PathOperation.READ_CONTENT, "/protected/file"),
            redirectRules = emptyList(),
            readOnlyRules = listOf(readOnly("ro", "/protected")),
            denyAllRules = emptyList(),
        )
        val write = PathPolicyEvaluator.evaluate(
            footprint = OperationFootprint.single(PathOperation.WRITE_CONTENT, "/protected/file"),
            redirectRules = emptyList(),
            readOnlyRules = listOf(readOnly("ro", "/protected")),
            denyAllRules = listOf(deny("deny", "/protected")),
        )

        assertEquals(PathPolicyDecision.ALLOW, read.decision)
        assertEquals(PathPolicyDecision.DENY, write.decision)
        assertTrue(RuleId("ro") in write.matchedRuleIds)
        assertTrue(RuleId("deny") in write.matchedRuleIds)
    }

    @Test
    fun `父目录普通读取保留名称可见但影响后代的操作被拒绝`() {
        val lookupParent = PathPolicyEvaluator.evaluate(
            footprint = OperationFootprint.single(PathOperation.LOOKUP, "/visible"),
            redirectRules = emptyList(),
            readOnlyRules = emptyList(),
            denyAllRules = listOf(deny("deny", "/visible/secret")),
        )
        val deleteParent = PathPolicyEvaluator.evaluate(
            footprint = OperationFootprint.single(PathOperation.DELETE, "/visible"),
            redirectRules = emptyList(),
            readOnlyRules = emptyList(),
            denyAllRules = listOf(deny("deny", "/visible/secret")),
        )

        assertEquals(PathPolicyDecision.ALLOW, lookupParent.decision)
        assertFalse(RuleId("deny") in lookupParent.matchedRuleIds)
        assertEquals(PathPolicyDecision.DENY, deleteParent.decision)
    }

    @Test
    fun `rename双端任一端与保护根祖先相交都采用最严格结果`() {
        val result = PathPolicyEvaluator.evaluate(
            footprint = OperationFootprint.rename(
                source = "/ordinary/source",
                destination = "/visible",
            ),
            redirectRules = emptyList(),
            readOnlyRules = listOf(readOnly("ro", "/ordinary/source")),
            denyAllRules = listOf(deny("deny", "/visible/secret")),
        )

        assertEquals(PathPolicyDecision.DENY, result.decision)
        assertEquals(
            listOf("/ordinary/source", "/visible"),
            result.paths.map(EvaluatedOperationPath::derivedPath),
        )
        assertTrue(RuleId("ro") in result.matchedRuleIds)
        assertTrue(RuleId("deny") in result.matchedRuleIds)
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
            denyAllRules = listOf(deny("deny", "/visible/private")),
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

    private fun deny(id: String, path: String): DenyAllRule =
        DenyAllRule(RuleId(id), scope, path)
}
