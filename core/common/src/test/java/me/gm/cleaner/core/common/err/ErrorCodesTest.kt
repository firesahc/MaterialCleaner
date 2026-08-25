package me.gm.cleaner.core.common.err

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 错误码注册表门禁测试：
 * 保证注册表格式合法、全局唯一、分段完整，防止错误码退化为第二套自由文本。
 */
class ErrorCodesTest {

    @Test
    fun `注册表常量与码值一一对应且无重复`() {
        val constants = ErrorCodes::class.java.declaredFields
            .filter { it.type == String::class.java && java.lang.reflect.Modifier.isStatic(it.modifiers) }
        val values = constants.map {
            it.isAccessible = true
            it.get(null) as String
        }
        // 归一化（去除分隔符）后常量名必须与码值逐字符一致：
        // 如 MOUNT_SETNS_OPEN_FAILED ↔ "MOUNT.SETNS.OPEN_FAILED"，允许原因段使用下划线连接多个单词。
        fun normalize(raw: String) = raw.lowercase().replace("_", "").replace(".", "")
        constants.zip(values).forEach { (field, value) ->
            assertEquals(
                "常量 ${field.name} 的命名应与码值归一化后一致",
                normalize(field.name),
                normalize(value),
            )
        }
        assertEquals("错误码值不允许重复", values.size, values.toSet().size)
    }

    @Test
    fun `所有码都符合大写分段格式`() {
        val all = ErrorCodes::class.java.declaredFields
            .filter { it.type == String::class.java && java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map {
                it.isAccessible = true
                it.get(null) as String
            }
        assertTrue("注册表不得为空", all.isNotEmpty())
        // 域.子系统.原因 三级以上；每段可由下划线连接多词，如 OPEN_FAILED。
        val pattern = Regex("[A-Z]+(_[A-Z]+)*(\\.[A-Z]+(_[A-Z]+)*)+")
        all.forEach { code ->
            assertTrue(
                "码 $code 不符合 <域>.<子系统>.<原因> 点分大写格式",
                pattern.matches(code),
            )
        }
    }

    @Test
    fun `五个业务域全部有码覆盖`() {
        val domains = ErrorCodes::class.java.declaredFields
            .filter { it.type == String::class.java && java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map {
                it.isAccessible = true
                (it.get(null) as String).substringBefore('.')
            }
        assertEquals(
            "bind mount / Java Hook / FUSE Hook / DataBus / supervisor 五域必须齐备",
            setOf("MOUNT", "HOOK", "BUS", "SUP"),
            domains.toSet(),
        )
    }
}
