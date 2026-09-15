package me.gm.cleaner.runtime.mediaprovider.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HookPathUserIdTest {

    @Test
    fun `emulated零用户路径返回0`() {
        assertEquals(0, extractEmulatedUserIdFast("/storage/emulated/0/DCIM/a.jpg"))
        assertEquals(0, extractUserIdFromPath("/storage/emulated/0/DCIM/a.jpg"))
    }

    @Test
    fun `emulated多位数用户路径返回对应用户`() {
        assertEquals(999, extractEmulatedUserIdFast("/storage/emulated/999/DCIM/a.jpg"))
        assertEquals(999, extractUserIdFromPath("/storage/emulated/999/DCIM/a.jpg"))
    }

    @Test
    fun `emulated前缀大小写不敏感`() {
        assertEquals(10, extractEmulatedUserIdFast("/STORAGE/EMULATED/10/DCIM/a.jpg"))
        assertEquals(10, extractUserIdFromPath("/STORAGE/EMULATED/10/DCIM/a.jpg"))
    }

    @Test
    fun `emulated裸前缀返回0`() {
        assertEquals(0, extractEmulatedUserIdFast("/storage/emulated/"))
        assertEquals(0, extractUserIdFromPath("/storage/emulated/"))
    }

    @Test
    fun `emulated非数字后缀返回0`() {
        assertEquals(0, extractEmulatedUserIdFast("/storage/emulated/abc/DCIM/a.jpg"))
        assertEquals(0, extractUserIdFromPath("/storage/emulated/abc/DCIM/a.jpg"))
        assertEquals(0, extractEmulatedUserIdFast("/storage/emulated/0abc/DCIM"))
        assertEquals(0, extractUserIdFromPath("/storage/emulated/0abc/DCIM"))
    }

    @Test
    fun `非emulated路径快检返回空`() {
        assertNull(extractEmulatedUserIdFast("/mnt/user/0/primary/DCIM/a.jpg"))
        assertNull(extractEmulatedUserIdFast("/data/media/0/DCIM/a.jpg"))
    }

    @Test
    fun `mnt用户路径回退正则返回0`() {
        assertEquals(0, extractUserIdFromPath("/mnt/user/0/primary/DCIM/a.jpg"))
    }

    @Test
    fun `非法路径返回0`() {
        assertEquals(0, extractUserIdFromPath("garbage"))
    }
}
