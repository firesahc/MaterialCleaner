package me.gm.cleaner.runtime.mediaprovider.hook.fuse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuseUidGateTest {
    @Test
    fun `核验的primitive int uid可以安装`() {
        assertNull(uidResolutionSkipReason(1, arrayOf(String::class.java, Int::class.javaPrimitiveType!!)))
    }

    @Test
    fun `核验的装箱Integer uid可以安装`() {
        assertNull(uidResolutionSkipReason(1, arrayOf(String::class.java, Integer::class.java)))
    }

    @Test
    fun `未知uid签名会跳过`() {
        assertEquals(
            "uid_signature_unresolved",
            uidResolutionSkipReason(-1, arrayOf(String::class.java, Int::class.javaPrimitiveType!!)),
        )
    }

    @Test
    fun `越界uid索引会跳过`() {
        assertEquals(
            "uid_index_out_of_range",
            uidResolutionSkipReason(2, arrayOf(String::class.java, Int::class.javaPrimitiveType!!)),
        )
    }

    @Test
    fun `非整数uid参数会跳过`() {
        assertEquals(
            "uid_parameter_not_integer",
            uidResolutionSkipReason(1, arrayOf(String::class.java, Long::class.javaPrimitiveType!!)),
        )
    }
}
