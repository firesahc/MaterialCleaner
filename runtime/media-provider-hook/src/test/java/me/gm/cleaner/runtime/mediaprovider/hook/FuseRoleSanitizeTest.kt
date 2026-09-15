package me.gm.cleaner.runtime.mediaprovider.hook

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FuseRoleSanitizeTest {
    @Test
    fun `有效下标直接透传`() {
        val types = arrayOf<Class<*>>(String::class.java, String::class.java, Int::class.javaPrimitiveType!!)
        assertArrayEquals(intArrayOf(0, 1, 2), sanitizeRoleIndices(0, 1, 2, types))
    }

    @Test
    fun `uid为负一表示推断直接透传`() {
        val types = arrayOf<Class<*>>(String::class.java, String::class.java)
        assertArrayEquals(intArrayOf(0, 1, -1), sanitizeRoleIndices(0, 1, -1, types))
    }

    @Test
    fun `path2指向long类型钳制为负一`() {
        val types = arrayOf<Class<*>>(String::class.java, Long::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
        assertArrayEquals(intArrayOf(0, -1, 2), sanitizeRoleIndices(0, 1, 2, types))
    }

    @Test
    fun `path2越界钳制为负一`() {
        val types = arrayOf<Class<*>>(String::class.java, String::class.java, Int::class.javaPrimitiveType!!)
        assertArrayEquals(intArrayOf(0, -1, 2), sanitizeRoleIndices(0, 5, 2, types))
    }

    @Test
    fun `path2与path重复钳制为负一`() {
        val types = arrayOf<Class<*>>(String::class.java, Int::class.javaPrimitiveType!!)
        assertArrayEquals(intArrayOf(0, -1, 1), sanitizeRoleIndices(0, 0, 1, types))
    }

    @Test
    fun `uid指向非int类型钳制为负一`() {
        val types = arrayOf<Class<*>>(String::class.java, String::class.java, Long::class.javaPrimitiveType!!)
        assertArrayEquals(intArrayOf(0, 1, -1), sanitizeRoleIndices(0, 1, 2, types))
    }

    @Test
    fun `uid越界钳制为负一`() {
        val types = arrayOf<Class<*>>(String::class.java, String::class.java, Int::class.javaPrimitiveType!!)
        assertArrayEquals(intArrayOf(0, 1, -1), sanitizeRoleIndices(0, 1, 9, types))
    }

    @Test
    fun `uid与path重复钳制为负一`() {
        val types = arrayOf<Class<*>>(String::class.java, String::class.java)
        assertArrayEquals(intArrayOf(0, 1, -1), sanitizeRoleIndices(0, 1, 0, types))
    }

    @Test
    fun `path指向File返回空`() {
        val types = arrayOf<Class<*>>(File::class.java, String::class.java, Int::class.javaPrimitiveType!!)
        assertNull(sanitizeRoleIndices(0, 1, 2, types))
    }

    @Test
    fun `path下标越界返回空`() {
        val types = arrayOf<Class<*>>(String::class.java, Int::class.javaPrimitiveType!!)
        assertNull(sanitizeRoleIndices(5, 1, 1, types))
    }

    @Test
    fun `uid为Integer装箱类型直接透传`() {
        val types = arrayOf<Class<*>>(String::class.java, String::class.java, Integer::class.java)
        assertArrayEquals(intArrayOf(0, 1, 2), sanitizeRoleIndices(0, 1, 2, types))
    }
}
