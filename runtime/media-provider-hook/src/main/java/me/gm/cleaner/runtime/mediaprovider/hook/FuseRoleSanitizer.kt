package me.gm.cleaner.runtime.mediaprovider.hook

/**
 * FUSE 角色下标清洗纯核心。
 *
 * 零 Android / Xposed 依赖，可跑在纯 JVM 单测中；
 * [FuseJavaGate][sanitizeRoles] 只是包了一层日志与 ParamRoles 组装后委托到这里。
 *
 * @return `intArrayOf(path, path2, uid)`（噪声钳制为 -1），path 自身非法时返回 null。
 * 静态方法不在此判断——handler 异常由 GuardedHook 隔离，基线已证明安全。
 */
internal fun sanitizeRoleIndices(
    pathIndex: Int,
    path2Index: Int,
    uidIndex: Int,
    types: Array<Class<*>>,
): IntArray? {
    if (pathIndex < 0 || pathIndex >= types.size) {
        return null
    }
    if (types[pathIndex] != String::class.java) {
        return null
    }
    var path2 = path2Index
    if (path2 >= 0 && (path2 >= types.size || path2 == pathIndex
                || types[path2] != String::class.java)) {
        path2 = -1
    }
    var uid = uidIndex
    if (uid >= 0 && (uid >= types.size || uid == pathIndex || uid == path2
                || (types[uid] != Int::class.javaPrimitiveType && types[uid] != Integer::class.java))) {
        uid = -1
    }
    return intArrayOf(pathIndex, path2, uid)
}
