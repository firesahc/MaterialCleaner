package me.gm.cleaner.runtime.server.hookbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CallbackRegistrationCommitTest {

    @Test
    fun `先注册回调再发布连接`() {
        val steps = mutableListOf<String>()

        callbackRegisteredBeforePublish(
            registerCallback = { steps += "callback" },
            publish = { steps += "publish" },
        )

        assertEquals(listOf("callback", "publish"), steps)
    }

    @Test
    fun `回调注册失败时不发布半连接`() {
        var published = false

        assertThrows(IllegalStateException::class.java) {
            callbackRegisteredBeforePublish(
                registerCallback = { throw IllegalStateException("callback registration failed") },
                publish = { published = true },
            )
        }

        assertEquals(false, published)
    }
}
