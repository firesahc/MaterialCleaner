package me.gm.cleaner.runtime.mediaprovider.hook.fuse

/**
 * 安装需要调用者 UID 的 FUSE Hook 前做签名门控。
 * 未核验出 UID 参数时返回原因，调用方跳过安装并计入部分覆盖诊断。
 */
internal fun uidResolutionSkipReason(uidIndex: Int, parameterTypes: Array<Class<*>>): String? {
    if (uidIndex < 0) return "uid_signature_unresolved"
    if (uidIndex >= parameterTypes.size) return "uid_index_out_of_range"
    val type = parameterTypes[uidIndex]
    if (type != Int::class.javaPrimitiveType && type != Integer::class.java) {
        return "uid_parameter_not_integer"
    }
    return null
}
