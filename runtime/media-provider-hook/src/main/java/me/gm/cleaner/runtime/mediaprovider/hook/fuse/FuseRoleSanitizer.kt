package me.gm.cleaner.runtime.mediaprovider.hook.fuse

/**
 * FUSE 角色下标清洗纯核心。
 *
 * 零 Android / Xposed 依赖，可跑在纯 JVM 单测中；
 * [FuseJavaGate][sanitizeRoles] 只是包了一层日志与 ParamRoles 组装后委托到这里。
 *
 * @return `intArrayOf(path, path2, uid, mode)`（噪声钳制为 -1），path 自身非法时返回 null。
 * UID 非法只钳为 -1：调用方随后会跳过依赖 UID 的 Hook，不作运行时猜测。
 * mode 非法只钳为 -1：仍可做重定向，但不再做只读判定（fail-open）。
 * 静态方法不在此判断——handler 异常由 GuardedHook 隔离，基线已证明安全。
 */
internal fun sanitizeRoleIndices(
    pathIndex: Int,
    path2Index: Int,
    uidIndex: Int,
    modeIndex: Int,
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
    // -1 means no verified UID signature mapping. It stays unresolved and is gated by FuseJavaGate.
    var uid = uidIndex
    if (uid >= 0 && (uid >= types.size || uid == pathIndex || uid == path2
                || (types[uid] != Int::class.javaPrimitiveType && types[uid] != Integer::class.java))) {
        uid = -1
    }
    var mode = modeIndex
    if (mode >= 0 && (mode >= types.size || mode == pathIndex || mode == path2 || mode == uid
                || (types[mode] != Int::class.javaPrimitiveType && types[mode] != Integer::class.java))) {
        mode = -1
    }
    return intArrayOf(pathIndex, path2, uid, mode)
}
