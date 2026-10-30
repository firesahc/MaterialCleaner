package me.gm.cleaner.runtime.server.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaProviderHookHealthPolicyTest {

    private val criticalMethods = listOf(
        "openWithFuse",
        "renameForFuse",
        "isDirAccessAllowedForFuse",
        "isDirectoryCreationOrDeletionAllowedForFuse",
        "insertFileIfNecessaryForFuse",
        "deleteFileForFuse",
    )

    private val currentFreshness = HookPolicyFreshnessSummary(
        redirect = PolicyFreshnessState.CURRENT,
        readOnly = PolicyFreshnessState.CURRENT,
        platformCapabilities = PolicyFreshnessState.CURRENT,
        mountPoints = PolicyFreshnessState.CURRENT,
    )

    private fun completeCoverage() = FuseJavaGateCoverageSummary(
        scanComplete = true,
        discoveredCount = criticalMethods.size,
        hookedMethods = criticalMethods.map { "$it [java.lang.String]" },
        applicableCriticalMethods = criticalMethods.toSet(),
    )

    @Test
    fun `扫描未完成时返回RECOVERING`() {
        val state = MediaProviderHookHealthPolicy.evaluate(
            mediaProviderHookConnected = true,
            recoveryInProgress = false,
            coverage = FuseJavaGateCoverageSummary(scanComplete = false),
            freshness = currentFreshness,
        )

        assertEquals(LayerState.RECOVERING, state)
    }

    @Test
    fun `六项关键Hook完整且策略fresh时返回HEALTHY`() {
        val state = MediaProviderHookHealthPolicy.evaluate(
            mediaProviderHookConnected = true,
            recoveryInProgress = false,
            coverage = completeCoverage(),
            freshness = currentFreshness,
        )

        assertEquals(LayerState.HEALTHY, state)
    }

    @Test
    fun `关键Hook跳过或安装失败时降级`() {
        val coverage = completeCoverage().copy(
            hookedMethods = criticalMethods.drop(2).map { "$it [java.lang.String]" },
            uidSkippedMethods = listOf("openWithFuse [java.lang.String] (uid unresolved)"),
            failedMethods = listOf("renameForFuse [java.lang.String] (install failed)"),
        )

        assertTrue(coverage.criticalUncoveredMethods.contains("openWithFuse"))
        assertTrue(coverage.criticalUncoveredMethods.contains("renameForFuse"))
        assertEquals(
            LayerState.DEGRADED,
            MediaProviderHookHealthPolicy.evaluate(
                mediaProviderHookConnected = true,
                recoveryInProgress = false,
                coverage = coverage,
                freshness = currentFreshness,
            ),
        )
    }

    @Test
    fun `普通健康指标展示UID跳过数和关键缺口`() {
        val coverage = completeCoverage().copy(
            hookedMethods = criticalMethods.drop(1).map { "$it [java.lang.String]" },
            uidSkippedMethods = listOf("openWithFuse [java.lang.String] (uid unresolved)"),
        )
        val report = MediaProviderHookLayerReporter.collect(
            generation = 1L,
            now = 1_000L,
            hooksBridgeConnected = true,
            mediaProviderHookConnected = true,
            recovery = RuntimeRecoverySnapshot(),
            coverage = coverage,
            freshness = currentFreshness,
        )

        assertEquals(LayerState.DEGRADED, report.state)
        assertEquals("true", report.metrics[MediaProviderHookLayerReporter.KEY_FUSE_JAVA_GATE_SCAN_COMPLETE])
        assertEquals(
            LayerState.DEGRADED.name,
            report.metrics[MediaProviderHookLayerReporter.KEY_FUSE_JAVA_GATE_COVERAGE_STATE],
        )
        assertEquals("1", report.metrics["fuseJavaGateUidSkippedCount"])
        assertTrue(
            report.metrics[MediaProviderHookLayerReporter.KEY_FUSE_JAVA_GATE_CRITICAL_MISSING]
                .orEmpty().contains("openWithFuse"),
        )
    }

    @Test
    fun `辅助open缺口只计量不降级`() {
        val coverage = completeCoverage().copy(
            unknownMethods = listOf("onFileOpenForFuse [java.lang.String]"),
        )

        assertTrue(coverage.auxiliaryLookupOpenUncoveredMethods.contains("onFileOpenForFuse"))
        assertEquals(
            LayerState.HEALTHY,
            MediaProviderHookHealthPolicy.evaluate(
                mediaProviderHookConnected = true,
                recoveryInProgress = false,
                coverage = coverage,
                freshness = currentFreshness,
            ),
        )
    }

    @Test
    fun `任一策略域freshness异常时不能报HEALTHY`() {
        val staleFreshness = currentFreshness.copy(readOnly = PolicyFreshnessState.STALE)
        val unknownCapabilities = currentFreshness.copy(
            platformCapabilities = PolicyFreshnessState.UNKNOWN,
        )

        assertEquals(
            LayerState.DEGRADED,
            MediaProviderHookHealthPolicy.evaluate(
                mediaProviderHookConnected = true,
                recoveryInProgress = false,
                coverage = completeCoverage(),
                freshness = staleFreshness,
            ),
        )
        assertEquals(
            LayerState.DEGRADED,
            MediaProviderHookHealthPolicy.evaluate(
                mediaProviderHookConnected = true,
                recoveryInProgress = false,
                coverage = completeCoverage(),
                freshness = unknownCapabilities,
            ),
        )
    }

    @Test
    fun `设备未发现的关键方法不判缺口`() {
        // 模拟设备 MediaProvider 类中不存在 isDirectoryCreationOrDeletionAllowedForFuse：
        // 它不在适用关键方法集合中，因此不应被判为覆盖缺口。
        val applicable = criticalMethods
            .filter { it != "isDirectoryCreationOrDeletionAllowedForFuse" }
            .toSet()
        val coverage = completeCoverage().copy(
            hookedMethods = applicable.map { "$it [java.lang.String]" },
            applicableCriticalMethods = applicable,
        )

        assertFalse(
            coverage.applicableCriticalMethods.contains("isDirectoryCreationOrDeletionAllowedForFuse"),
        )
        assertFalse(
            coverage.criticalMissingMethods.contains("isDirectoryCreationOrDeletionAllowedForFuse"),
        )
        assertEquals(
            LayerState.HEALTHY,
            MediaProviderHookHealthPolicy.evaluate(
                mediaProviderHookConnected = true,
                recoveryInProgress = false,
                coverage = coverage,
                freshness = currentFreshness,
            ),
        )
    }
}
