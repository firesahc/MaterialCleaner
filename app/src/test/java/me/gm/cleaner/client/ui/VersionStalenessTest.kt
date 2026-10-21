package me.gm.cleaner.client.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionStalenessTest {

    @Test
    fun `四端一致为OK`() {
        assertEquals(
            VersionStaleness.OK,
            evaluateVersionStaleness(310, 310, 310, 310, true),
        )
    }

    @Test
    fun `App进程旧判STALE_APP`() {
        assertEquals(
            VersionStaleness.STALE_APP,
            evaluateVersionStaleness(309, 310, 310, 310, true),
        )
    }

    @Test
    fun `服务旧判STALE_SERVER`() {
        assertEquals(
            VersionStaleness.OK,
            evaluateVersionStaleness(310, 310, 310, 0, false),
        )
        assertEquals(
            VersionStaleness.STALE_SERVER,
            evaluateVersionStaleness(310, 310, 309, 310, true),
        )
    }

    @Test
    fun `Hook旧判STALE_HOOK`() {
        assertEquals(
            VersionStaleness.STALE_HOOK,
            evaluateVersionStaleness(310, 310, 310, 309, true),
        )
    }

    @Test
    fun `无Hook环境不判Hook`() {
        assertEquals(
            VersionStaleness.OK,
            evaluateVersionStaleness(310, 310, 310, 0, false),
        )
    }

    @Test
    fun `未知版本永不判stale`() {
        assertEquals(
            VersionStaleness.OK,
            evaluateVersionStaleness(310, 0, 309, 308, true),
        )
        assertEquals(
            VersionStaleness.OK,
            evaluateVersionStaleness(0, 310, 0, 0, true),
        )
    }

    @Test
    fun `多端过期按App优先`() {
        assertEquals(
            VersionStaleness.STALE_APP,
            evaluateVersionStaleness(309, 310, 309, 309, true),
        )
    }

    // ---------- 版本过期通知判据（状态驱动） ----------

    private fun fingerprint(
        app: Long = 412,
        installed: Long = 412,
        server: Long = 412,
        hook: Long = 411,
    ) = VersionFingerprint(
        appRunning = app,
        installed = installed,
        serverRunning = server,
        hookRunning = hook,
    )

    @Test
    fun `首次发现版本过期应提示`() {
        assertTrue(shouldNotifyStale(null, fingerprint()))
    }

    @Test
    fun `同一版本组合重复检测不重复提示`() {
        val current = fingerprint()
        assertFalse(shouldNotifyStale(current, current))
        assertFalse(shouldNotifyStale(current, fingerprint()))
    }

    @Test
    fun `版本组合变化视为新的不一致应再提示`() {
        // stale 期间又更新了 APK：installed 变化 -> 新的不一致
        assertTrue(shouldNotifyStale(fingerprint(), fingerprint(installed = 413, hook = 412)))
    }

    @Test
    fun `server不可达取不到版本时不提示`() {
        assertFalse(shouldNotifyStale(null, null))
        assertFalse(shouldNotifyStale(fingerprint(), null))
    }
}
