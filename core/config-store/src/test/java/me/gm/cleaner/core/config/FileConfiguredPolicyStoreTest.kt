package me.gm.cleaner.core.config

import java.io.File
import java.nio.file.Files
import me.gm.cleaner.core.storage.redirect.domain.RedirectRuleType
import me.gm.cleaner.core.storage.redirect.domain.OrderedRedirectPolicy
import me.gm.cleaner.core.storage.redirect.domain.OrderedRedirectRule
import me.gm.cleaner.core.storage.redirect.domain.PackageStorageScope
import me.gm.cleaner.core.storage.redirect.domain.ReadOnlyRule
import me.gm.cleaner.core.storage.redirect.domain.RuleId
import me.gm.cleaner.core.storage.redirect.domain.StoragePolicyEnvelope
import me.gm.cleaner.core.storage.redirect.domain.StorageUserScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileConfiguredPolicyStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun missingSourcesAreReportedWithoutInventingRules() {
        val store = FileConfiguredPolicyStore(temporaryFolder.root)

        val redirect = store.readRedirect()
        val readOnly = store.readReadOnly()

        assertEquals(ConfigSourceHealth.MISSING, redirect.health)
        assertEquals(ConfigSourceHealth.MISSING, readOnly.health)
        assertTrue(redirect.envelope.redirectPolicies.isEmpty())
        assertTrue(readOnly.envelope.readOnlyRules.isEmpty())
        assertNotEquals(redirect.revision, readOnly.revision)
    }

    @Test
    fun snapshotsStartWithTheCompleteDiskState() {
        temporaryFolder.newFile("storage_redirect").writeText(
            "{\"pkg\":[[\"/source\",\"/target\"]]}",
            Charsets.UTF_8,
        )
        temporaryFolder.newFile("read_only").writeText(
            "{\"pkg\":[\"/protected\"]}",
            Charsets.UTF_8,
        )
        val store = FileConfiguredPolicyStore(temporaryFolder.root)

        assertEquals(store.readSnapshot(), store.snapshots.value)
        assertEquals(ConfigSourceHealth.VALID, store.snapshots.value.redirect.health)
        assertEquals(ConfigSourceHealth.VALID, store.snapshots.value.readOnly.health)
    }

    @Test
    fun unchangedMutationDoesNotWriteOrPublish() {
        val file = temporaryFolder.newFile("storage_redirect")
        val original = "{\"pkg\":[[\"/source\",\"/target\"]]}"
        file.writeText(original, Charsets.UTF_8)
        val store = FileConfiguredPolicyStore(temporaryFolder.root)
        val before = store.snapshots.value

        val result = store.updateRedirect(null) { it }

        assertTrue(result.success)
        assertFalse(result.changed)
        assertEquals(before, store.snapshots.value)
        assertEquals(original, file.readText(Charsets.UTF_8))
    }

    @Test
    fun emptyRedirectReplacementRemovesThePackagePolicy() {
        val envelope = StoragePolicyEnvelope(
            redirectPolicies = listOf(
                OrderedRedirectPolicy(
                    scope = PackageStorageScope("pkg", StorageUserScope.AllUsers),
                    rules = listOf(
                        OrderedRedirectRule(
                            ruleId = RuleId("rule"),
                            type = RedirectRuleType.MAP,
                            source = "/source",
                            target = "/target",
                            orderIndex = 0,
                        ),
                    ),
                ),
            ),
        )

        val updated = envelope.replaceRedirectRules(emptyList(), listOf("pkg"))

        assertTrue(updated.redirectPolicies.isEmpty())
    }

    @Test
    fun redirectConversionPreservesOrderDuplicatesAndIdentityRules() {
        temporaryFolder.newFile("storage_redirect").writeText(
            "{\"com.example\":[[\"/a\",\"/b\"],[\"/a\",\"/b\"],[\"/same\",\"/same\"]]}",
            Charsets.UTF_8,
        )
        val first = FileConfiguredPolicyStore(temporaryFolder.root).readRedirect()
        val second = FileConfiguredPolicyStore(temporaryFolder.root).readRedirect()

        assertEquals(ConfigSourceHealth.VALID, first.health)
        val rules = first.envelope.redirectPolicies.single().rules
        assertEquals(3, rules.size)
        assertEquals(listOf(0, 1, 2), rules.map { it.orderIndex })
        assertEquals(listOf("/a", "/a", "/same"), rules.map { it.source })
        assertEquals(RedirectRuleType.PRESERVE, rules[2].type)
        assertNotEquals(rules[0].ruleId, rules[1].ruleId)
        assertEquals(first.revision, second.revision)
        assertEquals(rules.map { it.ruleId }, second.envelope.redirectPolicies.single().rules.map { it.ruleId })
    }

    @Test
    fun readOnlyConversionUsesAllUsersAndStableRevision() {
        temporaryFolder.newFile("read_only").writeText(
            "{\"com.example\":[\"/protected\",\"/other\"]}",
            Charsets.UTF_8,
        )
        val store = FileConfiguredPolicyStore(temporaryFolder.root)
        val policy = store.readReadOnly()

        assertEquals(ConfigSourceHealth.VALID, policy.health)
        assertTrue(policy.envelope.readOnlyRules.all { it.scope.users is StorageUserScope.AllUsers })
        assertEquals(listOf("/protected", "/other"), policy.envelope.readOnlyRules.map { it.visiblePath })
        assertEquals(policy.revision, store.readReadOnly().revision)
    }

    @Test
    fun readOnlyOrderIsPartOfRevision() {
        val file = temporaryFolder.newFile("read_only")
        file.writeText("{\"com.example\":[\"/first\",\"/second\"]}", Charsets.UTF_8)
        val store = FileConfiguredPolicyStore(temporaryFolder.root)
        val firstRevision = store.readReadOnly().revision

        file.writeText("{\"com.example\":[\"/second\",\"/first\"]}", Charsets.UTF_8)
        val secondRevision = store.readReadOnly().revision

        assertNotEquals(firstRevision, secondRevision)
        assertEquals(
            listOf("/second", "/first"),
            store.readReadOnly().envelope.readOnlyRules.map { it.visiblePath },
        )
    }

    @Test
    fun legacyPathsAreNormalizedAtConversionBoundary() {
        temporaryFolder.newFile("storage_redirect").writeText(
            "{\"pkg\":[[\"/storage/emulated/0/./source/\",\"/storage/emulated/0/target/../target\"]]}",
            Charsets.UTF_8,
        )
        temporaryFolder.newFile("read_only").writeText(
            "{\"pkg\":[\"/storage/emulated/0/./protected/\"]}",
            Charsets.UTF_8,
        )
        val store = FileConfiguredPolicyStore(temporaryFolder.root)

        val redirectRule = store.readRedirect().envelope.redirectPolicies.single().rules.single()
        val readOnlyRule = store.readReadOnly().envelope.readOnlyRules.single()
        assertEquals("/storage/emulated/0/source", redirectRule.source)
        assertEquals("/storage/emulated/0/target", redirectRule.target)
        assertEquals("/storage/emulated/0/protected", readOnlyRule.visiblePath)

        temporaryFolder.root.resolve("storage_redirect").writeText(
            "{\"pkg\":[[\"/storage/emulated/0/same/\",\"/storage/emulated/0/same\"]]}",
            Charsets.UTF_8,
        )
        assertEquals(
            RedirectRuleType.PRESERVE,
            store.readRedirect().envelope.redirectPolicies.single().rules.single().type,
        )
    }

    @Test
    fun legacyFixturesConvertWithoutChangingRuleCardinalityOrOrder() {
        temporaryFolder.newFile("storage_redirect").writeText(
            javaClass.getResource("/legacy/storage_redirect.json")!!.readText(),
            Charsets.UTF_8,
        )
        temporaryFolder.newFile("read_only").writeText(
            javaClass.getResource("/legacy/read_only.json")!!.readText(),
            Charsets.UTF_8,
        )
        val store = FileConfiguredPolicyStore(temporaryFolder.root)

        val redirect = store.readRedirect().envelope.redirectPolicies
        assertEquals(listOf("com.example.camera", "com.example.viewer"),
            redirect.map { it.scope.packageName })
        assertEquals(listOf("/storage/emulated/0/DCIM", "/storage/emulated/0/keep"),
            redirect.first().rules.map { it.source })
        assertEquals(RedirectRuleType.PRESERVE, redirect.first().rules[1].type)

        val readOnly = store.readReadOnly().envelope.readOnlyRules
        assertEquals(
            listOf(
                "/storage/emulated/0/DCIM/Private",
                "/storage/emulated/0/DCIM/Private",
                "/storage/emulated/0/Movies/Archive",
            ),
            readOnly.map { it.visiblePath },
        )
    }

    @Test
    fun malformedJsonAndInvalidPathAreCorrupt() {
        temporaryFolder.newFile("storage_redirect").writeText("not-json", Charsets.UTF_8)
        val malformed = FileConfiguredPolicyStore(temporaryFolder.root).readRedirect()
        assertEquals(ConfigSourceHealth.CORRUPT, malformed.health)
        assertTrue(malformed.diagnostics.isNotEmpty())

        temporaryFolder.newFile("read_only").writeText("{\"pkg\":[\"relative\"]}", Charsets.UTF_8)
        val invalidPath = FileConfiguredPolicyStore(temporaryFolder.root).readReadOnly()
        assertEquals(ConfigSourceHealth.CORRUPT, invalidPath.health)
        assertTrue(invalidPath.diagnostics.any { it.contains("绝对路径") })
    }

    @Test
    fun invalidEntriesKeepOtherRulesAndExposeIndexedDiagnostics() {
        temporaryFolder.newFile("storage_redirect").writeText(
            "{\"pkg\":[[\"/a\",\"/b\"],[\"relative\",\"/target\"],[\"/c\",\"/d\"]]}",
            Charsets.UTF_8,
        )
        temporaryFolder.newFile("read_only").writeText(
            "{\"pkg\":[\"/protected\",\"relative\",\"/other\"]}",
            Charsets.UTF_8,
        )
        val store = FileConfiguredPolicyStore(temporaryFolder.root)

        val redirect = store.readRedirect()
        assertEquals(listOf("/a", "/c"), redirect.envelope.redirectPolicies.single().rules.map { it.source })
        assertTrue(redirect.diagnostics.any { it.contains("redirect[pkg][1]") })
        assertEquals(listOf("/protected", "/other"),
            store.readReadOnly().envelope.readOnlyRules.map { it.visiblePath })
        assertTrue(store.readReadOnly().diagnostics.any { it.contains("readOnly[pkg][1]") })
    }

    @Test
    fun corruptSourceBlocksUpdateAndKeepsOriginalBytes() {
        val file = temporaryFolder.newFile("storage_redirect")
        val original = "{not-json"
        file.writeText(original, Charsets.UTF_8)
        val store = FileConfiguredPolicyStore(temporaryFolder.root)

        val result = store.updateRedirect(null) { current -> current }

        assertFalse(result.success)
        assertEquals(PolicyStoreFailureKind.CORRUPT_SOURCE, result.failureKind)
        assertEquals(ConfigSourceHealth.CORRUPT, store.snapshots.value.redirect.health)
        assertEquals(original, file.readText(Charsets.UTF_8))
        assertTrue(result.error.orEmpty().contains("损坏"))
    }

    @Test
    fun contentChangeChangesRevisionAndCasRejectsStaleRevision() {
        val file = temporaryFolder.newFile("storage_redirect")
        file.writeText("{\"pkg\":[[\"/a\",\"/b\"]]}", Charsets.UTF_8)
        val store = FileConfiguredPolicyStore(temporaryFolder.root)
        val before = store.readRedirect()
        val result = store.updateRedirect("stale") { current -> current }
        assertFalse(result.success)
        assertEquals(PolicyStoreFailureKind.REVISION_CONFLICT, result.failureKind)

        file.writeText("{\"pkg\":[[\"/a\",\"/c\"]]}", Charsets.UTF_8)
        assertNotEquals(before.revision, store.readRedirect().revision)
    }

    @Test
    fun updateWritesCompatibleLegacyJsonAndReturnsNewRevision() {
        val store = FileConfiguredPolicyStore(temporaryFolder.root)
        val result = store.updateRedirect(null) {
            StoragePolicyEnvelope(
                redirectPolicies = listOf(
                    OrderedRedirectPolicy(
                        scope = PackageStorageScope("pkg", StorageUserScope.AllUsers),
                        rules = listOf(
                            OrderedRedirectRule(
                                ruleId = RuleId("rule"),
                                type = RedirectRuleType.MAP,
                                source = "/source",
                                target = "/target",
                                orderIndex = 0,
                            ),
                        ),
                    ),
                ),
            )
        }

        assertTrue(result.success)
        assertEquals(result.revision, store.readRedirect().revision)
        assertEquals("{\"pkg\":[[\"/source\",\"/target\"]]}",
            temporaryFolder.root.resolve("storage_redirect").readText(Charsets.UTF_8))
    }

    @Test
    fun redirectAndReadOnlyRevisionsAdvanceIndependently() {
        val store = FileConfiguredPolicyStore(temporaryFolder.root)
        val initial = store.readSnapshot()

        val redirectResult = store.updateRedirect(null) {
            StoragePolicyEnvelope(
                redirectPolicies = listOf(
                    OrderedRedirectPolicy(
                        scope = PackageStorageScope("pkg", StorageUserScope.AllUsers),
                        rules = listOf(
                            OrderedRedirectRule(
                                ruleId = RuleId("redirect"),
                                type = RedirectRuleType.MAP,
                                source = "/source",
                                target = "/target",
                                orderIndex = 0,
                            ),
                        ),
                    ),
                ),
            )
        }
        assertTrue(redirectResult.success)
        val afterRedirect = store.readSnapshot()
        assertEquals(afterRedirect, store.snapshots.value)
        assertNotEquals(initial.redirect.revision, afterRedirect.redirect.revision)
        assertEquals(initial.readOnly.revision, afterRedirect.readOnly.revision)

        val readOnlyResult = store.updateReadOnly(null) {
            StoragePolicyEnvelope(
                readOnlyRules = listOf(
                    ReadOnlyRule(
                        ruleId = RuleId("read-only"),
                        scope = PackageStorageScope("pkg", StorageUserScope.AllUsers),
                        visiblePath = "/protected",
                    ),
                ),
            )
        }
        assertTrue(readOnlyResult.success)
        val afterReadOnly = store.readSnapshot()
        assertEquals(afterReadOnly, store.snapshots.value)
        assertEquals(afterRedirect.redirect.revision, afterReadOnly.redirect.revision)
        assertNotEquals(afterRedirect.readOnly.revision, afterReadOnly.readOnly.revision)
    }

    @Test
    fun updateAtomicallyReplacesExistingLegacyFile() {
        val file = temporaryFolder.newFile("storage_redirect")
        file.writeText("{\"old\":[[\"/a\",\"/b\"]]}", Charsets.UTF_8)
        val store = FileConfiguredPolicyStore(temporaryFolder.root)

        val result = store.updateRedirect(null) { current ->
            current.copy(redirectPolicies = emptyList())
        }

        assertTrue(result.success)
        assertEquals("{}", file.readText(Charsets.UTF_8))
        assertEquals(ConfigSourceHealth.VALID, store.readRedirect().health)
    }

    @Test
    fun updateRejectsUnsupportedEnvelopeFieldsWithoutOverwritingSource() {
        val file = temporaryFolder.newFile("storage_redirect")
        file.writeText("{\"pkg\":[[\"/a\",\"/b\"]]}", Charsets.UTF_8)
        val store = FileConfiguredPolicyStore(temporaryFolder.root)
        val before = file.readText(Charsets.UTF_8)

        val result = store.updateRedirect(null) { current ->
            current.copy(
                readOnlyRules = listOf(
                    ReadOnlyRule(
                        ruleId = RuleId("read-only"),
                        scope = PackageStorageScope("pkg", StorageUserScope.AllUsers),
                        visiblePath = "/read-only",
                    ),
                ),
            )
        }

        assertFalse(result.success)
        assertEquals(PolicyStoreFailureKind.INVALID_MUTATION, result.failureKind)
        assertEquals(before, file.readText(Charsets.UTF_8))
    }

    @Test
    fun atomicWriteFailureReturnsFailureWithoutThrowing() {
        val base = temporaryFolder.newFile("base-file")
        val store = FileConfiguredPolicyStore(base)

        val result = store.updateRedirect(null) {
            StoragePolicyEnvelope(
                redirectPolicies = listOf(
                    OrderedRedirectPolicy(
                        scope = PackageStorageScope("pkg", StorageUserScope.AllUsers),
                        rules = listOf(
                            OrderedRedirectRule(
                                ruleId = RuleId("rule"),
                                type = RedirectRuleType.MAP,
                                source = "/source",
                                target = "/target",
                                orderIndex = 0,
                            ),
                        ),
                    ),
                ),
            )
        }

        assertFalse(result.success)
        assertEquals(PolicyStoreFailureKind.IO_FAILURE, result.failureKind)
        assertTrue(result.error.orEmpty().isNotBlank())
        assertTrue(Files.exists(base.toPath()))
    }
}
