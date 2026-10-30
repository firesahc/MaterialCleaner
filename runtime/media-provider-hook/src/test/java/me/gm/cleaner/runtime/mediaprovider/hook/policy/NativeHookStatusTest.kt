package me.gm.cleaner.runtime.mediaprovider.hook.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeHookStatusTest {

    private val allCritical = listOf(
        "openWithFuse",
        "renameForFuse",
        "isDirAccessAllowedForFuse",
        "isDirectoryCreationOrDeletionAllowedForFuse",
        "insertFileIfNecessaryForFuse",
        "deleteFileForFuse",
    )

    private fun hooked(methods: List<String>) = methods.map { "$it [java.lang.String]" }

    @Test
    fun `设备未发现的候选方法不计入适用集合`() {
        // 设备上只有 5 个关键方法被发现（hooked），isDirectoryCreationOrDeletionAllowedForFuse
        // 根本不存在于类中，不应出现在 applicableCriticalMethods 中。
        val discovered = allCritical.filter { it != "isDirectoryCreationOrDeletionAllowedForFuse" }
        val result = applicableCriticalMethods(
            hookedMethods = hooked(discovered),
            unknownMethods = emptyList(),
            uidSkippedMethods = emptyList(),
            failedMethods = emptyList(),
        )

        assertEquals(5, result.size)
        assertFalse(result.contains("isDirectoryCreationOrDeletionAllowedForFuse"))
        assertTrue(result.contains("openWithFuse"))
        assertTrue(result.contains("deleteFileForFuse"))
    }

    @Test
    fun `设备发现但未 hooked 的方法计入适用集合`() {
        // 设备类中存在 isDirectoryCreationOrDeletionAllowedForFuse，但未 hook，
        // 出现在 unknownMethods 中，应计入适用集合，供 server 层判缺口。
        val hooked5 = allCritical.filter { it != "isDirectoryCreationOrDeletionAllowedForFuse" }
        val result = applicableCriticalMethods(
            hookedMethods = hooked(hooked5),
            unknownMethods = listOf("isDirectoryCreationOrDeletionAllowedForFuse [java.lang.String, int, boolean]"),
            uidSkippedMethods = emptyList(),
            failedMethods = emptyList(),
        )

        assertEquals(6, result.size)
        assertTrue(result.contains("isDirectoryCreationOrDeletionAllowedForFuse"))
    }

    @Test
    fun `全部6个关键方法均已发现且hooked`() {
        val result = applicableCriticalMethods(
            hookedMethods = hooked(allCritical),
            unknownMethods = emptyList(),
            uidSkippedMethods = emptyList(),
            failedMethods = emptyList(),
        )

        assertEquals(6, result.size)
        allCritical.forEach { assertTrue(result.contains(it)) }
    }

    @Test
    fun `非关键方法不计入适用集合`() {
        val result = applicableCriticalMethods(
            hookedMethods = listOf("onFileOpenForFuse [java.lang.String]"),
            unknownMethods = listOf("getMediaUriForFuse [java.lang.String, int, java.lang.String]"),
            uidSkippedMethods = emptyList(),
            failedMethods = emptyList(),
        )

        assertEquals(0, result.size)
    }

    @Test
    fun `failed和uidSkipped中的关键方法也计入适用集合`() {
        val result = applicableCriticalMethods(
            hookedMethods = emptyList(),
            unknownMethods = emptyList(),
            uidSkippedMethods = listOf("isDirAccessAllowedForFuse [java.lang.String, int, int] (uid unresolved)"),
            failedMethods = listOf("deleteFileForFuse [java.lang.String, int, int] (install failed)"),
        )

        assertEquals(2, result.size)
        assertTrue(result.contains("isDirAccessAllowedForFuse"))
        assertTrue(result.contains("deleteFileForFuse"))
    }

    @Test
    fun `inline未DISABLED时平台支持标记为完全no-op`() {
        // 前提：本类先执行且单例 inlineState 处于初始 NOT_LOADED（尚未 DISABLED）。
        assertEquals("NOT_LOADED", NativeHookStatus.currentInlineState())
        // 基线语义：非 DISABLED 状态下完全短路——不重置 nativeStatus/mount 证据，
        // 不发布快照。若门面绕过早退走 publish 链路，JVM 下会因 android.util.Log
        // 未 mock 而 RuntimeException（快照写入失败的日志路径），本测试即失败。
        NativeHookStatus.markInlinePlatformSupported()
        assertEquals("NOT_LOADED", NativeHookStatus.currentInlineState())
        assertFalse(NativeHookStatus.isInlineDisabled())
        assertFalse(NativeHookStatus.isInlineDisabledByPlatform())
    }
}
