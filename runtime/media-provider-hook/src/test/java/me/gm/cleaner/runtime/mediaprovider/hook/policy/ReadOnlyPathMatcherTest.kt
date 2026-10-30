package me.gm.cleaner.runtime.mediaprovider.hook.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadOnlyPathMatcherTest {
    @Test
    fun `匹配规则本身和任意深度子孙路径`() {
        val rule = "/storage/emulated/0/Pictures"
        assertTrue(ReadOnlyPathMatcher.matches(rule, rule))
        assertTrue(ReadOnlyPathMatcher.matches(rule, "$rule/a.jpg"))
        assertTrue(ReadOnlyPathMatcher.matches(rule, "$rule/album/2026/a.jpg"))
    }

    @Test
    fun `按路径段边界匹配而不命中相似前缀`() {
        assertFalse(ReadOnlyPathMatcher.matches("/storage/emulated/0/Pictures", "/storage/emulated/0/PicturesBackup/a.jpg"))
        assertFalse(ReadOnlyPathMatcher.matches("/storage/emulated/0/Pictures", "/storage/emulated/0/Picture/a.jpg"))
    }

    @Test
    fun `重复和尾部分隔符先规范化`() {
        assertTrue(ReadOnlyPathMatcher.matches("//storage///emulated/0/Pictures///", "/storage/emulated/0/Pictures//album/"))
    }

    @Test
    fun `区分大小写匹配不同大小写路径不命中`() {
        assertFalse(ReadOnlyPathMatcher.matches("/storage/emulated/0/Pictures", "/STORAGE/EMULATED/0/pictures/a.jpg"))
    }

    @Test
    fun `相同大小写路径命中`() {
        assertTrue(ReadOnlyPathMatcher.matches("/storage/emulated/0/Pictures", "/storage/emulated/0/Pictures/a.jpg"))
    }
}
