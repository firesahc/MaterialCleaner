package me.gm.cleaner.runtime.server

import android.os.Build
import android.util.Log
import api.SystemService
import me.gm.cleaner.core.common.RuntimeSystemProperties
import me.gm.cleaner.core.storage.redirect.domain.PlatformCapabilities
import me.gm.cleaner.runtime.server.observer.Mounter
import java.io.File

/**
 * 平台能力探测器。
 *
 * 统一采集 Android 平台、厂商变体、FUSE/BPF/sdcardfs 等特性状态。
 * 替代散落在各处的 Build.VERSION.SDK_INT、HookSystemProperties、Mounter.isFuseBpfEnabled 判断。
 *
 * 使用方式：
 * ```
 * val caps = PlatformCapabilitiesDetector.detect()
 * SnapshotPublisher.publishPlatformCapabilities(caps)
 * ```
 */
object PlatformCapabilitiesDetector {
    private const val TAG = "PlatformCapabilitiesDetector"
    private const val FUSE_LOAD_MODE_SYSTEM_LIB = "SYSTEM_LIB"
    private const val FUSE_LOAD_MODE_APEX_APK_EMBEDDED = "APEX_APK_EMBEDDED"
    private const val FUSE_LOAD_MODE_UNKNOWN = "UNKNOWN"
    private const val HOOK_MODE_XHOOK = "XHOOK"
    private const val HOOK_MODE_EMBEDDED_GOT_PATCH = "EMBEDDED_GOT_PATCH"
    private const val HOOK_MODE_NONE = "NONE"
    private val MEDIA_PROVIDER_PACKAGE_CANDIDATES = arrayOf(
        "com.android.providers.media.module",
        "com.google.android.providers.media.module",
        "com.android.providers.media",
    )

    /** 单调递增代数计数器（替代 wall clock 避免 NTP 回拨等问题） */
    private val capsGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private var cachedGeneration: Long = 0L

    /**
     * 采集所有平台能力。
     *
     * @param mounter VFS Mounter 实例（用于获取 isFuseBpfEnabled）
     * @return 当前平台能力快照
     */
    fun detect(mounter: Mounter? = null): PlatformCapabilities {
        val gen = capsGeneration.incrementAndGet()
        cachedGeneration = gen
        val now = System.currentTimeMillis()

        val sdkInt = Build.VERSION.SDK_INT

        // FUSE 可用性：SDK >= 30 或 persist.sys.fuse=true
        val fuseAvailable = sdkInt >= Build.VERSION_CODES.R
                || RuntimeSystemProperties.getBoolean("persist.sys.fuse") == true

        // FUSE BPF 可用性：优先从 Mounter 获取（已实现复杂的多层探测）
        val isFuseBpfEnabled = mounter?.isFuseBpfEnabled
                ?: detectFuseBpfFallback()

        // sdcardfs：ro.sys.sdcardfs 属性
        val usesSdcardfs = RuntimeSystemProperties.getBoolean("ro.sys.sdcardfs") == true

        // HyperOS 变体：通过 ro.miui.ui.version.name 判断
        val hyperOsVariant = detectHyperOs()

        // Android/data 特殊处理：FUSE BPF 启用时需要
        val specialAndroidDataHandlingRequired = isFuseBpfEnabled

        val mediaProviderPackageName = detectMediaProviderPackageName()
        val systemFuseJniAvailable = detectSystemFuseJniAvailable()
        val fuseJniLoadMode = when {
            systemFuseJniAvailable -> FUSE_LOAD_MODE_SYSTEM_LIB
            fuseAvailable && mediaProviderPackageName.isNotBlank() -> FUSE_LOAD_MODE_APEX_APK_EMBEDDED
            else -> FUSE_LOAD_MODE_UNKNOWN
        }
        val supportedNativeHookMode = when (fuseJniLoadMode) {
            FUSE_LOAD_MODE_SYSTEM_LIB -> HOOK_MODE_XHOOK
            FUSE_LOAD_MODE_APEX_APK_EMBEDDED -> HOOK_MODE_EMBEDDED_GOT_PATCH
            else -> HOOK_MODE_NONE
        }
        // 旧字段保持原含义：仅表示系统分区 libfuse_jni.so 可用于 xhook。
        val xhookSymbolsAvailable = systemFuseJniAvailable
        val mediaProviderApiShape = detectMediaProviderApiShape(sdkInt)

        return PlatformCapabilities(
            schemaVersion = 1,
            generation = gen,
            createdAt = now,
            publisher = "PlatformCapabilitiesDetector",
            sdkVersionInt = sdkInt,
            isFuseBpfEnabled = isFuseBpfEnabled,
            fuseAvailable = fuseAvailable,
            usesSdcardfs = usesSdcardfs,
            hyperOsVariant = hyperOsVariant,
            specialAndroidDataHandlingRequired = specialAndroidDataHandlingRequired,
            xhookSymbolsAvailable = xhookSymbolsAvailable,
            mediaProviderPackageName = mediaProviderPackageName,
            systemFuseJniAvailable = systemFuseJniAvailable,
            fuseJniLoadMode = fuseJniLoadMode,
            supportedNativeHookMode = supportedNativeHookMode,
            mediaProviderApiShape = mediaProviderApiShape,
        )
    }

    /**
     * 获取当前缓存的 generation（用于判断快照是否需要重新发布）。
     */
    fun getCurrentGeneration(): Long = cachedGeneration

    // ── 私有探测方法 ──

