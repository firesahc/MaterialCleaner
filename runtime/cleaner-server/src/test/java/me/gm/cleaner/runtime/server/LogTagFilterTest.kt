package me.gm.cleaner.runtime.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * logcat tag 真相源的门禁测试。
 *
 * 背景：logcat 的 filterSpec 只写 `<tag>:*` **不会**抑制其它 tag，
 * 必须以 `*:S` 结尾才真正过滤；缺少 `*:S` 时会静默退化为全量输出，
 * 采集量回到未过滤水平（设备实测 875MB/天）。该不变式一旦破坏，
 * 不会编译失败、也不会运行时报错，只表现为磁盘占用暴涨，因此在此设门禁。
 *
 * 另设核心 tag 存在性断言：tag 清单与代码演进容易漂移（此前诊断导出
 * 侧手写的清单已遗漏多个实际在用的 tag），新增本项目日志 tag 时应同步登记。
 */
class LogTagFilterTest {

    @Test
    fun `过滤串必须以静默项结尾否则过滤失效`() {
        assertTrue(
            "filterSpec 必须以 *:S 结尾，缺失会导致 logcat 退化为全量输出",
            LogTagFilter.FILTER_SPEC.endsWith(" *:S"),
        )
    }

    @Test
    fun `过滤串参数数组末位为静默项`() {
        val args = LogTagFilter.filterSpecArgs()
        assertEquals("*:S", args.last())
    }

    @Test
    fun `主通道命令带 main 与 system buffer 且以静默项结尾`() {
        val args = LogTagFilter.mainChannelArgs()
        assertTrue("主通道必须采集 main buffer", args.contains("main,system"))
        assertEquals("*:S", args.last())
    }

    @Test
    fun `崩溃通道不施加 tag 过滤`() {
        val args = LogTagFilter.crashChannelArgs()
        assertTrue("崩溃通道必须采集 crash buffer", args.contains("crash"))
        assertTrue(
            "崩溃的 tag 是崩溃进程名，无法穷举进白名单，崩溃通道不得施加过滤",
            args.none { it == "*:S" },
        )
    }

    @Test
    fun `核心 tag 均已登记避免采集与诊断同时漏采`() {
        val required = listOf(
            // server / app 进程
            "me.gm.cleaner",
            "me.gm.cleaner.runtime.server",
            "cleaner_server",
            // 重定向与挂载
            "MC_REDIRECT",
            // 生命周期与状态机
            "MC_BOOT",
            "MC/Test",
            "MC/StateMachine",
            "MC/BootState",
            "MC/XposedState",
            // 组件级
            "DataBus",
            "NativeHookStatus",
            "HookPolicyCache",
            "ActivityManagerLogsObserver",
            // native 与启动器
            "xhook",
            "starter",
        )
        val spec = LogTagFilter.FILTER_SPEC
        for (tag in required) {
            assertTrue("tag 未登记：$tag", spec.contains("$tag:"))
        }
    }

    @Test
    fun `过滤串不出现裸 tag 以免被误当作优先级规则`() {
        // 每个已登记 tag 都必须带 ":*"，否则 logcat 会把它当成
        // "提升该 tag 优先级"而非"只保留该 tag"。
        val spec = LogTagFilter.FILTER_SPEC
        val tokens = spec.split(" ").filter { it != "*:S" }
        for (token in tokens) {
            assertTrue("过滤项缺少 :* 后缀：$token", token.endsWith(":*"))
        }
    }
}
