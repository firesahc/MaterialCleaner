package me.gm.cleaner.runtime.mediaprovider.hook

import org.json.JSONObject

object NativeHookStatus {
    @Volatile
    private var mediaProviderHookLoaded = false
    @Volatile
    private var mediaProviderPackageName = ""
    @Volatile
    private var policyCacheInitialized = false
    @Volatile
    private var policyCacheInitializedAt = 0L
    @Volatile
    private var inlineLibraryLoaded = false
    @Volatile
    private var inlineHookInitialized = false
    @Volatile
    private var inlineHookStatusJson = ""
    @Volatile
    private var lastInlineError = ""
    @Volatile
    private var lastMountPointsApplySuccess = false
    @Volatile
    private var lastMountPointsApplyAt = 0L
    @Volatile
    private var lastMountPointsApplyGeneration = 0L
    @Volatile
    private var lastMountPointsApplyCount = 0
    @Volatile
    private var lastMountPointsApplyError = ""

    fun markMediaProviderHookLoaded(packageName: String) {
        mediaProviderHookLoaded = true
        mediaProviderPackageName = packageName
    }

    fun markPolicyCacheInitialized() {
        policyCacheInitialized = true
        policyCacheInitializedAt = System.currentTimeMillis()
    }

    fun markInlineLoadSucceeded(statusJson: String) {
        inlineLibraryLoaded = true
        inlineHookInitialized = true
        inlineHookStatusJson = statusJson
        lastInlineError = ""
    }

    fun markInlineLoadFailed(error: Throwable) {
        inlineLibraryLoaded = false
        inlineHookInitialized = false
        inlineHookStatusJson = ""
        lastInlineError = describe(error)
    }

    fun markMountPointsApplySucceeded(generation: Long, count: Int) {
        lastMountPointsApplySuccess = true
        lastMountPointsApplyAt = System.currentTimeMillis()
        lastMountPointsApplyGeneration = generation
        lastMountPointsApplyCount = count
        lastMountPointsApplyError = ""
    }

    fun markMountPointsApplyFailed(generation: Long, count: Int, error: Throwable) {
        lastMountPointsApplySuccess = false
        lastMountPointsApplyAt = System.currentTimeMillis()
        lastMountPointsApplyGeneration = generation
        lastMountPointsApplyCount = count
        lastMountPointsApplyError = describe(error)
    }

    fun toJson(mountPointsGeneration: Long): String {
        val root = JSONObject()
        root.put("mediaProviderHookLoaded", mediaProviderHookLoaded)
        root.put("mediaProviderPackageName", mediaProviderPackageName)
        root.put("policyCacheInitialized", policyCacheInitialized)
        root.put("policyCacheInitializedAt", policyCacheInitializedAt)
        root.put("inlineLibraryLoaded", inlineLibraryLoaded)
        root.put("inlineHookInitialized", inlineHookInitialized)
        root.put("lastInlineError", lastInlineError)
        root.put("mountPointsGeneration", mountPointsGeneration)
        root.put("lastMountPointsApplySuccess", lastMountPointsApplySuccess)
        root.put("lastMountPointsApplyAt", lastMountPointsApplyAt)
        root.put("lastMountPointsApplyGeneration", lastMountPointsApplyGeneration)
        root.put("lastMountPointsApplyCount", lastMountPointsApplyCount)
        root.put("lastMountPointsApplyError", lastMountPointsApplyError)
        if (inlineHookStatusJson.isNotBlank()) {
            runCatching {
                root.put("native", JSONObject(inlineHookStatusJson))
            }.onFailure {
                root.put("nativeStatusParseError", describe(it))
            }
        }
        return root.toString()
    }

    private fun describe(error: Throwable): String {
        val message = error.message?.takeIf { it.isNotBlank() }
        return if (message == null) error.javaClass.name else "${error.javaClass.name}: $message"
    }
}
