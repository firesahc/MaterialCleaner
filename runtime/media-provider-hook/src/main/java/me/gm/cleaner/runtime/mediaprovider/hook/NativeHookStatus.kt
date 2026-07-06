package me.gm.cleaner.runtime.mediaprovider.hook

import android.util.Log
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import org.json.JSONObject

object NativeHookStatus {
    private const val TAG = "NativeHookStatus"

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
    private var mountPointsGeneration = 0L
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
        publishSnapshot()
    }

    fun markPolicyCacheInitialized() {
        policyCacheInitialized = true
        policyCacheInitializedAt = System.currentTimeMillis()
        publishSnapshot()
    }

    fun markInlineLoadSucceeded(statusJson: String) {
        inlineLibraryLoaded = true
        inlineHookInitialized = true
        inlineHookStatusJson = statusJson
        lastInlineError = ""
        publishSnapshot()
    }

    fun markInlineLoadFailed(error: Throwable) {
        inlineLibraryLoaded = false
        inlineHookInitialized = false
        inlineHookStatusJson = ""
        lastInlineError = describe(error)
        publishSnapshot()
    }

    fun markMountPointsApplySucceeded(generation: Long, count: Int) {
        lastMountPointsApplySuccess = true
        mountPointsGeneration = generation
        lastMountPointsApplyAt = System.currentTimeMillis()
        lastMountPointsApplyGeneration = generation
        lastMountPointsApplyCount = count
        lastMountPointsApplyError = ""
        publishSnapshot()
    }

    fun markMountPointsApplyFailed(generation: Long, count: Int, error: Throwable) {
        lastMountPointsApplySuccess = false
        mountPointsGeneration = generation
        lastMountPointsApplyAt = System.currentTimeMillis()
        lastMountPointsApplyGeneration = generation
        lastMountPointsApplyCount = count
        lastMountPointsApplyError = describe(error)
        publishSnapshot()
    }

    fun publishSnapshot() {
        runCatching {
            val json = toJson(mountPointsGeneration)
            if (HookDataBusBridge.writeSnapshot(DataBus.SNAPSHOT_NATIVE_HOOK_STATUS, json)) {
                HookDataBusBridge.signal(DataBus.SIGNAL_NATIVE_HOOK_STATUS_CHANGED)
            }
        }.onFailure {
            Log.w(TAG, "publishSnapshot failed", it)
        }
    }

    fun toJson(mountPointsGeneration: Long = this.mountPointsGeneration): String {
        val root = JSONObject()
        root.put("schemaVersion", 1)
        root.put("createdAt", System.currentTimeMillis())
        root.put("publisher", "NativeHookStatus")
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
