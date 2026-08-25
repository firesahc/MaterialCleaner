package me.gm.cleaner.runtime.server.observer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ProcessMountSelectionPolicyTest {

    @Test
    fun `规则与目录限制处理不一致时拒绝共享 namespace`() {
        val selected = ProcessMountSelectionPolicy.resolve(
            packageNames = arrayOf("record.only", "redirect.configured"),
            redirectRuleSignature = {
                if (it == "redirect.configured") listOf("source" to "target") else null
            },
            shouldUnmountDataRestriction = { it == "record.only" },
        )

        assertEquals(
            ProcessMountSelectionPolicy.Selection.Conflict(
                linkedSetOf("record.only", "redirect.configured"),
            ),
            selected,
        )
    }

    @Test
    fun `没有规则但目录限制处理不一致时显式冲突`() {
        val selected = ProcessMountSelectionPolicy.resolve(
            packageNames = arrayOf("denied", "record.enabled"),
            redirectRuleSignature = { null },
            shouldUnmountDataRestriction = { it == "record.enabled" },
        )

        assertEquals(
            ProcessMountSelectionPolicy.Selection.Conflict(
                linkedSetOf("denied", "record.enabled"),
            ),
            selected,
        )
    }

    @Test
    fun `配置全部移除后仍选择首包以清理旧 namespace`() {
        val selected = ProcessMountSelectionPolicy.resolve(
            packageNames = arrayOf("first", "second"),
            redirectRuleSignature = { null },
            shouldUnmountDataRestriction = { false },
        )

        assertEquals(ProcessMountSelectionPolicy.Selection.Selected("first"), selected)
    }

    @Test
    fun `空包列表不产生挂载调用`() {
        assertSame(
            ProcessMountSelectionPolicy.Selection.None,
            ProcessMountSelectionPolicy.resolve(
                packageNames = emptyArray(),
                redirectRuleSignature = { null },
                shouldUnmountDataRestriction = { false },
            ),
        )
    }

    @Test
    fun `共享进程相同规则可复用一个代表`() {
        val signature = listOf("source" to "target")

        val selected = ProcessMountSelectionPolicy.resolve(
            packageNames = arrayOf("first", "second"),
            redirectRuleSignature = { signature },
            shouldUnmountDataRestriction = { false },
        )

        assertEquals(ProcessMountSelectionPolicy.Selection.Selected("first"), selected)
    }

    @Test
    fun `共享进程不同规则必须显式冲突`() {
        val selected = ProcessMountSelectionPolicy.resolve(
            packageNames = arrayOf("first", "second", "plain"),
            redirectRuleSignature = { packageName ->
                when (packageName) {
                    "first" -> listOf("source-a" to "target")
                    "second" -> listOf("source-b" to "target")
                    else -> null
                }
            },
            shouldUnmountDataRestriction = { false },
        )

        assertEquals(
            ProcessMountSelectionPolicy.Selection.Conflict(
                linkedSetOf("first", "second", "plain"),
            ),
            selected,
        )
    }
}
