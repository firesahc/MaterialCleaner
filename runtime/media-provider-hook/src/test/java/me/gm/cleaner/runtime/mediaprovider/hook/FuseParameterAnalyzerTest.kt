package me.gm.cleaner.runtime.mediaprovider.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * ParameterAnalyzer 语义单测：根治无条件取第二个 String 为 path2 的误标。
 *
 * 约定：仅 rename 行为需要 path2（renameOp 是唯一消费方），其余行为即使有两个
 * String 也必须钳为 -1；sanitize 只做类型级兜底，挡不住此类语义误标。
 */
class FuseParameterAnalyzerTest {

    @Suppress("unused")
    class FakeFuseMethods {
        fun renameForFuse(oldPath: String, newPath: String, uid: Int) {}
        fun openWithFuse(path: String, displayName: String, uid: Int) {}
        fun onFileLookupForFuse(path: String, tag: String, uid: Int) {}
        fun isUidAllowedAccessToDataOrObbPathForFuse(uid: Int, path: String) {}
        fun isDirAccessAllowedForFuse(path: String, uid: Int, accessType: Int) {}
        fun isDirectoryCreationOrDeletionAllowedForFuse(path: String, uid: Int, forCreate: Boolean) {}
        fun insertFileIfNecessaryForFuse(path: String, uid: Int) {}
    }

    private fun method(name: String, vararg types: Class<*>?) =
        FakeFuseMethods::class.java.getDeclaredMethod(name, *types)

    @Test
    fun `rename双String透传path2`() {
        val m = method(
            "renameForFuse",
            String::class.java, String::class.java, Int::class.javaPrimitiveType,
        )
        val roles = ParameterAnalyzer.analyze(m, true)
        assertNotNull(roles)
        assertEquals(0, roles.pathIndex)
        assertEquals(1, roles.path2Index)
        assertEquals(2, roles.uidIndex)
    }

    @Test
    fun `rename便捷重载自动透传path2`() {
        val m = method(
            "renameForFuse",
            String::class.java, String::class.java, Int::class.javaPrimitiveType,
        )
        val roles = ParameterAnalyzer.analyze(m)
        assertNotNull(roles)
        assertEquals(0, roles.pathIndex)
        assertEquals(1, roles.path2Index)
        assertEquals(2, roles.uidIndex)
    }

    @Test
    fun `非rename双String钳path2为负一`() {
        val m = method(
            "openWithFuse",
            String::class.java, String::class.java, Int::class.javaPrimitiveType,
        )
        val roles = ParameterAnalyzer.analyze(m, false)
        assertNotNull(roles)
        assertEquals(0, roles.pathIndex)
        assertEquals(-1, roles.path2Index)
        assertEquals(2, roles.uidIndex)
    }

    @Test
    fun `非rename便捷重载自动钳path2为负一`() {
        val m = method(
            "onFileLookupForFuse",
            String::class.java, String::class.java, Int::class.javaPrimitiveType,
        )
        val roles = ParameterAnalyzer.analyze(m)
        assertNotNull(roles)
        assertEquals(0, roles.pathIndex)
        assertEquals(-1, roles.path2Index)
        assertEquals(2, roles.uidIndex)
    }

    @Test
    fun `回归isUid参数反转`() {
        val m = method(
            "isUidAllowedAccessToDataOrObbPathForFuse",
            Int::class.javaPrimitiveType, String::class.java,
        )
        val roles = ParameterAnalyzer.analyze(m)
        assertNotNull(roles)
        assertEquals(1, roles.pathIndex)
        assertEquals(-1, roles.path2Index)
        assertEquals(0, roles.uidIndex)
    }

    @Test
    fun `回归isDir固定uid`() {
        val intType = Int::class.javaPrimitiveType
        val m = method("isDirAccessAllowedForFuse", String::class.java, intType, intType)
        val roles = ParameterAnalyzer.analyze(m)
        assertNotNull(roles)
        assertEquals(0, roles.pathIndex)
        assertEquals(-1, roles.path2Index)
        assertEquals(1, roles.uidIndex)
    }

    @Test
    fun `回归isDirectory布尔版固定uid`() {
        val m = method(
            "isDirectoryCreationOrDeletionAllowedForFuse",
            String::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
        )
        val roles = ParameterAnalyzer.analyze(m)
        assertNotNull(roles)
        assertEquals(0, roles.pathIndex)
        assertEquals(-1, roles.path2Index)
        assertEquals(1, roles.uidIndex)
    }

    @Test
    fun `回归单String单uid`() {
        val m = method(
            "insertFileIfNecessaryForFuse",
            String::class.java, Int::class.javaPrimitiveType,
        )
        val roles = ParameterAnalyzer.analyze(m)
        assertNotNull(roles)
        assertEquals(0, roles.pathIndex)
        assertEquals(-1, roles.path2Index)
        assertEquals(1, roles.uidIndex)
    }
}
