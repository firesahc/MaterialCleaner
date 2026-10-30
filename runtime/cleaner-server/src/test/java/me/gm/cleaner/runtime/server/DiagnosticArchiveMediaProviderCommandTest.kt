package me.gm.cleaner.runtime.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticArchiveMediaProviderCommandTest {

    @Test
    fun `PID只经过一次命令替换并传给定向logcat`() {
        val command = DiagnosticArchive.mediaProviderPidLogcatCommand()

        assertTrue(command.startsWith("MPID=${'$'}(pidof "))
        assertEquals(1, command.split("${'$'}(").size - 1)
        assertTrue(command.contains("awk '{print ${'$'}1}'"))
        assertTrue(command.contains("--pid=${'$'}MPID"))
        val nestedCommandSubstitution = "MPID=${'$'}(" + "${'$'}("
        assertFalse(command.contains(nestedCommandSubstitution))
    }

    @Test
    fun `只有PID为空时才报告MediaProvider未运行`() {
        val command = DiagnosticArchive.mediaProviderPidLogcatCommand()

        assertTrue(command.contains("if [ -z \"${'$'}MPID\" ]; then"))
        assertTrue(command.contains("media provider process is not running (no pid found)"))
        assertTrue(command.contains("else /system/bin/logcat"))
    }
}
