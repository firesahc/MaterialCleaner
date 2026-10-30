package me.gm.cleaner.runtime.mediaprovider.hook.policy

/** 只读规则路径判定：规则自身及任意深度的子孙路径。 */
internal object ReadOnlyPathMatcher {

    /** 合并重复分隔符并移除尾部分隔符；不访问文件系统或解析软链。 */
    internal fun normalize(path: String): String {
        if (path.isEmpty()) return ""
        val absolute = path.startsWith('/')
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return if (absolute) "/" else ""
        return (if (absolute) "/" else "") + parts.joinToString("/")
    }

    internal fun matches(rulePath: String, path: String): Boolean {
        val rule = normalize(rulePath)
        val target = normalize(path)
        if (rule.isEmpty() || target.isEmpty()) return false
        if (target.equals(rule)) return true
        val prefix = if (rule.endsWith('/')) rule else "$rule/"
        return target.length > prefix.length && target.startsWith(prefix)
    }

    internal fun matchesAny(rulePaths: Collection<String>, path: String): Boolean =
        rulePaths.any { rulePath -> matches(rulePath, path) }
}
