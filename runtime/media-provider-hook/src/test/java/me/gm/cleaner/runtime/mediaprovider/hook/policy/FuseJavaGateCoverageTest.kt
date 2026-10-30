package me.gm.cleaner.runtime.mediaprovider.hook.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FuseJavaGateCoverage 方法名提取与覆盖判定单测。
 *
 * 覆盖申请项 P2-4：
 * - methodName [参数签名] 形式的方法名提取；
 * - methodName(参数签名) 形式的方法名提取；
 * - 带错误说明的 UID skipped（如 "... (uid unresolved)"）；
 * - 带失败原因的 failed（如 "... (install failed)"）；
 * - 不规范或空字符串；
 * - 重复方法记录的去重。
 */
class FuseJavaGateCoverageTest {

    @Test
    fun `methodName带参数签名方括号形式提取方法名`() {
        val result = applicableCriticalMethods(
            hookedMethods = listOf("openWithFuse [java.lang.String, int]"),
            unknownMethods = emptyList(),
            uidSkippedMethods = emptyList(),
            failedMethods = emptyList(),
        )
        assertTrue(result.contains("openWithFuse"))
        assertEquals(1, result.size)
    }

    @Test
    fun `methodName带圆括号参数签名形式提取方法名`() {
        val result = applicableCriticalMethods(
            hookedMethods = emptyList(),
            unknownMethods = listOf("renameForFuse(java.lang.String, java.lang.String, int)"),
            uidSkippedMethods = emptyList(),
            failedMethods = emptyList(),
        )
        assertTrue(result.contains("renameForFuse"))
        assertEquals(1, result.size)
    }

    @Test
    fun `uidSkipped带错误说明仍提取方法名`() {
        val result = applicableCriticalMethods(
            hookedMethods = emptyList(),
            unknownMethods = emptyList(),
            uidSkippedMethods = listOf(
                "isDirAccessAllowedForFuse [java.lang.String, int, int] (uid unresolved)",
            ),
            failedMethods = emptyList(),
        )
        assertTrue(result.contains("isDirAccessAllowedForFuse"))
        assertEquals(1, result.size)
    }

    @Test
    fun `failed带失败原因仍提取方法名`() {
        val result = applicableCriticalMethods(
            hookedMethods = emptyList(),
            unknownMethods = emptyList(),
            uidSkippedMethods = emptyList(),
            failedMethods = listOf("deleteFileForFuse [java.lang.String] (install failed)"),
        )
        assertTrue(result.contains("deleteFileForFuse"))
        assertEquals(1, result.size)
    }

    @Test
    fun `空字符串不提取任何方法名`() {
        val result = applicableCriticalMethods(
            hookedMethods = listOf(""),
            unknownMethods = listOf("   "),
            uidSkippedMethods = listOf(""),
            failedMethods = listOf("   "),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `重复方法记录只计入一次`() {
        val result = applicableCriticalMethods(
            hookedMethods = listOf(
                "openWithFuse [java.lang.String]",
                "openWithFuse [java.lang.String]",
                "openWithFuse (java.lang.String)",
            ),
            unknownMethods = emptyList(),
            uidSkippedMethods = emptyList(),
            failedMethods = emptyList(),
        )
        assertTrue(result.contains("openWithFuse"))
        assertEquals(1, result.size)
    }

    @Test
    fun `非候选方法不计入适用集合`() {
        val result = applicableCriticalMethods(
            hookedMethods = listOf("onFileLookupForFuse [java.lang.String]"),
            unknownMethods = listOf("getMediaUriForFuse [java.lang.String, int, java.lang.String]"),
            uidSkippedMethods = emptyList(),
            failedMethods = emptyList(),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `四类方法均参与发现集合`() {
        val result = applicableCriticalMethods(
            hookedMethods = listOf("openWithFuse [java.lang.String]"),
            unknownMethods = listOf("renameForFuse [java.lang.String, java.lang.String]"),
            uidSkippedMethods = listOf("isDirAccessAllowedForFuse [java.lang.String, int, int] (uid unresolved)"),
            failedMethods = listOf("deleteFileForFuse [java.lang.String] (install failed)"),
        )
        assertEquals(4, result.size)
        assertTrue(result.contains("openWithFuse"))
        assertTrue(result.contains("renameForFuse"))
        assertTrue(result.contains("isDirAccessAllowedForFuse"))
        assertTrue(result.contains("deleteFileForFuse"))
    }
}
