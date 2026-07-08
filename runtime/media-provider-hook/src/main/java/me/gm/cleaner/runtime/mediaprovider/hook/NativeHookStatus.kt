package me.gm.cleaner.runtime.mediaprovider.hook

import android.util.Log
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import org.json.JSONArray
import org.json.JSONObject

object NativeHookStatus {
    private const val TAG = "NativeHookStatus"
    private const val SCHEMA_VERSION = 2

    private const val STATE_NOT_LOADED = "NOT_LOADED"
    private const val STATE_INLINE_LOADED = "INLINE_LOADED"
    private const val STATE_FUSE_WAITING = "FUSE_WAITING"
    private const val STATE_HOOK_READY_FULL = "HOOK_READY_FULL"
    private const val STATE_HOOK_READY_CORE = "HOOK_READY_CORE"
    private const val STATE_HOOK_DEGRADED = "HOOK_DEGRADED"
    private const val STATE_HOOK_UNAVAILABLE = "HOOK_UNAVAILABLE"
    private const val STATE_DISABLED = "DISABLED"

    @Volatile
    private var mediaProviderHookLoaded = false
    @Volatile
    private var mediaProviderPackageName = ""
    @Volatile
    private var policyCacheInitialized = false
    @Volatile
    private var policyCacheInitializedAt = 0L

    @Volatile
    private var inlineState = STATE_NOT_LOADED
    @Volatile
    private var inlineLibraryLoaded = false
    @Volatile
    private var inlineHookInitialized = false
    @Volatile
    private var inlineRetryCount = 0
    @Volatile
    private var inlineNextRetryAt = 0L
    @Volatile
    private var inlineRetryExhausted = false
    @Volatile
    private var inlineDisabledByPlatform = false
    @Volatile
    private var lastInlineError = ""

    @Volatile
    private var nativeStatus = NativeStatusSnapshot()

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

