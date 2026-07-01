package me.gm.cleaner.server.orchestrator

/**
 * 系统五层架构标识。
 *
 * 每一层承担独立的职责边界：
 * - [VFS]：mount namespace + bind mount 文件系统级重定向（主机制）
 * - [MEDIA_PROVIDER_JAVA_HOOK]：MediaProvider Java 层 Hook（_data 列替换、FUSE Java gate、只读检查）
 * - [FUSE_NATIVE_HOOK]：libfuse_jni.so PLT/GOT Hook（containsMount、StartsWith、bpf_install 等）
 * - [DATA_BUS]：文件系统数据总线（快照发布、事件队列、信号通知）
 * - [CONTROL_PLANE]：控制面（App Binder 通信、规则下发、状态查询）
 */
enum class LayerId {
    VFS,
    MEDIA_PROVIDER_JAVA_HOOK,
    FUSE_NATIVE_HOOK,
    DATA_BUS,
    CONTROL_PLANE,
}
