package me.gm.cleaner.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * logcat 真死（serverException=2）App 侧看门狗决策单测。
 *
 * 覆盖轮询触发（连续 3 次命中 2 即重启）与豁免逻辑
 * （启动期 3 不计数、正常/未知复位、通知去重与启动期误报抑制）。
 *
 * 真机注入验证为手动步骤（本机不执行）：
 * 1. 真机以 Root 启动服务，确认主界面状态健康；
 * 2. `adb shell "su -c 'pkill -9 -f \"/system/bin/logcat\"'"` 杀死 server 内的
 *    logcat 子进程（只杀 logcat，不杀 cleaner_server 本体）；
 * 3. 观察 logcat：5s 周期轮询命中 serverException=2，连续 3 次（约 15s）
 *    后 ServerStateMachine 走 killServerProcess + recoverIfTargetRunning；
 * 4. 预期 15-20s 内（ADR-0009 基线）服务恢复 RUNNING，ClientErrorJournal
 *    记一条 SUP.WATCHDOG.RESTART（subject=watchdog-logcat），且只弹出一次
 *    logcat 真死通知（去重窗口内无重复）；
 * 5. 刚重启尚未观测到 AmStart 时 serverException=3，看门狗不计数、不误报。
 */
class LogcatDeathPolicyTest {

    @Test
    fun `命中2计数累加_连续3次达到重启阈值`() {
        var count = 0
        count = LogcatDeathPolicy.nextDeadCount(count, 2)
        assertEquals(1, count)
        assertFalse(LogcatDeathPolicy.shouldRestart(count))
        count = LogcatDeathPolicy.nextDeadCount(count, 2)
        assertEquals(2, count)
        assertFalse(LogcatDeathPolicy.shouldRestart(count))
        count = LogcatDeathPolicy.nextDeadCount(count, 2)
        assertEquals(3, count)
        assertTrue(LogcatDeathPolicy.shouldRestart(count))
    }

    @Test
    fun `启动期3豁免_不计数且复位历史计数`() {
        assertEquals(0, LogcatDeathPolicy.nextDeadCount(0, 3))
        // 重启后新进程短暂处于 3：旧计数不得泄漏到新进程。
        assertEquals(0, LogcatDeathPolicy.nextDeadCount(2, 3))
        assertFalse(LogcatDeathPolicy.shouldRestart(0))
    }

    @Test
    fun `正常0与未知null复位计数`() {
        assertEquals(0, LogcatDeathPolicy.nextDeadCount(2, 0))
        assertEquals(0, LogcatDeathPolicy.nextDeadCount(2, null))
        // 中间一次正常即中断连续性，需重新累积 3 次。
        var count = LogcatDeathPolicy.nextDeadCount(2, 0)
        count = LogcatDeathPolicy.nextDeadCount(count, 2)
        count = LogcatDeathPolicy.nextDeadCount(count, 2)
        assertFalse(LogcatDeathPolicy.shouldRestart(count))
    }

    @Test
    fun `其他异常码不触发logcat重启`() {
        assertEquals(0, LogcatDeathPolicy.nextDeadCount(0, 4))
        assertEquals(0, LogcatDeathPolicy.nextDeadCount(0, 6))
    }

    @Test
    fun `通知_命中2且超出窗口才弹出`() {
        assertTrue(LogcatDeathPolicy.shouldNotify(2, 0L, LogcatDeathPolicy.NOTIFY_DEDUP_MS + 1))
        // 窗口内重复广播去重。
        assertFalse(LogcatDeathPolicy.shouldNotify(2, 1000L, 2000L))
    }

    @Test
    fun `通知_启动期3与已恢复0一律抑制`() {
        val now = LogcatDeathPolicy.NOTIFY_DEDUP_MS + 1
        assertFalse(LogcatDeathPolicy.shouldNotify(3, 0L, now))
        assertFalse(LogcatDeathPolicy.shouldNotify(0, 0L, now))
        assertFalse(LogcatDeathPolicy.shouldNotify(4, 0L, now))
    }

    @Test
    fun `通知_状态未知时failOpen但仍受去重约束`() {
        assertTrue(LogcatDeathPolicy.shouldNotify(null, 0L, LogcatDeathPolicy.NOTIFY_DEDUP_MS + 1))
        assertFalse(LogcatDeathPolicy.shouldNotify(null, 1000L, 2000L))
    }
}
