package me.gm.cleaner.runtime.mediaprovider.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeRegistrationAckPolicyTest {
    @Test
    fun `新Provider必须显式确认注册成功`() {
        assertTrue(BridgeRegistrationAckPolicy.isAccepted(true, true, true))
        assertFalse(BridgeRegistrationAckPolicy.isAccepted(true, true, false))
    }

    @Test
    fun `旧Provider无确认字段时保持兼容`() {
        assertTrue(BridgeRegistrationAckPolicy.isAccepted(true, false, false))
    }

    @Test
    fun `Provider无返回结果不能视为成功`() {
        assertFalse(BridgeRegistrationAckPolicy.isAccepted(false, false, false))
    }
}