    private fun detectFuseBpfFallback(): Boolean {
        var isEnabled = RuntimeSystemProperties.getBoolean("ro.fuse.bpf.is_running")
        if (isEnabled != null) return isEnabled
        isEnabled = RuntimeSystemProperties.getBoolean("persist.sys.fuse.bpf.override")
        if (isEnabled != null) return isEnabled
        isEnabled = RuntimeSystemProperties.getBoolean("ro.fuse.bpf.enabled")
        if (isEnabled != null) return isEnabled

        return try {
            val filename = "/sys/fs/fuse/features/fuse_bpf"
            java.nio.file.Path.of(filename).toFile().takeIf { it.exists() }
                ?.readText() == "supported\n"
        } catch (e: Exception) {
            false
        }
    }

    private fun detectHyperOs(): Boolean {
        val miuiVersion = RuntimeSystemProperties.get("ro.miui.ui.version.name", "")
        // HyperOS 基于 Android 14+，miui 版本号以 "OS" 开头（如 "OS1.0.2"）
        return miuiVersion.startsWith("OS", ignoreCase = true)
                || RuntimeSystemProperties.get("ro.build.version.miotg", "") == "1"
    }

    private fun detectSystemFuseJniAvailable(): Boolean {
        return try {
            val libPath = File("/system/lib64/libfuse_jni.so")
            if (libPath.exists()) return true
            val libPath32 = File("/system/lib/libfuse_jni.so")
            libPath32.exists()
        } catch (e: Exception) {
            false
        }
    }

    private fun detectMediaProviderPackageName(): String {
        return try {
            val userIds = SystemService.getUserIdsNoThrow().ifEmpty { listOf(0) }
            for (packageName in MEDIA_PROVIDER_PACKAGE_CANDIDATES) {
                if (userIds.any { userId ->
                        SystemService.getPackageInfoNoThrow(packageName, 0, userId) != null
                    }) {
                    return packageName
                }
            }
            ""
        } catch (e: Exception) {
            Log.w(TAG, "Failed to detect MediaProvider package", e)
            ""
        }
    }

    private fun detectMediaProviderApiShape(sdkInt: Int): String {
        return when {
            sdkInt >= 35 -> "ANDROID_15_OPEN_WITH_FUSE"
            sdkInt >= Build.VERSION_CODES.R -> "ANDROID_11_FUSE_DAEMON"
            else -> "LEGACY_MEDIA_PROVIDER"
        }
    }

    /**
     * 从 DataBus 读取的 JSON 解析 [PlatformCapabilities]。
     * 由 HookPolicyCache 在 MediaProvider 进程中使用。
     */
    fun fromJson(json: String): PlatformCapabilities? {
        return try {
            val root = org.json.JSONObject(json)
            PlatformCapabilities(
                schemaVersion = root.optInt("schemaVersion", 1),
                generation = root.optLong("generation", 0L),
                createdAt = root.optLong("createdAt", 0L),
                publisher = root.optString("publisher", ""),
                sdkVersionInt = root.optInt("sdkVersionInt", 0),
                isFuseBpfEnabled = root.optBoolean("isFuseBpfEnabled", false),
                fuseAvailable = root.optBoolean("fuseAvailable", false),
                usesSdcardfs = root.optBoolean("usesSdcardfs", false),
                hyperOsVariant = root.optBoolean("hyperOsVariant", false),
                specialAndroidDataHandlingRequired = root.optBoolean(
                    "specialAndroidDataHandlingRequired", false
                ),
                xhookSymbolsAvailable = root.optBoolean("xhookSymbolsAvailable", false),
                mediaProviderPackageName = root.optString("mediaProviderPackageName", ""),
                systemFuseJniAvailable = root.optBoolean("systemFuseJniAvailable", false),
                fuseJniLoadMode = root.optString("fuseJniLoadMode", FUSE_LOAD_MODE_UNKNOWN),
                supportedNativeHookMode = root.optString("supportedNativeHookMode", HOOK_MODE_NONE),
                mediaProviderApiShape = root.optString("mediaProviderApiShape", "UNKNOWN"),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse PlatformCapabilities from JSON", e)
            null
        }
    }

    /**
     * 序列化 [PlatformCapabilities] 为 JSON。
     * 由 SnapshotPublisher 使用。
     */
    fun toJson(caps: PlatformCapabilities): String {
        val root = org.json.JSONObject()
        root.put("schemaVersion", caps.schemaVersion)
        root.put("generation", caps.generation)
        root.put("createdAt", caps.createdAt)
        root.put("publisher", caps.publisher)
        root.put("sdkVersionInt", caps.sdkVersionInt)
        root.put("isFuseBpfEnabled", caps.isFuseBpfEnabled)
        root.put("fuseAvailable", caps.fuseAvailable)
        root.put("usesSdcardfs", caps.usesSdcardfs)
        root.put("hyperOsVariant", caps.hyperOsVariant)
        root.put("specialAndroidDataHandlingRequired", caps.specialAndroidDataHandlingRequired)
        root.put("xhookSymbolsAvailable", caps.xhookSymbolsAvailable)
        root.put("mediaProviderPackageName", caps.mediaProviderPackageName)
        root.put("systemFuseJniAvailable", caps.systemFuseJniAvailable)
        root.put("fuseJniLoadMode", caps.fuseJniLoadMode)
        root.put("supportedNativeHookMode", caps.supportedNativeHookMode)
        root.put("mediaProviderApiShape", caps.mediaProviderApiShape)
        return root.toString(2)
    }
}
