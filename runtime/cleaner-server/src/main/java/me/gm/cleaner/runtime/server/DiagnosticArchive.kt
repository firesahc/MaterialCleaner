package me.gm.cleaner.runtime.server

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DiagnosticArchive {
    private const val TAG = "DiagnosticArchive"
    private const val OUTPUT_DIR = "/data/local/tmp/cleaner_diagnostics"
    private const val AUTO_LOG_DIR = "/data/local/tmp/cleaner_logs"
    private const val MAX_COMMAND_BYTES = 4 * 1024 * 1024
    private const val MAX_TEXT_FILE_BYTES = 512 * 1024
    private const val MAX_AUTO_LOG_FILES = 8
    private const val MAX_EVENT_FILES = 20

    fun open(server: CleanerServer): ParcelFileDescriptor {
        val file = create(server)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun create(server: CleanerServer): File {
        val outDir = File(OUTPUT_DIR)
        outDir.mkdirs()
        outDir.setReadable(true, false)
        outDir.setWritable(true, false)
        outDir.setExecutable(true, false)
        cleanupOldArchives(outDir)

        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val archive = File(outDir, "material-cleaner-diagnostics-$timestamp.zip")
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            addText(zip, "privacy.txt", privacyNotice())
            addText(zip, "manifest.txt", buildManifest(server))
            addText(zip, "summary.txt", buildSummary(server))
            addStatus(zip, server)
            addDataBus(zip)
            addLogcat(zip)
            addAutoLogs(zip)
            addCommandOutputs(zip)
        }
        archive.setReadable(true, false)
        Log.i("MC_DIAG", JSONObject().apply {
            put("event", "diagnostics_archive_created")
            put("path", archive.path)
            put("sizeBytes", archive.length())
            put("redacted", true)
            put("summary", true)
        }.toString())
        return archive
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
            appendLine("- eventQueueCounts=${health.eventQueueCounts}")
            appendLine("- leaseCounts=${health.leaseCounts}")
            appendLine("- missingDirectories=${health.missingDirectories.size}, permissionIssues=${health.permissionIssues.size}")
            appendLine()
        }

        val nativeStatus = DataBus.readSnapshotSafe(DataBus.SNAPSHOT_NATIVE_HOOK_STATUS)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (nativeStatus != null) {
            appendNativeHookSummary(nativeStatus)
            appendLine()
        }

        val platformCaps = DataBus.readSnapshotSafe(DataBus.SNAPSHOT_PLATFORM_CAPABILITIES)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (platformCaps != null) {
            appendLine("Platform capabilities:")
            appendLine("- sdk=${platformCaps.optInt("sdkVersionInt", 0)}, fuse=${platformCaps.optBoolean("fuseAvailable", false)}, fuseBpf=${platformCaps.optBoolean("isFuseBpfEnabled", false)}")
            appendLine("- mediaProvider=${platformCaps.optString("mediaProviderPackageName", "")}")
            appendLine("- fuseJniLoadMode=${platformCaps.optString("fuseJniLoadMode", "UNKNOWN")}, nativeHookMode=${platformCaps.optString("supportedNativeHookMode", "UNKNOWN")}")
            appendLine()
        }

        appendLine("If the package is hard to read, start from this file, then open the referenced files above.")
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
            appendLine("- symbols containsMount=${symbols.optBoolean("containsMount", false)}, startsWith=${symbols.optBoolean("startsWith", false)}, isFuseBpfEnabled=${symbols.optBoolean("isFuseBpfEnabled", false)}, fuseReqUserdata=${symbols.optBoolean("fuseReqUserdata", false)}, fuseBpfInstall=${symbols.optBoolean("fuseBpfInstall", false)}")
        }
        if (missingSymbols != null && missingSymbols.length() > 0) {
            appendLine("- missingSymbols=$missingSymbols")
        }
        val lastError = native?.optString("lastError", "") ?: ""
        if (lastError.isNotBlank()) {
            appendLine("- lastError=$lastError")
        }
    }

    private fun addStatus(zip: ZipOutputStream, server: CleanerServer) {
        runCatching {
            val status = server.layerOrchestrator.collectStatusJson()
            addText(zip, "status/orchestrated_status.json", status)
            DataBus.writeSnapshot(DataBus.SNAPSHOT_ORCHESTRATED_STATUS, status)
        }.onFailure {
            addText(zip, "status/orchestrated_status_error.txt", it.stackTraceToString())
        }
        runCatching {
            addText(zip, "status/server_exception.txt", server.cleanerService.serverException.toString())
        }
        addSnapshotIfExists(zip, DataBus.SNAPSHOT_NATIVE_HOOK_STATUS,
            "status/native_hook_status_pretty.json")
        addNativeHookSectionIfExists(zip, "fuseJavaGate",
            "status/fuse_java_gate_status.json")
        addSnapshotIfExists(zip, DataBus.SNAPSHOT_PLATFORM_CAPABILITIES,
            "status/platform_capabilities.json")
        addSnapshotIfExists(zip, DataBus.SNAPSHOT_CONFIGURED_MOUNT_POINTS,
            "status/configured_mount_points.json")
    }

    private fun addDataBus(zip: ZipOutputStream) {
        val initialized = DataBus.ensureInitialized()
        addText(zip, "databus/initialized.txt", initialized.toString())
        runCatching {
            addText(zip, "databus/health.json", healthToJson(DataBus.checkHealth(repair = true)).toString(2))
        }.onFailure {
            addText(zip, "databus/health_error.txt", it.stackTraceToString())
        }

        val busRoot = File(DataBus.BUS_ROOT)
        addDirectoryFiles(zip, File(busRoot, "snapshots"), "databus/snapshots", Int.MAX_VALUE)
        addDirectoryFiles(zip, File(busRoot, "signals"), "databus/signals", Int.MAX_VALUE)
        addDirectoryFiles(zip, File(busRoot, "cursors"), "databus/cursors", Int.MAX_VALUE)
        addDirectoryFiles(zip, File(busRoot, "events/${DataBus.EVENT_FILESYSTEM}"),
            "databus/events/${DataBus.EVENT_FILESYSTEM}", MAX_EVENT_FILES)
        addDirectoryFiles(zip, File(busRoot, "events/${DataBus.EVENT_REDIRECT_NOTICE}"),
            "databus/events/${DataBus.EVENT_REDIRECT_NOTICE}", MAX_EVENT_FILES)
        addDirectoryFiles(zip, File(busRoot, "events/consumed"),
            "databus/events/consumed", MAX_EVENT_FILES)
        addDirectoryFiles(zip, File(busRoot, "leases/${DataBus.LEASE_QUERY_SESSIONS}"),
            "databus/leases/${DataBus.LEASE_QUERY_SESSIONS}", MAX_EVENT_FILES)
    }

    private fun healthToJson(health: DataBus.HealthReport): JSONObject = JSONObject().apply {
        put("initialized", health.initialized)
        put("healthy", health.healthy)
        put("criticalSnapshotsReady", health.criticalSnapshotsReady)
        put("missingDirectories", JSONArray(health.missingDirectories))
        put("permissionIssues", JSONArray(health.permissionIssues))
        put("eventQueueCounts", JSONObject(health.eventQueueCounts))
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
                    "MC_BOOT:* MC_EVENT:* MC_STATE:* MC_DIAG:* MC_NATIVE:* " +
                    "MC_REDIRECT:* DataBus:* DiagnosticArchive:* CleanerService:* " +
                    "LayerOrchestrator:* SnapshotPublisher:* HookRecoveryCoordinator:* " +
                    "MediaProviderRecoveryStrategy:* NativeHookStatus:* HookPolicyCache:* " +
                    "FuseNativePolicyAdapter:* HookDataBusBridge:* EventConsumerScheduler:* " +
                    "FileSystemEventConsumer:* RedirectNoticeConsumer:* QuerySessionLeaseConsumer:* " +
                    "ActivityManagerLogsObserver:* AMLogs:* MC/Test:* MC/StateMachine:* " +
                    "CleanerTest:* xhook:* starter:* " +
                    "me.gm.cleaner:* *:S",
        )
    }

    private fun addAutoLogs(zip: ZipOutputStream) {
        val dir = File(AUTO_LOG_DIR)
        val files = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(MAX_AUTO_LOG_FILES)
            ?: emptyList()
        addText(zip, "logs/auto_logging_manifest.txt", buildString {
            appendLine("dir=$AUTO_LOG_DIR")
            appendLine("included=${files.size}")
            for (file in files) {
                appendLine("${file.name}\tsize=${file.length()}\tmodified=${file.lastModified()}")
            }
        })
        for (file in files) {
            addFileTail(zip, file, "logs/auto/${file.name}", MAX_TEXT_FILE_BYTES)
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
        )
        for ((entry, command) in commands) {
            addCommand(zip, entry, command)
        }
    }

    private fun addDirectoryFiles(
        zip: ZipOutputStream,
        dir: File,
        entryPrefix: String,
        maxFiles: Int,
    ) {
        val files = dir.listFiles()
            ?.filter { it.isFile }
            ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })
            ?.take(maxFiles)
            ?: emptyList()
        addText(zip, "$entryPrefix/manifest.txt", buildString {
            appendLine("path=${dir.path}")
            appendLine("exists=${dir.exists()}")
            appendLine("included=${files.size}")
            for (file in files) {
                appendLine("${file.name}\tsize=${file.length()}\tmodified=${file.lastModified()}")
            }
        })
        for (file in files) {
            addFileTail(zip, file, "$entryPrefix/${file.name}", MAX_TEXT_FILE_BYTES)
        }
    }

    private fun addCommand(zip: ZipOutputStream, entryName: String, command: String) {
        val result = runCommand(command)
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
        val content = DataBus.readSnapshotSafe(DataBus.SNAPSHOT_NATIVE_HOOK_STATUS) ?: return
        val section = runCatching {
            JSONObject(content).optJSONObject(sectionName)
        }.getOrNull() ?: return
        addText(zip, entryName, section.toString(2))
    }

    private fun runCommand(command: String): CommandResult {
        var process: Process? = null
        return try {
            process = ProcessBuilder("/system/bin/sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = ByteArrayOutputStream()
            var truncated = false
            val reader = Thread {
                try {
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    val input = process.inputStream
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (output.size() + read <= MAX_COMMAND_BYTES) {
                            output.write(buffer, 0, read)
                        } else {
                            val allowed = MAX_COMMAND_BYTES - output.size()
                            if (allowed > 0) output.write(buffer, 0, allowed)
                            truncated = true
                            process.destroy()
                            break
                        }
                    }
                } catch (_: Exception) {
                }
            }
            reader.start()
            val finished = process.waitFor(15, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
            }
            reader.join(1000)
            CommandResult(
                exitCode = if (finished) process.exitValue() else -1,
                timedOut = !finished,
                truncated = truncated,
                output = output.toString(StandardCharsets.UTF_8.name()),
            )
        } catch (e: Exception) {
            CommandResult(-1, timedOut = false, truncated = false, output = e.stackTraceToString())
        } finally {
            process?.destroy()
        }
    }

    private fun addFileTail(zip: ZipOutputStream, file: File, entryName: String, maxBytes: Int) {
        runCatching {
            val content = buildString {
                appendLine("path=${file.path}")
                appendLine("size=${file.length()}")
                appendLine("modified=${file.lastModified()}")
                if (file.length() > maxBytes) {
                    appendLine("truncated=head omitted, tailBytes=$maxBytes")
                }
                appendLine()
                append(readFileTail(file, maxBytes))
            }
            addText(zip, entryName, content)
        }.onFailure {
            addText(zip, "$entryName.error.txt", it.stackTraceToString())
        }
    }

    private fun readFileTail(file: File, maxBytes: Int): String {
        val output = ByteArrayOutputStream()
        if (file.length() <= maxBytes) {
            FileInputStream(file).use { input ->
                input.copyTo(output)
            }
        } else {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(file.length() - maxBytes)
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var remaining = maxBytes
                while (remaining > 0) {
                    val read = raf.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
        }
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun addText(zip: ZipOutputStream, entryName: String, content: String) {
        zip.putNextEntry(ZipEntry(safeEntryName(entryName)))
        zip.write(redact(content).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun redact(content: String): String {
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

    private fun safeEntryName(name: String): String =
        name.replace('\\', '/').trimStart('/').replace("../", "_")

    private fun cleanupOldArchives(dir: File) {
        runCatching {
            dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".zip") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(5)
                ?.forEach { it.delete() }
        }.onFailure {
            Log.w(TAG, "cleanupOldArchives failed", it)
        }
    }

    private data class CommandResult(
        val exitCode: Int,
        val timedOut: Boolean,
        val truncated: Boolean,
        val output: String,
    )
}