    @Volatile
    private var fuseJavaGateStatus = FuseJavaGateStatus()

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
        val parsed = parseNativeStatus(statusJson)
        nativeStatus = parsed
        inlineLibraryLoaded = true
        inlineHookInitialized = parsed.coreAvailable
        lastInlineError = parsed.lastError
        inlineState = deriveInlineState(parsed)
        inlineDisabledByPlatform = false
        if (inlineState == STATE_DISABLED) {
            inlineRetryExhausted = true
            inlineNextRetryAt = 0L
        }
        if (parsed.coreAvailable) {
            inlineRetryCount = 0
            inlineNextRetryAt = 0L
            inlineRetryExhausted = false
        }
        publishSnapshot()
    }

    fun markInlineLoadFailed(error: Throwable) {
        inlineLibraryLoaded = false
        inlineHookInitialized = false
        nativeStatus = NativeStatusSnapshot(lastError = describe(error))
        lastInlineError = describe(error)
        inlineState = STATE_NOT_LOADED
        inlineDisabledByPlatform = false
        publishSnapshot()
    }

    fun markInlineRetryScheduled(retryCount: Int, nextRetryAt: Long) {
        inlineRetryCount = retryCount
        inlineNextRetryAt = nextRetryAt
        inlineRetryExhausted = false
        if (inlineState == STATE_NOT_LOADED || inlineState == STATE_INLINE_LOADED) {
            inlineState = STATE_FUSE_WAITING
        }
        publishSnapshot()
    }

    fun markInlineRetryExhausted(error: String) {
        inlineRetryExhausted = true
        inlineNextRetryAt = 0L
        lastInlineError = error
        inlineState = STATE_HOOK_UNAVAILABLE
        inlineDisabledByPlatform = false
        publishSnapshot()
    }

    fun markInlineDisabled(
        reason: String,
        fuseAvailable: Boolean,
        fuseJniLoadMode: String = "UNKNOWN",
    ) {
        inlineLibraryLoaded = false
        inlineHookInitialized = false
        inlineRetryExhausted = true
        inlineNextRetryAt = 0L
        lastInlineError = reason
        inlineState = STATE_DISABLED
        inlineDisabledByPlatform = true
        nativeStatus = NativeStatusSnapshot(
            fuseAvailable = fuseAvailable,
            hookMode = "NONE",
            fuseJniLoadMode = fuseJniLoadMode,
            lastError = reason,
        )
        publishSnapshot()
    }

    fun resetInlineRetryState() {
        inlineRetryCount = 0
        inlineNextRetryAt = 0L
        inlineRetryExhausted = false
        publishSnapshot()
    }

    fun markInlinePlatformSupported() {
        if (inlineState != STATE_DISABLED) {
            return
        }
        inlineState = STATE_NOT_LOADED
        inlineLibraryLoaded = false
        inlineHookInitialized = false
        inlineRetryCount = 0
        inlineNextRetryAt = 0L
        inlineRetryExhausted = false
        inlineDisabledByPlatform = false
        lastInlineError = ""
        nativeStatus = NativeStatusSnapshot()
        publishSnapshot()
    }

    fun shouldRetryInlineInitialization(now: Long): Boolean {
        if (inlineRetryExhausted) return false
        if (inlineState == STATE_DISABLED) return false
        if (isInlinePolicyBridgeAvailable()) return false
        return inlineNextRetryAt <= 0L || now >= inlineNextRetryAt
    }

    fun currentInlineRetryCount(): Int = inlineRetryCount

    fun currentInlineState(): String = inlineState

    fun isInlineDisabled(): Boolean = inlineState == STATE_DISABLED

    fun isInlineDisabledByPlatform(): Boolean =
        inlineState == STATE_DISABLED && inlineDisabledByPlatform

    fun isInlinePolicyBridgeAvailable(): Boolean =
        inlineState == STATE_HOOK_READY_FULL ||
                inlineState == STATE_HOOK_READY_CORE ||
                inlineState == STATE_HOOK_DEGRADED

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
        lastMountPointsApplyAt = System.currentTimeMillis()
        lastMountPointsApplyGeneration = generation
        lastMountPointsApplyCount = count
        lastMountPointsApplyError = describe(error)
        publishSnapshot()
    }

    fun markFuseJavaGateScanned(
        discoveredCount: Int,
        hookedMethods: List<String>,
        unknownMethods: List<String>,
        failedMethods: List<String>,
    ) {
        fuseJavaGateStatus = FuseJavaGateStatus(
            discoveredCount = discoveredCount,
            hookedMethods = hookedMethods,
            unknownMethods = unknownMethods,
            failedMethods = failedMethods,
        )
        publishSnapshot()
    }

    fun publishSnapshot() {
        runCatching {
            val json = toJson()
            if (HookDataBusBridge.writeSnapshot(DataBus.SNAPSHOT_NATIVE_HOOK_STATUS, json)) {
                HookDataBusBridge.signal(DataBus.SIGNAL_NATIVE_HOOK_STATUS_CHANGED)
            }
        }.onFailure {
            Log.w(TAG, "publishSnapshot failed", it)
        }
    }

    fun toJson(): String {
        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("createdAt", System.currentTimeMillis())
            put("publisher", "NativeHookStatus")
            put("mediaProvider", JSONObject().apply {
                put("loaded", mediaProviderHookLoaded)
                put("packageName", mediaProviderPackageName)
            })
            put("policyCache", JSONObject().apply {
                put("initialized", policyCacheInitialized)
                put("initializedAt", policyCacheInitializedAt)
            })
            put("inline", JSONObject().apply {
                put("state", inlineState)
                put("loaded", inlineLibraryLoaded)
                put("initialized", inlineHookInitialized)
                put("retryCount", inlineRetryCount)
                put("nextRetryAt", inlineNextRetryAt)
                put("retryExhausted", inlineRetryExhausted)
                put("disabledByPlatform", inlineDisabledByPlatform)
                put("lastError", lastInlineError)
            })
            put("native", nativeStatus.toJson())
            put("policy", JSONObject().apply {
                put("mountPointsGeneration", mountPointsGeneration)
                put("lastApplySuccess", lastMountPointsApplySuccess)
                put("lastApplyAt", lastMountPointsApplyAt)
                put("lastApplyGeneration", lastMountPointsApplyGeneration)
                put("lastApplyCount", lastMountPointsApplyCount)
                put("lastApplyError", lastMountPointsApplyError)
            })
            put("fuseJavaGate", fuseJavaGateStatus.toJson())
        }.toString()
    }

    private fun deriveInlineState(status: NativeStatusSnapshot): String = when {
        !status.fuseAvailable -> STATE_DISABLED
        !status.fuseLibraryLoaded -> STATE_FUSE_WAITING
        status.fullAvailable -> STATE_HOOK_READY_FULL
        status.coreAvailable && status.startsWithHooked -> STATE_HOOK_READY_CORE
        status.coreAvailable -> STATE_HOOK_DEGRADED
        status.fuseLibraryLoaded -> STATE_HOOK_UNAVAILABLE
        inlineLibraryLoaded -> STATE_INLINE_LOADED
        else -> STATE_NOT_LOADED
    }

    private fun parseNativeStatus(json: String): NativeStatusSnapshot {
        return try {
            val root = JSONObject(json)
            val symbols = root.optJSONObject("symbols")
            val symbolMethods = root.optJSONObject("symbolMethods")
            NativeStatusSnapshot(
                fuseAvailable = root.optBoolean("fuseAvailable", true),
                fuseLibraryLoaded = root.optBoolean("fuseLibraryLoaded", false),
                fuseLibraryName = root.optString("fuseLibraryName", ""),
                hookMode = root.optString("hookMode", "UNKNOWN"),
                fuseJniLoadMode = root.optString("fuseJniLoadMode", "UNKNOWN"),
                embeddedFuseJniFound = root.optBoolean("embeddedFuseJniFound", false),
                containsMountHooked = symbols?.optBoolean("containsMount", false) ?: false,
                startsWithHooked = symbols?.optBoolean("startsWith", false) ?: false,
                isFuseBpfEnabledHooked = symbols?.optBoolean("isFuseBpfEnabled", false) ?: false,
                fuseReqUserdataHooked = symbols?.optBoolean("fuseReqUserdata", false) ?: false,
                fuseBpfInstallHooked = symbols?.optBoolean("fuseBpfInstall", false) ?: false,
                containsMountMethod = symbolMethods?.optString("containsMount", "") ?: "",
                startsWithMethod = symbolMethods?.optString("startsWith", "") ?: "",
                isFuseBpfEnabledMethod = symbolMethods?.optString("isFuseBpfEnabled", "") ?: "",
                fuseReqUserdataMethod = symbolMethods?.optString("fuseReqUserdata", "") ?: "",
                fuseBpfInstallMethod = symbolMethods?.optString("fuseBpfInstall", "") ?: "",
                xhookRefreshCalled = root.optBoolean("xhookRefreshCalled", false),
                lastError = root.optString("lastError", ""),
            )
        } catch (e: Exception) {
            NativeStatusSnapshot(lastError = "Invalid native status: ${describe(e)}")
        }
    }

    private fun describe(error: Throwable): String {
        val message = error.message?.takeIf { it.isNotBlank() }
        return if (message == null) error.javaClass.name else "${error.javaClass.name}: $message"
    }

    private data class NativeStatusSnapshot(
        val fuseAvailable: Boolean = true,
        val fuseLibraryLoaded: Boolean = false,
        val fuseLibraryName: String = "",
        val hookMode: String = "UNKNOWN",
        val fuseJniLoadMode: String = "UNKNOWN",
        val embeddedFuseJniFound: Boolean = false,
        val containsMountHooked: Boolean = false,
        val startsWithHooked: Boolean = false,
        val isFuseBpfEnabledHooked: Boolean = false,
        val fuseReqUserdataHooked: Boolean = false,
        val fuseBpfInstallHooked: Boolean = false,
        val containsMountMethod: String = "",
        val startsWithMethod: String = "",
        val isFuseBpfEnabledMethod: String = "",
        val fuseReqUserdataMethod: String = "",
        val fuseBpfInstallMethod: String = "",
        val xhookRefreshCalled: Boolean = false,
        val lastError: String = "",
    ) {
        val coreAvailable: Boolean
            get() = containsMountHooked

        val fullAvailable: Boolean
            get() = containsMountHooked &&
                    startsWithHooked &&
                    isFuseBpfEnabledHooked &&
                    fuseReqUserdataHooked &&
                    fuseBpfInstallHooked

        private val missingSymbols: List<String>
            get() = buildList {
                if (!fuseLibraryLoaded) return@buildList
                if (!containsMountHooked) add("containsMount")
                if (!startsWithHooked) add("startsWith")
                if (!isFuseBpfEnabledHooked) add("isFuseBpfEnabled")
                if (!fuseReqUserdataHooked) add("fuseReqUserdata")
                if (!fuseBpfInstallHooked) add("fuseBpfInstall")
            }

        fun toJson(): JSONObject = JSONObject().apply {
            put("fuseAvailable", fuseAvailable)
            put("fuseLibraryLoaded", fuseLibraryLoaded)
            put("fuseLibraryName", fuseLibraryName)
            put("hookMode", hookMode)
            put("fuseJniLoadMode", fuseJniLoadMode)
            put("embeddedFuseJniFound", embeddedFuseJniFound)
            put("xhookRefreshCalled", xhookRefreshCalled)
            put("coreAvailable", coreAvailable)
            put("fullAvailable", fullAvailable)
            put("symbols", JSONObject().apply {
                put("containsMount", containsMountHooked)
                put("startsWith", startsWithHooked)
                put("isFuseBpfEnabled", isFuseBpfEnabledHooked)
                put("fuseReqUserdata", fuseReqUserdataHooked)
                put("fuseBpfInstall", fuseBpfInstallHooked)
            })
            put("symbolMethods", JSONObject().apply {
                put("containsMount", containsMountMethod)
                put("startsWith", startsWithMethod)
                put("isFuseBpfEnabled", isFuseBpfEnabledMethod)
                put("fuseReqUserdata", fuseReqUserdataMethod)
                put("fuseBpfInstall", fuseBpfInstallMethod)
            })
            put("missingSymbols", JSONArray(missingSymbols))
            put("lastError", lastError)
        }
    }

    private data class FuseJavaGateStatus(
        val discoveredCount: Int = 0,
        val hookedMethods: List<String> = emptyList(),
        val unknownMethods: List<String> = emptyList(),
        val failedMethods: List<String> = emptyList(),
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("discoveredCount", discoveredCount)
            put("hookedCount", hookedMethods.size)
            put("unknownCount", unknownMethods.size)
            put("failedCount", failedMethods.size)
            put("hookedMethods", JSONArray(hookedMethods))
            put("unknownMethods", JSONArray(unknownMethods))
            put("failedMethods", JSONArray(failedMethods))
        }
    }
}
