package me.gm.cleaner.runtime.mediaprovider.hook.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

class HookDataBusBridgeResultTest {
    @Test
    fun `callback缺失返回显式不可用`() {
        val result = callDataBusCallback<String, Int>(null, "readSnapshot:policy") { it.length }

        assertEquals(
            DataBusBridgeResult.Unavailable("readSnapshot:policy", "callback_missing"),
            result,
        )
    }

    @Test
    fun `Binder异常返回不可用而不是空结果`() {
        val result = callDataBusCallback("callback", "readSnapshot:policy") {
            throw IllegalStateException("remote call failed")
        }

        assertEquals(
            DataBusBridgeResult.Unavailable("readSnapshot:policy", "IllegalStateException"),
            result,
        )
    }

    @Test
    fun `成功响应保留空快照的可用语义`() {
        val result = callDataBusCallback<String, String?>("callback", "readSnapshot:policy") { null }

        assertEquals(DataBusBridgeResult.Available(null), result)
    }
}
