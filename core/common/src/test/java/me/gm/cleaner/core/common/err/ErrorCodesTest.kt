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

    /**
     * 预留码清单与注册表保持同步。
     *
     * 背景：注册表允许包含当前没有生产产出点的码（失败语义契约），但
     * "已注册"极易被误读为"该类失败已被观测上报"。RESERVED_CODES 把这层
     * 区别显式化：某码接入生产路径后必须从清单与预留分区一并移出，
     * 否则清单会与现实脱节。
     *
     * 本断言只能校验清单与注册表的对应关系；"某个预留码是否真的没有
     * 生产引用"需要跨模块源码扫描，不适合放在单元测试里（模块工作目录
     * 假设脆弱），该事实由评审与 grep 核对保证。
     */
    @Test
    fun `预留码清单与注册表一致且不遗漏`() {
        val registered = ErrorCodes::class.java.declaredFields
            .filter { it.type == String::class.java && java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map {
                it.isAccessible = true
                it.get(null) as String
            }
            .toSet()
        val reserved = ErrorCodes.RESERVED_CODES
        assertTrue("预留码清单不得为空", reserved.isNotEmpty())
        for (code in reserved) {
            assertTrue("预留码 $code 未在注册表中定义", registered.contains(code))
        }
        assertEquals(
            "预留码清单存在重复项",
            reserved.size,
            reserved.toSet().size,
        )
        val active = registered - reserved
        assertTrue("已接入码不得为空", active.isNotEmpty())
        assertTrue(
            "已接入码应多于预留码，否则说明错误码体系整体未接入生产路径",
            active.size > reserved.size,
        )
    }
}
