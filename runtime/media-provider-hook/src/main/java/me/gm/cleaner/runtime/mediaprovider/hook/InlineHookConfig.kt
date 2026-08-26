package me.gm.cleaner.runtime.mediaprovider.hook

object InlineHookConfig {

    /**
     * 单次 JNI 调用原子应用策略两维度（挂载点集合 + 记录偏好），
     * 消除分次调用间"F 新挂载点配旧偏好"的不一致窗口。
     */
    private external fun a(value: Array<String>, record: Boolean)

    fun commitPolicy(mountPoints: Array<String>, recordExternalAppSpecificStorage: Boolean) =
        a(mountPoints, recordExternalAppSpecificStorage)

    private external fun init(): String

    fun initializeXHook(): String = init()
}
