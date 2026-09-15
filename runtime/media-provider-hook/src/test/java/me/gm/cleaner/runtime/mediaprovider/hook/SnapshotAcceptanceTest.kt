package me.gm.cleaner.runtime.mediaprovider.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotAcceptanceTest {

    @Test
    fun `首次加载接受快照`() {
        assertTrue(shouldAcceptSnapshot("epoch1", 1L, "", 0L))
        assertTrue(shouldAcceptSnapshot("", 1L, "", 0L))
        assertTrue(shouldAcceptSnapshot("epoch1", 0L, "", 0L))
    }

    @Test
    fun `epoch变化接受快照`() {
        assertTrue(shouldAcceptSnapshot("epochB", 1L, "epochA", 5L))
    }

    @Test
    fun `新epoch非空且当前为空时接受快照`() {
        assertTrue(shouldAcceptSnapshot("epoch1", 1L, "", 5L))
    }

    @Test
    fun `同epoch要求代数严格递增`() {
        assertTrue(shouldAcceptSnapshot("epoch1", 6L, "epoch1", 5L))
    }

    @Test
    fun `同epoch同代数拒绝快照`() {
        assertFalse(shouldAcceptSnapshot("epoch1", 5L, "epoch1", 5L))
    }

    @Test
    fun `同epoch旧代数拒绝快照`() {
        assertFalse(shouldAcceptSnapshot("epoch1", 4L, "epoch1", 5L))
    }

    @Test
    fun `新epoch为空时按代数比较`() {
        assertTrue(shouldAcceptSnapshot("", 6L, "epoch1", 5L))
        assertFalse(shouldAcceptSnapshot("", 5L, "epoch1", 5L))
        assertFalse(shouldAcceptSnapshot("", 4L, "epoch1", 5L))
    }
}
