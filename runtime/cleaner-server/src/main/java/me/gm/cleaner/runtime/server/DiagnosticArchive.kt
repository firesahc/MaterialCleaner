package me.gm.cleaner.runtime.server

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import me.gm.cleaner.core.storage.redirect.databus.DataBusProtocol
import me.gm.cleaner.runtime.server.orchestrator.MediaProviderHookLayerReporter
import me.gm.cleaner.runtime.server.orchestrator.NativeHookLayerReporter
import me.gm.cleaner.runtime.server.orchestrator.ServerErrorJournal
import me.gm.cleaner.runtime.server.vfs.VfsProcessCensus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DiagnosticArchive {
    private const val TAG = "DiagnosticArchive"
    private const val OUTPUT_DIR = "/data/local/tmp/cleaner_diagnostics"
    private const val AUTO_LOG_DIR = "/data/local/tmp/cleaner_logs"
    private const val MAX_AUTO_LOG_FILES = 8
    private const val MAX_EVENT_FILES = 20

    fun open(server: CleanerServer): ParcelFileDescriptor {
        val file = create(server)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun create(server: CleanerServer): File {
        val outDir = prepareOutputDir()
        cleanupOldArchives(outDir)

        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val archive = createArchiveFile(outDir, timestamp)
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            addText(zip, "privacy.txt", privacyNotice())
            addText(zip, "manifest.txt", buildManifest(server))
            addText(zip, "summary.txt", buildSummary(server))
            addText(zip, "summary_zh-CN.txt", buildSummaryZhCn(server))
            addStatus(zip, server)
            addErrorJournal(zip)
            addDataBus(zip)
            addLogcat(zip)
            addAutoLogs(zip)
            addCommandOutputs(zip)
        }
        setOwnerOnly(archive, executable = false)
        Log.i("MC_DIAG", JSONObject().apply {
            put("event", "diagnostics_archive_created")
            put("path", archive.path)
            put("sizeBytes", archive.length())
            put("redacted", true)
            put("summary", true)
        }.toString())
        return archive
    }

    private fun prepareOutputDir(): File {
        val outDir = File(OUTPUT_DIR)
        val path = outDir.toPath()
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
            (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
        ) {
            Files.delete(path)
        }
        Files.createDirectories(path)
        setOwnerOnly(outDir, executable = true)
        return outDir
    }

    private fun createArchiveFile(outDir: File, timestamp: String): File {
        val archive = Files.createTempFile(
            outDir.toPath(),
            "material-cleaner-diagnostics-$timestamp-",
            ".zip"
        ).toFile()
        setOwnerOnly(archive, executable = false)
        return archive
    }

    private fun setOwnerOnly(file: File, executable: Boolean) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
        if (executable) {
            file.setExecutable(true, true)
        }
    }

    private fun privacyNotice(): String = buildString {
        appendLine("This diagnostics package is intended for troubleshooting Material Cleaner.")
        appendLine("It may include device details, process and mount state, logcat output,")
        appendLine("DataBus snapshots, redirect rules, and file paths.")
        appendLine("Basic identifiers such as build fingerprint, APK source paths,")
        appendLine("and long hex-like tokens are redacted before export.")
        appendLine("File paths and package names are preserved because they are required")
        appendLine("to diagnose storage redirection issues.")
    }

    private fun buildManifest(server: CleanerServer): String = buildString {
        appendLine("createdAt=${System.currentTimeMillis()}")
        appendLine("redacted=true")
        appendLine("serverPid=${Os.getpid()}")
        appendLine("serverUid=${Os.getuid()}")
        appendLine("versionCode=${BuildConfig.VERSION_CODE}")
        appendLine("packageName=${server.packageInfo.packageName}")
        appendLine("sourceDir=${server.packageInfo.applicationInfo.sourceDir}")
        appendLine("sdk=${Build.VERSION.SDK_INT}")
        appendLine("release=${Build.VERSION.RELEASE}")
        appendLine("manufacturer=${Build.MANUFACTURER}")
        appendLine("brand=${Build.BRAND}")
        appendLine("model=${Build.MODEL}")
        appendLine("device=${Build.DEVICE}")
        appendLine("fingerprint=${Build.FINGERPRINT}")
    }

    private fun buildSummary(server: CleanerServer): String = buildString {
        appendLine("Material Cleaner diagnostics summary")
        appendLine("Generated at: ${System.currentTimeMillis()}")
        appendLine()
        appendLine("Read this first:")
        appendLine("- status/orchestrated_status.json has the full five-layer state.")
        appendLine("- status/native_hook_status_pretty.json has MediaProvider/FUSE hook details.")
        appendLine("- databus/health.json has queue, snapshot, and permission health.")
        appendLine("- logs/logcat_material_cleaner_filtered.txt has focused runtime logs.")
        appendLine("- commands/media_provider_maps.txt and commands/media_provider_mountinfo.txt prove native loading and mount state.")
        appendLine("- commands/dmesg_evidence.txt has kernel-side denials (SELinux avc, FUSE, mount, namespace).")
        appendLine("- logs/logcat_crash_recent.txt and logs/logcat_media_provider_pid.txt cover crash and hook-host evidence.")
        appendLine()

        val status = runCatching { JSONObject(server.layerOrchestrator.collectStatusJson()) }
            .onFailure {
                appendLine("Runtime status: unavailable (${it.javaClass.name}: ${it.message})")
            }
            .getOrNull()
        if (status != null) {
            appendLine("Runtime health: ${status.optString("health", "UNKNOWN")}")
            appendLayerSummary(status, "vfs", "VFS")
            appendLayerSummary(status, "mediaProviderJavaHook", "MediaProvider Java Hook")
            appendLayerSummary(status, "fuseNativeHook", "FUSE Native Hook")
            appendLayerSummary(status, "dataBus", "DataBus")
            appendLayerSummary(status, "controlPlane", "Control Plane")
            appendLine()
        }

        val health = runCatching { DataBus.checkHealth(repair = true) }.getOrNull()
        if (health != null) {
            appendLine("DataBus:")
            appendLine("- initialized=${health.initialized}, healthy=${health.healthy}, criticalSnapshotsReady=${health.criticalSnapshotsReady}")
            appendLine("- eventQueueCounts=${health.eventQueueCounts} (physical, includes consumed-but-retained)")
            appendLine("- pendingEventCounts=${health.pendingEventCounts} (drives backlog alerts)")
            appendLine("- quarantineCounts=${health.quarantineCounts}")
            appendLine("- cursorReadStates=${health.cursorReadStates} (UNREADABLE queues excluded from backlog)")
            appendLine("- leaseCounts=${health.leaseCounts}")
            appendLine("- missingDirectories=${health.missingDirectories.size}, permissionIssues=${health.permissionIssues.size}")
            appendLine()
        }

        val nativeStatus = DataBus.readSnapshotSafe(DataBusProtocol.SNAPSHOT_NATIVE_HOOK_STATUS)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (nativeStatus != null) {
            appendNativeHookSummary(nativeStatus)
            appendLine()
        }

        val platformCaps = DataBus.readSnapshotSafe(DataBusProtocol.SNAPSHOT_PLATFORM_CAPABILITIES)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (platformCaps != null) {
            appendLine("Platform capabilities:")
            appendLine("- sdk=${platformCaps.optInt("sdkVersionInt", 0)}, fuse=${platformCaps.optBoolean("fuseAvailable", false)}, fuseBpf=${platformCaps.optBoolean("isFuseBpfEnabled", false)}")
            appendLine("- mediaProvider=${platformCaps.optString("mediaProviderPackageName", "")}")
            appendLine("- fuseJniLoadMode=${platformCaps.optString("fuseJniLoadMode", "UNKNOWN")}, nativeHookMode=${platformCaps.optString("supportedNativeHookMode", "UNKNOWN")}")
            appendLine()
        }

        if (status != null) {
            appendLine("Key signals:")
            appendKeySignals(status)
            appendLine()
        }

        appendLine("If the package is hard to read, start from this file, then open the referenced files above.")
    }

    /**
     * P1 诊断质量：陈列 status JSON 中的关键信号。
     *
     * 职责边界：本函数只**陈列事实**，不做状态裁决——裁决归 Reporter
     * （[NativeHookLayerReporter] / [MediaProviderHookLayerReporter]），
     * 此处直接展示其结论与证据字段。自造判定词会与运行状态分叉，
     * 正是历史上"摘要显示 synced 而实际 STALE"的成因。
     */
    internal fun StringBuilder.appendKeySignals(status: JSONObject) {
        val vfs = status.optJSONObject("vfs")
        val mp = status.optJSONObject("mediaProviderJavaHook")
        val fuse = status.optJSONObject("fuseNativeHook")
        if (vfs == null || mp == null || fuse == null) return
        // FUSE：展示 Reporter 的权威裁决 + 支撑证据，不再自行推导 synced/pending
        val appliedEpoch = fuse.optString(NativeHookLayerReporter.KEY_APPLIED_EPOCH, "")
        val snapshotEpoch = fuse.optString(NativeHookLayerReporter.KEY_SNAPSHOT_EPOCH, "")
        val policySynced = fuse.optBoolean(NativeHookLayerReporter.KEY_POLICY_SYNCED, false)
        val verdict = fuse.optString(NativeHookLayerReporter.KEY_SYNC_VERDICT, "UNKNOWN")
        val layerState = fuse.optString("state", "UNKNOWN")
        if (appliedEpoch.isNotBlank() && snapshotEpoch.isNotBlank()) {
            val epoch = if (appliedEpoch == snapshotEpoch) "consistent" else "MISMATCH"
            appendLine("- FUSE: state=$layerState, sync=$verdict, " +
                    "epoch=$epoch, policySynced=$policySynced")
        }
        // 恢复熔断：展示 Reporter 结论与计数（attempts 语义）
        val wakeOnly = mp.optBoolean(MediaProviderHookLayerReporter.KEY_WAKE_ONLY_MODE, false)
        val rounds = mp.optInt(MediaProviderHookLayerReporter.KEY_DESTRUCTIVE_ROUNDS, 0)
        if (wakeOnly || rounds > 0) {
            appendLine("- MediaProvider recovery: state=${mp.optString("state", "UNKNOWN")}, " +
                    "wakeOnly=$wakeOnly, destructiveAttempts=$rounds/3")
        }
        // VFS 分母
        val unmanaged = vfs.optInt(VfsProcessCensus.KEY_UNMANAGED, -1)
        val managed = vfs.optInt(VfsProcessCensus.KEY_MANAGED, -1)
        if (unmanaged >= 0 && managed >= 0) {
            appendLine("- VFS pids: managed=$managed, unmanaged=$unmanaged")
        }
        // srStatus 截断
        val truncated = vfs.optBoolean(VfsProcessCensus.KEY_SR_TRUNCATED, false)
        val total = vfs.optInt(VfsProcessCensus.KEY_SR_TOTAL, -1)
        if (total >= 0) {
            appendLine("- srStatus: total=$total, truncated=$truncated")
        }
    }

    /** 中文概览：同样只陈列 Reporter 结论，不自行裁决。 */
    internal fun StringBuilder.appendKeySignalsZhCn(status: JSONObject) {
        val vfs = status.optJSONObject("vfs")
        val mp = status.optJSONObject("mediaProviderJavaHook")
        val fuse = status.optJSONObject("fuseNativeHook")
        if (vfs == null || mp == null || fuse == null) return
        val appliedEpoch = fuse.optString(NativeHookLayerReporter.KEY_APPLIED_EPOCH, "")
        val snapshotEpoch = fuse.optString(NativeHookLayerReporter.KEY_SNAPSHOT_EPOCH, "")
        val policySynced = fuse.optBoolean(NativeHookLayerReporter.KEY_POLICY_SYNCED, false)
        val verdict = fuse.optString(NativeHookLayerReporter.KEY_SYNC_VERDICT, "UNKNOWN")
        val layerState = fuse.optString("state", "UNKNOWN")
        if (appliedEpoch.isNotBlank() && snapshotEpoch.isNotBlank()) {
            val epoch = if (appliedEpoch == snapshotEpoch) "一致" else "不一致"
            appendLine("- FUSE：状态=$layerState，同步=$verdict，代次=$epoch，策略同步=$policySynced")
        }
        // 恢复熔断（attempts 语义）
        val wakeOnly = mp.optBoolean(MediaProviderHookLayerReporter.KEY_WAKE_ONLY_MODE, false)
        val rounds = mp.optInt(MediaProviderHookLayerReporter.KEY_DESTRUCTIVE_ROUNDS, 0)
        if (wakeOnly || rounds > 0) {
            appendLine("- MediaProvider 恢复：状态=${mp.optString("state", "UNKNOWN")}，" +
                    "仅唤醒=$wakeOnly，破坏性尝试=$rounds/3")
        }
        // VFS 分母
        val unmanaged = vfs.optInt(VfsProcessCensus.KEY_UNMANAGED, -1)
        val managed = vfs.optInt(VfsProcessCensus.KEY_MANAGED, -1)
        if (unmanaged >= 0 && managed >= 0) {
            appendLine("- VFS 进程：已管理=$managed，未接管=$unmanaged")
        }
        // srStatus 截断
        val truncated = vfs.optBoolean(VfsProcessCensus.KEY_SR_TRUNCATED, false)
        val total = vfs.optInt(VfsProcessCensus.KEY_SR_TOTAL, -1)
        if (total >= 0) {
            appendLine("- srStatus：总数=$total，已截断=$truncated")
        }
    }

    private fun buildSummaryZhCn(server: CleanerServer): String = buildString {
        appendLine("Material Cleaner 诊断包概览")
        appendLine("生成时间：${System.currentTimeMillis()}")
        appendLine()
        appendLine("优先阅读：")
        appendLine("- status/orchestrated_status.json：完整五层运行状态。")
        appendLine("- status/native_hook_status_pretty.json：MediaProvider/FUSE Hook 详情。")
        appendLine("- databus/health.json：队列、快照和权限健康状态。")
        appendLine("- logs/logcat_material_cleaner_filtered.txt：聚焦运行日志。")
        appendLine("- commands/media_provider_maps.txt 与 commands/media_provider_mountinfo.txt：native 加载与挂载状态证据。")
        appendLine("- commands/dmesg_evidence.txt：内核侧拒绝证据（SELinux avc、FUSE、mount、namespace）。")
        appendLine("- logs/logcat_crash_recent.txt 与 logs/logcat_media_provider_pid.txt：崩溃与 Hook 宿主进程证据。")
        appendLine()

        val status = runCatching { JSONObject(server.layerOrchestrator.collectStatusJson()) }
            .onFailure {
                appendLine("运行状态：不可用（${it.javaClass.name}: ${it.message}）")
            }
            .getOrNull()
        if (status != null) {
            appendLine("运行健康：${status.optString("health", "UNKNOWN")}")
            appendLayerSummaryZhCn(status, "vfs", "VFS")
            appendLayerSummaryZhCn(status, "mediaProviderJavaHook", "MediaProvider Java Hook")
            appendLayerSummaryZhCn(status, "fuseNativeHook", "FUSE Native Hook")
            appendLayerSummaryZhCn(status, "dataBus", "DataBus")
            appendLayerSummaryZhCn(status, "controlPlane", "控制面")
            appendLine()
        }

        val health = runCatching { DataBus.checkHealth(repair = true) }.getOrNull()
        if (health != null) {
            appendLine("DataBus：")
            appendLine("- initialized=${health.initialized}, healthy=${health.healthy}, criticalSnapshotsReady=${health.criticalSnapshotsReady}")
            appendLine("- eventQueueCounts=${health.eventQueueCounts}（物理量，含已消费未清理）")
            appendLine("- pendingEventCounts=${health.pendingEventCounts}（待处理，唯一告警口径）")
            appendLine("- quarantineCounts=${health.quarantineCounts}")
            appendLine("- cursorReadStates=${health.cursorReadStates}（UNREADABLE 队列不计入告警）")
            appendLine("- leaseCounts=${health.leaseCounts}")
            appendLine("- missingDirectories=${health.missingDirectories.size}, permissionIssues=${health.permissionIssues.size}")
            appendLine()
        }

        val nativeStatus = DataBus.readSnapshotSafe(DataBusProtocol.SNAPSHOT_NATIVE_HOOK_STATUS)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (nativeStatus != null) {
            appendNativeHookSummaryZhCn(nativeStatus)
            appendLine()
        }

        val platformCaps = DataBus.readSnapshotSafe(DataBusProtocol.SNAPSHOT_PLATFORM_CAPABILITIES)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (platformCaps != null) {
            appendLine("平台能力：")
            appendLine("- sdk=${platformCaps.optInt("sdkVersionInt", 0)}, fuse=${platformCaps.optBoolean("fuseAvailable", false)}, fuseBpf=${platformCaps.optBoolean("isFuseBpfEnabled", false)}")
            appendLine("- mediaProvider=${platformCaps.optString("mediaProviderPackageName", "")}")
            appendLine("- fuseJniLoadMode=${platformCaps.optString("fuseJniLoadMode", "UNKNOWN")}, nativeHookMode=${platformCaps.optString("supportedNativeHookMode", "UNKNOWN")}")
            appendLine()
        }

        if (status != null) {
            appendLine("关键信号：")
            appendKeySignalsZhCn(status)
            appendLine()
        }

        appendLine("如果诊断包难以阅读，请先从本文件开始，再打开上方引用的文件。")
    }

    private fun StringBuilder.appendLayerSummary(
        root: JSONObject,
        key: String,
        label: String,
    ) {
        val layer = root.optJSONObject(key)
        if (layer == null) {
            appendLine("- $label: missing")
            return
        }
        val state = layer.optString("state", "UNKNOWN")
        val error = layer.optString("lastError", "")
        val suffix = if (error.isBlank() || error == "null") "" else ", error=$error"
        appendLine("- $label: $state$suffix")
    }

    private fun StringBuilder.appendLayerSummaryZhCn(
        root: JSONObject,
        key: String,
        label: String,
    ) {
        val layer = root.optJSONObject(key)
        if (layer == null) {
            appendLine("- $label：缺失")
            return
        }
        val state = layer.optString("state", "UNKNOWN")
        val error = layer.optString("lastError", "")
        val suffix = if (error.isBlank() || error == "null") "" else "，错误=$error"
        appendLine("- $label：$state$suffix")
    }

    private fun StringBuilder.appendNativeHookSummary(root: JSONObject) {
        val mediaProvider = root.optJSONObject("mediaProvider")
        val inline = root.optJSONObject("inline")
        val native = root.optJSONObject("native")
        val symbols = native?.optJSONObject("symbols")
        val missingSymbols = native?.optJSONArray("missingSymbols")

        appendLine("Native hook:")
        appendLine("- mediaProvider.loaded=${mediaProvider?.optBoolean("loaded", false) ?: false}, package=${mediaProvider?.optString("packageName", "") ?: ""}")
        appendLine("- inline.state=${inline?.optString("state", "UNKNOWN") ?: "UNKNOWN"}, retryCount=${inline?.optInt("retryCount", 0) ?: 0}, disabledByPlatform=${inline?.optBoolean("disabledByPlatform", false) ?: false}")
        appendLine("- fuseLibraryLoaded=${native?.optBoolean("fuseLibraryLoaded", false) ?: false}, hookMode=${native?.optString("hookMode", "UNKNOWN") ?: "UNKNOWN"}, fuseJniLoadMode=${native?.optString("fuseJniLoadMode", "UNKNOWN") ?: "UNKNOWN"}")
        if (symbols != null) {
            appendLine("- symbols containsMount=${symbols.optBoolean("containsMount", false)}, startsWith=${symbols.optBoolean("startsWith", false)}, isFuseBpfEnabled=${symbols.optBoolean("isFuseBpfEnabled", false)}, fuseReqUserdata=${symbols.optBoolean("fuseReqUserdata", false)}, fillEntries=${symbols.optBoolean("fillEntries", false)}, install=${symbols.optBoolean("install", false)}, effective=${symbols.optBoolean("effective", false)}")
        }
        if (missingSymbols != null && missingSymbols.length() > 0) {
            appendLine("- missingSymbols=$missingSymbols")
        }
        appendHookErrorsSummary(root)
        val lastError = native?.optString("lastError", "") ?: ""
        if (lastError.isNotBlank()) {
            appendLine("- lastError=$lastError")
        }
    }

    private fun StringBuilder.appendNativeHookSummaryZhCn(root: JSONObject) {
        val mediaProvider = root.optJSONObject("mediaProvider")
        val inline = root.optJSONObject("inline")
        val native = root.optJSONObject("native")
        val symbols = native?.optJSONObject("symbols")
        val missingSymbols = native?.optJSONArray("missingSymbols")

        appendLine("Native Hook：")
        appendLine("- mediaProvider.loaded=${mediaProvider?.optBoolean("loaded", false) ?: false}, package=${mediaProvider?.optString("packageName", "") ?: ""}")
        appendLine("- inline.state=${inline?.optString("state", "UNKNOWN") ?: "UNKNOWN"}, retryCount=${inline?.optInt("retryCount", 0) ?: 0}, disabledByPlatform=${inline?.optBoolean("disabledByPlatform", false) ?: false}")
        appendLine("- fuseLibraryLoaded=${native?.optBoolean("fuseLibraryLoaded", false) ?: false}, hookMode=${native?.optString("hookMode", "UNKNOWN") ?: "UNKNOWN"}, fuseJniLoadMode=${native?.optString("fuseJniLoadMode", "UNKNOWN") ?: "UNKNOWN"}")
        if (symbols != null) {
            appendLine("- symbols containsMount=${symbols.optBoolean("containsMount", false)}, startsWith=${symbols.optBoolean("startsWith", false)}, isFuseBpfEnabled=${symbols.optBoolean("isFuseBpfEnabled", false)}, fuseReqUserdata=${symbols.optBoolean("fuseReqUserdata", false)}, fillEntries=${symbols.optBoolean("fillEntries", false)}, install=${symbols.optBoolean("install", false)}, effective=${symbols.optBoolean("effective", false)}")
        }
        if (missingSymbols != null && missingSymbols.length() > 0) {
            appendLine("- missingSymbols=$missingSymbols")
        }
        appendHookErrorsSummaryZhCn(root)
        val lastError = native?.optString("lastError", "") ?: ""
        if (lastError.isNotBlank()) {
            appendLine("- lastError=$lastError")
        }
    }

    /**
     * 中文版 Hook 失败事实摘要，语义与英文版一致。
     */
    private fun addStatus(zip: ZipOutputStream, server: CleanerServer) {
        runCatching {
            val status = server.layerOrchestrator.collectStatusJson()
            addText(zip, "status/orchestrated_status.json", status)
            DataBus.writeSnapshot(DataBusProtocol.SNAPSHOT_ORCHESTRATED_STATUS, status)
        }.onFailure {
            addText(zip, "status/orchestrated_status_error.txt", it.stackTraceToString())
        }
        runCatching {
            addText(zip, "status/server_exception.txt", server.cleanerService.serverException.toString())
        }
        addSnapshotIfExists(zip, DataBusProtocol.SNAPSHOT_NATIVE_HOOK_STATUS,
            "status/native_hook_status_pretty.json")
        addNativeHookSectionIfExists(zip, "fuseJavaGate",
            "status/fuse_java_gate_status.json")
        addSnapshotIfExists(zip, DataBusProtocol.SNAPSHOT_PLATFORM_CAPABILITIES,
            "status/platform_capabilities.json")
        addSnapshotIfExists(zip, DataBusProtocol.SNAPSHOT_CONFIGURED_MOUNT_POINTS,
            "status/configured_mount_points.json")
    }

    /**
     * 导出结构化错误事件流水（JSON Lines，每行一个 ErrorEvent 的紧凑表示）。
     * 与 orchestrated_status.json 中的 recentErrors 数组同源，
     * 供离线工具按错误码归并统计。
     *
     * 取持久层全文而非内存快照：持久层跨服务重启保留，
     * 内存环形缓冲只有 50 条且进程重启即清零。
     */
    private fun addErrorJournal(zip: ZipOutputStream) {
        runCatching {
            addText(zip, "errors/journal.jsonl", ServerErrorJournal.exportJsonL())
        }.onFailure {
            addText(zip, "errors/journal_error.txt", it.stackTraceToString())
        }
    }

    private fun addDataBus(zip: ZipOutputStream) {
        val initialized = DataBus.ensureInitialized()
        addText(zip, "databus/initialized.txt", initialized.toString())
        val busRoot = File(DataBus.BUS_ROOT)
        runCatching {
            // P1 诊断质量：归档侧补齐隔离/计数/总账，不改 HealthReport 协议
            val healthJson = healthToJson(DataBus.checkHealth(repair = true))
            val q1 = File(busRoot, "events/${DataBusProtocol.EVENT_FILESYSTEM}.quarantine")
            val q2 = File(busRoot, "events/${DataBusProtocol.EVENT_REDIRECT_NOTICE}.quarantine")
            val a1 = File(busRoot, "cursors/${DataBusProtocol.EVENT_FILESYSTEM}.attempts")
            val a2 = File(busRoot, "cursors/${DataBusProtocol.EVENT_REDIRECT_NOTICE}.attempts")
            val recovery = File(busRoot, "cursors/media_provider_recovery.json")
            healthJson.put("quarantineCounts", JSONObject().apply {
                put("filesystem", q1.listFiles()?.count { it.isFile() && it.name.endsWith(".json") } ?: 0)
                put("redirectNotice", q2.listFiles()?.count { it.isFile() && it.name.endsWith(".json") } ?: 0)
            })
            healthJson.put("attemptCounts", JSONObject().apply {
                put("filesystem", a1.listFiles()?.count { it.isFile() } ?: 0)
                put("redirectNotice", a2.listFiles()?.count { it.isFile() } ?: 0)
            })
            healthJson.put("recoveryStateExists", recovery.exists())
            addText(zip, "databus/health.json", healthJson.toString(2))
        }.onFailure {
            addText(zip, "databus/health_error.txt", it.stackTraceToString())
        }

        addDirectoryFiles(zip, File(busRoot, "snapshots"), "databus/snapshots", Int.MAX_VALUE)
        addDirectoryFiles(zip, File(busRoot, "signals"), "databus/signals", Int.MAX_VALUE)
        addDirectoryFiles(zip, File(busRoot, "cursors"), "databus/cursors", Int.MAX_VALUE)
        addDirectoryFiles(zip, File(busRoot, "events/${DataBusProtocol.EVENT_FILESYSTEM}"),
            "databus/events/${DataBusProtocol.EVENT_FILESYSTEM}", MAX_EVENT_FILES)
        addDirectoryFiles(zip, File(busRoot, "events/${DataBusProtocol.EVENT_REDIRECT_NOTICE}"),
            "databus/events/${DataBusProtocol.EVENT_REDIRECT_NOTICE}", MAX_EVENT_FILES)
        addDirectoryFiles(zip, File(busRoot, "events/consumed"),
            "databus/events/consumed", MAX_EVENT_FILES)
        addDirectoryFiles(zip, File(busRoot, "leases/${DataBusProtocol.LEASE_QUERY_SESSIONS}"),
            "databus/leases/${DataBusProtocol.LEASE_QUERY_SESSIONS}", MAX_EVENT_FILES)
        // P0-1：毒丸隔离与重试计数目录（Fix 1′/P0-B 产物，world-readable，只读导出）
        addDirectoryFiles(zip,
            File(busRoot, "events/${DataBusProtocol.EVENT_FILESYSTEM}.quarantine"),
            "databus/events/${DataBusProtocol.EVENT_FILESYSTEM}.quarantine", MAX_EVENT_FILES)
        addDirectoryFiles(zip,
            File(busRoot, "events/${DataBusProtocol.EVENT_REDIRECT_NOTICE}.quarantine"),
            "databus/events/${DataBusProtocol.EVENT_REDIRECT_NOTICE}.quarantine", MAX_EVENT_FILES)
        addDirectoryFiles(zip,
            File(busRoot, "cursors/${DataBusProtocol.EVENT_FILESYSTEM}.attempts"),
            "databus/cursors/${DataBusProtocol.EVENT_FILESYSTEM}.attempts", MAX_EVENT_FILES)
        addDirectoryFiles(zip,
            File(busRoot, "cursors/${DataBusProtocol.EVENT_REDIRECT_NOTICE}.attempts"),
            "databus/cursors/${DataBusProtocol.EVENT_REDIRECT_NOTICE}.attempts", MAX_EVENT_FILES)
    }

    private fun healthToJson(health: DataBusProtocol.HealthReport): JSONObject = JSONObject().apply {
        put("initialized", health.initialized)
        put("healthy", health.healthy)
        put("criticalSnapshotsReady", health.criticalSnapshotsReady)
        put("missingDirectories", JSONArray(health.missingDirectories))
        put("permissionIssues", JSONArray(health.permissionIssues))
        put("eventQueueCounts", JSONObject(health.eventQueueCounts))
        put("pendingEventCounts", JSONObject(health.pendingEventCounts))
        put("quarantineCounts", JSONObject(health.quarantineCounts))
        put("cursorReadStates", JSONObject(
            health.cursorReadStates.mapValues { it.value.name },
        ))
        put("leaseCounts", JSONObject(health.leaseCounts))
        put("snapshots", JSONArray().apply {
            for (snapshot in health.snapshots) {
                put(JSONObject().apply {
                    put("name", snapshot.name)
                    put("exists", snapshot.exists)
                    put("validJson", snapshot.validJson)
                    put("error", snapshot.error)
                })
            }
        })
    }

    private fun addLogcat(zip: ZipOutputStream) {
        addCommand(
            zip,
            "logs/logcat_threadtime_recent.txt",
            "/system/bin/logcat -d -v threadtime -b main,system,crash -t 5000",
        )
        addCommand(
            zip,
            "logs/logcat_material_cleaner_filtered.txt",
            "/system/bin/logcat -d -v threadtime -b main,system,crash -t 3000 " +
                    LogTagFilter.FILTER_SPEC,
        )
        // 崩溃 buffer 独立取证：crash 的 tag 是崩溃进程名，无法用固定白名单穷举，
        // 故此处不做 tag 过滤；crash buffer 仅在崩溃时写入，体积可忽略。
        addCommand(
            zip,
            "logs/logcat_crash_recent.txt",
            "/system/bin/logcat -d -v threadtime -b crash -t 2000",
        )
        // 目标进程（MediaProvider）定向取证：Hook 住在该进程内，其自身 tag
        // （FuseDaemon 等）不在本项目白名单内，只能按 pid 定向抓取。
        addCommand(
            zip,
            "logs/logcat_media_provider_pid.txt",
            "MPID=$(${'$'}(pidof com.android.providers.media.module " +
                    "com.android.providers.media " +
                    "com.google.android.providers.media.module 2>/dev/null | awk '{print ${'$'}1}')); " +
                    "if [ -z \"${'$'}MPID\" ]; then " +
                    "echo 'media provider process is not running (no pid found)'; " +
                    "else /system/bin/logcat -d -v threadtime --pid=${'$'}MPID -t 2000; fi",
        )
    }

    private fun addAutoLogs(zip: ZipOutputStream) {
        val dir = File(AUTO_LOG_DIR)
        val all = dir.listFiles()
            ?.filter { isRegularFileNoFollow(it) && it.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
        val files = all.take(MAX_AUTO_LOG_FILES)
        addText(zip, "logs/auto_logging_manifest.txt", buildString {
            appendLine("dir=$AUTO_LOG_DIR")
            appendLine("total=${all.size}")
            appendLine("included=${files.size}")
            for (file in files) {
                appendLine("${file.name}\tsize=${file.length()}\tmodified=${file.lastModified()}")
            }
        })
        for (file in files) {
            addFileHeadAndTail(zip, file, "logs/auto/${file.name}", MAX_TEXT_FILE_BYTES)
        }
    }

    private fun addCommandOutputs(zip: ZipOutputStream) {
        val commands = linkedMapOf(
            "commands/id.txt" to "id; getenforce 2>/dev/null; getprop ro.build.version.sdk; getprop ro.product.model",
            "commands/processes_cleaner.txt" to "ps -A | grep -E 'cleaner|material|gm.cleaner' || true",
            "commands/processes_mediaprovider.txt" to "ps -A | grep -E 'media.provider|providers.media|MediaProvider' || true",
            "commands/media_provider_maps.txt" to "for p in ${'$'}(pidof com.android.providers.media.module com.google.android.providers.media.module com.android.providers.media 2>/dev/null); do echo === pid=${'$'}p ===; grep -E 'MediaProvider|libfuse_jni|libinline|com.android.mediaprovider' /proc/${'$'}p/maps 2>&1; done",
            "commands/media_provider_mountinfo.txt" to "for p in ${'$'}(pidof com.android.providers.media.module com.google.android.providers.media.module com.android.providers.media 2>/dev/null); do echo === pid=${'$'}p ===; grep -E '/storage|/mnt/runtime|/Android/data|fuse' /proc/${'$'}p/mountinfo 2>&1 | head -200; done",
            "commands/mount_storage.txt" to "mount | grep -E '/storage|/mnt/runtime|/Android/data|fuse' || true",
            "commands/databus_tree.txt" to "ls -laR ${DataBus.BUS_ROOT} 2>&1 | head -400",
            "commands/auto_logs_tree.txt" to "ls -laR $AUTO_LOG_DIR 2>&1 | head -200",
            // 内核侧拒绝证据：SELinux avc denied、FUSE 内核错误、mount/namespace 失败
            // 只在内核日志可见，logcat 的 kernel buffer 在多数真机不可用（恒为空），
            // 故必须走 dmesg 通道。tail 限制输出量，保留最近匹配。
            "commands/dmesg_evidence.txt" to
                    "dmesg 2>/dev/null | grep -iE 'avc|fuse|mount|namespace' | tail -2000",
        )
        for ((entry, command) in commands) {
            addCommand(zip, entry, command)
        }
    }

    private fun addCommand(zip: ZipOutputStream, entryName: String, command: String) {
        val result = DiagnosticCommandRunner.runCommand(command)
        addText(zip, entryName, buildString {
            appendLine("$ $command")
            appendLine("exitCode=${result.exitCode}")
            appendLine("timedOut=${result.timedOut}")
            appendLine("truncated=${result.truncated}")
            appendLine()
            append(result.output)
        })
    }

    private fun addSnapshotIfExists(zip: ZipOutputStream, snapshotName: String, entryName: String) {
        val content = DataBus.readSnapshotSafe(snapshotName) ?: return
        val pretty = runCatching {
            JSONObject(content).toString(2)
        }.getOrDefault(content)
        addText(zip, entryName, pretty)
    }

    private fun addNativeHookSectionIfExists(
        zip: ZipOutputStream,
        sectionName: String,
        entryName: String,
    ) {
        val content = DataBus.readSnapshotSafe(DataBusProtocol.SNAPSHOT_NATIVE_HOOK_STATUS) ?: return
        val section = runCatching {
            JSONObject(content).optJSONObject(sectionName)
        }.getOrNull() ?: return
        addText(zip, entryName, section.toString(2))
    }

    private fun cleanupOldArchives(dir: File) {
        runCatching {
            dir.listFiles()
                ?.filter { isRegularFileNoFollow(it) && it.name.endsWith(".zip") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(5)
                ?.forEach { it.delete() }
        }.onFailure {
            Log.w(TAG, "cleanupOldArchives failed", it)
        }
    }

    // 命令执行实现见 DiagnosticCommandRunner（G2 粒度抽离）；
    // 历史 CommandResult 已迁移为 DiagnosticCommandRunner.CommandResult。
    // zip 写入与脱敏基础能力见文件末尾的顶层函数（G2 粒度抽离）。
}

/**
 * 写入一个文本条目到诊断包。
 *
 * 置于顶层 internal：既被本文件的编排逻辑使用，也被
 * [DiagnosticFileSection] 的文件取用逻辑使用。
 */
internal fun addText(zip: ZipOutputStream, entryName: String, content: String) {
    zip.putNextEntry(ZipEntry(safeEntryName(entryName)))
    zip.write(redact(content).toByteArray(Charsets.UTF_8))
    zip.closeEntry()
}

/** 脱敏：移除构建指纹、APK 路径与长十六进制标识。 */
internal fun redact(content: String): String {
    var redacted = content
    val fingerprint = Build.FINGERPRINT
    if (fingerprint.isNotBlank()) {
        redacted = redacted.replace(fingerprint, "<build-fingerprint>")
    }
    redacted = redacted
        .replace(Regex("/data/app/[^\\s\\n\\r]+"), "/data/app/<redacted>")
        .replace(Regex("/mnt/expand/[0-9A-Fa-f-]+"), "/mnt/expand/<volume>")
        .replace(Regex("(?i)\\b[0-9a-f]{24,}\\b"), "<hex-id>")
    return redacted
}

/** zip 条目名净化：去掉路径穿越与前导斜杠。 */
internal fun safeEntryName(name: String): String =
    name.replace('\\', '/').trimStart('/').replace("../", "_")
