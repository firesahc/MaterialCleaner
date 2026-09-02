package me.gm.cleaner.runtime.server

import me.gm.cleaner.core.config.ConfigSourceHealth
import me.gm.cleaner.core.config.ConfiguredPolicySnapshot
import me.gm.cleaner.core.config.VersionedReadOnlyPolicy
import me.gm.cleaner.core.config.VersionedRedirectPolicy
import me.gm.cleaner.core.storage.redirect.domain.StoragePolicyEnvelope
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeRedirectPolicyFactoryTest {

    @Test
    fun `投影携带两个独立配置 revision`() {
        val snapshot = RuntimeRedirectPolicyFactory.build(
            ConfiguredPolicySnapshot(
                redirect = VersionedRedirectPolicy(
                    revision = "redirect-revision",
                    envelope = StoragePolicyEnvelope(),
                    health = ConfigSourceHealth.VALID,
                ),
                readOnly = VersionedReadOnlyPolicy(
                    revision = "read-only-revision",
                    envelope = StoragePolicyEnvelope(),
                    health = ConfigSourceHealth.VALID,
                ),
            ),
            userIds = emptyList(),
        )

        assertEquals("redirect-revision", snapshot.redirectRevision)
        assertEquals("read-only-revision", snapshot.readOnlyRevision)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `损坏配置不能生成运行时投影`() {
        RuntimeRedirectPolicyFactory.build(
            ConfiguredPolicySnapshot(
                redirect = VersionedRedirectPolicy(
                    revision = "redirect-revision",
                    envelope = StoragePolicyEnvelope(),
                    health = ConfigSourceHealth.CORRUPT,
                    diagnostics = listOf("invalid json"),
                ),
                readOnly = VersionedReadOnlyPolicy(
                    revision = "read-only-revision",
                    envelope = StoragePolicyEnvelope(),
                    health = ConfigSourceHealth.VALID,
                ),
            ),
            userIds = emptyList(),
        )
    }
}
