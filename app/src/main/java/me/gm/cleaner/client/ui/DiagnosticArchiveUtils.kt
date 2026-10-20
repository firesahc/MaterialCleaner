package me.gm.cleaner.client.ui

/**
 * 诊断包抓取和分享工具。
 */
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.gm.cleaner.BuildConfig
import me.gm.cleaner.R
import me.gm.cleaner.client.CleanerClient
import me.gm.cleaner.client.ClientErrorJournal
import me.gm.cleaner.client.OrchestratedLayerStatus
import me.gm.cleaner.client.OrchestratedRuntimeStatus
import me.gm.cleaner.core.storage.redirect.databus.DataBus
import me.gm.cleaner.core.storage.redirect.databus.DataBusProtocol
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * App 进程侧错误事件流水在诊断包中的条目名。
 */
private const val CLIENT_JOURNAL_ENTRY = "client/errors/journal.jsonl"

/**
 * 诊断包有两条产出路径，资源约束不同（不要互相引用对方的口径）：
 *
 * 1. **服务端完整包**（[me.gm.cleaner.runtime.server.DiagnosticArchive]）：
 *    逐目录文件数上限（事件类 20、快照/信号/游标不限）、单文件 512 KiB、
 *    单条命令输出 4 MiB、自愈日志 8 个、归档保留 5 份；
 *    **没有覆盖全部条目的统一总量预算**。
 * 2. **App fallback 包**（本文件，服务端不可用时产出）：
 *    统一 [ArchiveBudget] 内容总量预算（压缩前条目内容），
 *    命令输出采集期另限 1 MiB；预算覆盖除最终归档清单外的全部条目。
 *
 * 因此不应声称“所有诊断包都严格限制在同一内容预算内”。
 * 后续若需统一，应在服务端路径引入同构预算，而非缩小本文件口径。
 */

fun Fragment.exportDiagnosticsArchiveAndShare(context: Context) {
    AlertDialog.Builder(context)
        .setTitle(R.string.diagnostics_archive_privacy_title)
        .setMessage(R.string.diagnostics_archive_privacy_message)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(R.string.diagnostics_archive_privacy_confirm) { _, _ ->
            createDiagnosticsArchiveAndShare(context)
        }
        .show()
}

private fun Fragment.createDiagnosticsArchiveAndShare(context: Context) {
    lifecycleScope.launch(Dispatchers.IO) {
        try {
            cleanupOldDiagnosticArchives(context)
            val logFile = File(
                context.cacheDir,
                "material-cleaner-diagnostics-${System.currentTimeMillis()}.zip"
            )
            val pfd = runCatching { CleanerClient.service?.openDiagnosticsArchive() }
                .onFailure {
                    if (BuildConfig.DEBUG) Log.w("CleanerTest", "server diagnostics unavailable", it)
                }
                .getOrNull()
            if (pfd != null) {
                copyFromServerArchive(pfd, logFile)
                appendClientJournalEntry(logFile)
            } else {
                createFallbackArchive(logFile)
            }
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", logFile
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            withContext(Dispatchers.Main) {
                context.startActivity(
                    Intent.createChooser(intent, context.getString(R.string.share_diagnostics_archive))
                )
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.e("CleanerTest", "diagnostics archive export failed", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "${context.getString(R.string.diagnostics_archive_failed)} ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}

private fun copyFromServerArchive(pfd: ParcelFileDescriptor, target: File) {
    ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
        FileOutputStream(target).use { output ->
            input.copyTo(output)
        }
    }
    if (target.length() == 0L) {
        throw IllegalStateException("diagnostics archive is empty")
    }
}

/**
 * 向已生成的诊断包追加 App 进程侧错误事件流水
 * （client/errors/journal.jsonl，与 server 端 errors/journal.jsonl 同格式）。
 *
 * Java 标准库不支持向现有 zip 追加条目，采用全量重写：
 * 读出原包全部条目后连同新条目一起重新压缩。
 */
private fun appendClientJournalEntry(zipFile: File) {
    val content = ClientErrorJournal.exportJsonL()
    if (content.isBlank()) return
    runCatching {
        val entries = mutableListOf<Pair<String, ByteArray>>()
        java.util.zip.ZipInputStream(java.io.FileInputStream(zipFile)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                entries += entry.name to zin.readBytes()
                entry = zin.nextEntry
            }
        }
        java.util.zip.ZipOutputStream(java.io.FileOutputStream(zipFile)).use { zout ->
            for ((name, bytes) in entries) {
                if (name == CLIENT_JOURNAL_ENTRY) continue
                zout.putNextEntry(java.util.zip.ZipEntry(name))
                zout.write(bytes)
                zout.closeEntry()
            }
            zout.putNextEntry(java.util.zip.ZipEntry(CLIENT_JOURNAL_ENTRY))
            zout.write(content.toByteArray(Charsets.UTF_8))
            zout.closeEntry()
        }
    }.onFailure {
        if (BuildConfig.DEBUG) Log.w("CleanerTest", "append client journal failed", it)
    }
}

private fun createFallbackArchive(target: File) {
    // 预算覆盖全部条目：小而关键的说明/清单/状态先写，保证预算耗尽时仍可用。
    val budget = ArchiveBudget(FALLBACK_MAX_TOTAL_BYTES)
    ZipOutputStream(FileOutputStream(target)).use { zip ->
        val status = runCatching { CleanerClient.getOrchestratedStatus() }
            .getOrNull()
        addTextEntry(zip, "privacy.txt", fallbackPrivacyNotice(), budget)
        addTextEntry(zip, "manifest.txt", buildFallbackManifest(), budget)
        addTextEntry(zip, "summary_zh-CN.txt", buildFallbackSummaryZhCn(status), budget)
        addTextEntry(
            zip,
            "client/errors/journal.jsonl",
            ClientErrorJournal.exportJsonL(),
            budget,
        )
        addCommandEntry(
            zip,
            "logs/app_logcat_threadtime_recent.txt",
            listOf("logcat", "-d", "-v", "threadtime", "-b", "main,system,crash", "-t", "2000"),
            budget,
        )
        // 事实类取证：server 不可用时（本包存在的理由），app 侧仍须能取到
        // "事实类"证据——uid/权限上下文、SELinux 模式、进程视图、挂载视图、
        // MediaProvider mountinfo。取不到必须显式记录原因，不得静默留空。
        val factEntries = addFactCommandEntries(zip, budget)
        // P0-2: fallback 时追加只读 DataBus（快照/信号/游标/隔离/重试计数/总账），不 repair 避免权限变更
        // P1-9 诊断预算：与 server 侧一致，事件类目录限 20 个，防止故障时大量
        // 毒丸/重试计数堆积把诊断导出本身变成资源压力源。
        // P2 统一预算：snapshots/signals/cursors 正常内容约 10~17 个，
        // 上限仅防病态堆积；全部条目共用同一预算，末尾输出最终归档清单。
        if (DataBus.ensureInitialized()) {
            addTextEntry(
                zip,
                "databus/health.json",
                healthToJson(DataBus.checkHealth(repair = false)).toString(2),
                budget,
            )
            val busRoot = File(DataBus.BUS_ROOT)
            addDirectoryFiles(zip, File(busRoot, "snapshots"), "databus/snapshots", 100, budget)
            addDirectoryFiles(zip, File(busRoot, "signals"), "databus/signals", 100, budget)
            addDirectoryFiles(zip, File(busRoot, "cursors"), "databus/cursors", 100, budget)
            addDirectoryFiles(zip, File(busRoot, "events/consumed"), "databus/events/consumed", 20, budget)
            addDirectoryFiles(zip,
                File(busRoot, "events/" + DataBusProtocol.EVENT_FILESYSTEM + ".quarantine"),
                "databus/events/" + DataBusProtocol.EVENT_FILESYSTEM + ".quarantine", 20, budget)
            addDirectoryFiles(zip,
                File(busRoot, "events/" + DataBusProtocol.EVENT_REDIRECT_NOTICE + ".quarantine"),
                "databus/events/" + DataBusProtocol.EVENT_REDIRECT_NOTICE + ".quarantine", 20, budget)
            addDirectoryFiles(zip,
                File(busRoot, "cursors/" + DataBusProtocol.EVENT_FILESYSTEM + ".attempts"),
                "databus/cursors/" + DataBusProtocol.EVENT_FILESYSTEM + ".attempts", 20, budget)
            addDirectoryFiles(zip,
                File(busRoot, "cursors/" + DataBusProtocol.EVENT_REDIRECT_NOTICE + ".attempts"),
                "databus/cursors/" + DataBusProtocol.EVENT_REDIRECT_NOTICE + ".attempts", 20, budget)
            // recovery_state.json 在 cursors 根目录，顺带导出
        }
        addTextEntry(
            zip,
            "status/app_visible_status.txt",
            status?.toString() ?: "server unavailable",
            budget,
        )
        // 最终归档清单：累计值与跳过/失败计数，供排障者判断证据完整性。
        // 本清单不计入预算——最终统计信息必须保留；预算口径为数据条目内容
        // （压缩前 UTF-8 字节），不是字面意义上所有 ZIP 条目、也不是最终文件体积。
        addTextEntry(zip, "archive_manifest.txt", buildString {
            appendLine("contentBudgetBytes=${budget.maxBytes()}")
            appendLine("contentUsedBytes=${budget.usedBytes()}")
            appendLine("skippedByBudget=${budget.skippedByBudget.get()}")
            appendLine("readFailed=${budget.readFailed.get()}")
            appendLine("commandOutputCapBytes=$MAX_COMMAND_OUTPUT_BYTES")
            // 事实类取证条目逐条列出状态：排障者一眼可判断"是没取到"还是"取到了但为空"。
            appendLine("factEntries=${factEntries.size}")
            appendLine("factEntriesOk=${factEntries.count { it.second == FACT_STATUS_OK }}")
            appendLine("factEntriesDegraded=${factEntries.count { it.second == FACT_STATUS_DEGRADED }}")
            appendLine("factEntriesEmpty=${factEntries.count { it.second == FACT_STATUS_EMPTY }}")
            appendLine("factEntriesUnavailable=${factEntries.count { it.second == FACT_STATUS_UNAVAILABLE }}")
            appendLine("factEntriesFailed=${factEntries.count { it.second == FACT_STATUS_FAILED || it.second == FACT_STATUS_TIMEOUT }}")
            for ((name, entryStatus) in factEntries) {
                appendLine("factEntry=$name\t$entryStatus")
            }
            appendLine("note=budget covers entry content (pre-compression), " +
                    "excluding this final manifest; per-file results live in each directory manifest")
        })
    }
}

private fun fallbackPrivacyNotice(): String = buildString {
    appendLine("This fallback diagnostics package was generated by the app process.")
    appendLine("It may include device details, app-visible logcat output,")
    appendLine("app-visible process and mount state,")
    appendLine("and the app-visible service status.")
    appendLine("Basic identifiers such as build fingerprint, APK source paths,")
    appendLine("and long hex-like tokens are redacted before export.")
}

private fun buildFallbackSummaryZhCn(status: OrchestratedRuntimeStatus?): String = buildString {
    appendLine("Material Cleaner 诊断包概览（App fallback）")
    appendLine("生成时间：${System.currentTimeMillis()}")
    appendLine()
    appendLine("说明：")
    appendLine("- 这是服务端诊断不可用时由 App 进程生成的 fallback 诊断包。")
    appendLine("- 数据范围比完整服务端诊断包小，主要包含 App 可见状态和近期 logcat。")
    appendLine("- logs/app_logcat_threadtime_recent.txt：App 进程可抓取的近期日志。")
    appendLine("- status/app_visible_status.txt：App 通过 Binder 读取到的服务状态。")
    appendLine("- commands/：降级路径下仍尽力取证的事实类证据（uid/权限上下文、")
    appendLine("  SELinux 模式、进程视图、挂载视图、MediaProvider mountinfo），")
    appendLine("  条目名与 server 侧 commands/ 对齐，便于交叉比对。")
    appendLine("- 每条 commands/ 取证条目首部的 status 说明该证据是否取到，")
    appendLine("  failure 说明失败原因；status=degraded 表示主命令不可用、")
    appendLine("  已改用替代来源。空文件不等于现象不存在。")
    appendLine()
    appendLine("连接状态：")
    appendLine("- serverConnected=${CleanerClient.service != null}")
    appendLine("- serverVersion=${CleanerClient.serverVersion}")
    if (status == null) {
        appendLine("- orchestratedStatus=unavailable")
    } else {
        appendLine("- health=${status.health}")
        appendFallbackLayerSummary("VFS", status.vfs)
        appendFallbackLayerSummary("MediaProvider Java Hook", status.mediaProviderJavaHook)
        appendFallbackLayerSummary("FUSE Native Hook", status.fuseNativeHook)
        appendFallbackLayerSummary("DataBus", status.dataBus)
        appendFallbackLayerSummary("控制面", status.controlPlane)
    }
    appendLine()
    appendLine("如果需要完整链路证据，请优先修复服务端连接后重新导出诊断包。")
}

private fun StringBuilder.appendFallbackLayerSummary(
    label: String,
    layer: OrchestratedLayerStatus,
) {
    val error = layer.lastError.orEmpty()
    val suffix = if (error.isBlank() || error == "null") "" else "，错误=$error"
    appendLine("- $label：${layer.state}$suffix")
}

private fun buildFallbackManifest(): String = buildString {
    appendLine("createdAt=${System.currentTimeMillis()}")
    appendLine("redacted=true")
    appendLine("source=app-fallback")
    appendLine("serverConnected=${CleanerClient.service != null}")
    appendLine("serverVersion=${CleanerClient.serverVersion}")
    appendLine("sdk=${Build.VERSION.SDK_INT}")
    appendLine("release=${Build.VERSION.RELEASE}")
    appendLine("manufacturer=${Build.MANUFACTURER}")
    appendLine("brand=${Build.BRAND}")
    appendLine("model=${Build.MODEL}")
    appendLine("device=${Build.DEVICE}")
    appendLine("fingerprint=${Build.FINGERPRINT}")
}

private fun addCommandEntry(
    zip: ZipOutputStream,
    entryName: String,
    command: List<String>,
    budget: ArchiveBudget? = null,
) {
    val result = runCommand(command)
    val described = describeCommandResult(result)
    addTextEntry(zip, entryName, buildString {
        appendLine("$ ${command.joinToString(" ")}")
        appendLine("status=${described.first}")
        if (described.second != null) appendLine("failure=${described.second}")
        appendLine("exitCode=${result.exitCode}")
        appendLine("timedOut=${result.timedOut}")
        appendLine("truncated=${result.truncated}")
        appendLine()
        append(result.output)
    }, budget)
}


internal fun runCommand(
    command: List<String>,
    timeoutSeconds: Long = COMMAND_TIMEOUT_SECONDS,
): CommandResult {
    var process: Process? = null
    return try {
        process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        // 采集线程必须先于等待启动：否则 waitFor 超时后读取会阻塞到 EOF，
        // 而销毁又在读取之后，形成“超时却不返回”的死等路径。
        val output = readCappedAsync(process.inputStream, MAX_COMMAND_OUTPUT_BYTES)
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        // 无论是否超时都先终止进程并回收，确保读取线程能拿到 EOF。
        process.destroyForcibly()
        val bounded = output.joinBounded(COMMAND_DRAIN_GRACE_MS)
        CommandResult(
            exitCode = if (finished && bounded.completed) process.exitValue() else -1,
            timedOut = !finished,
            truncated = bounded.truncated || !bounded.completed,
            output = bounded.text,
        )
    } catch (e: Exception) {
        CommandResult(-1, timedOut = false, truncated = false, output = e.stackTraceToString())
    } finally {
        process?.destroy()
    }
}

/** 子进程等待上限：超时即强杀，不无限等待。 */
private const val COMMAND_TIMEOUT_SECONDS = 10L

/**
 * 事实类命令的等待上限：这些命令都是本地瞬时命令（id/getenforce/ps/mount/pidof），
 * 正常耗时在百毫秒量级，因此用更短的上限把"命令卡死"的最坏总耗时从
 * 5×10s 压到 5×5s——诊断导出本身不得在设备故障时成为新的阻塞源。
 */
internal const val FACT_COMMAND_TIMEOUT_SECONDS = 5L

/** 强杀后留给采集线程拿到 EOF 的宽限期。 */
private const val COMMAND_DRAIN_GRACE_MS = 1_000L

/** 命令输出采集上限：超限截断并标记，避免诊断导出在故障时成为内存压力源。 */
private const val MAX_COMMAND_OUTPUT_BYTES = 1024 * 1024

/**
 * 并发排空输出流：采集在独立线程进行，主线程只做有界等待，
 * 保证采集耗时受控，不因目标进程不退出而无限阻塞。
 */
private fun readCappedAsync(
    input: java.io.InputStream,
    maxBytes: Int,
): java.util.concurrent.Future<CappedOutput> {
    return java.util.concurrent.CompletableFuture.supplyAsync {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var truncated = false
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (out.size() + read <= maxBytes) {
                    out.write(buffer, 0, read)
                } else {
                    val allowed = maxBytes - out.size()
                    if (allowed > 0) out.write(buffer, 0, allowed)
                    truncated = true
                    break
                }
            }
        } catch (_: Exception) {
            // 进程被强杀时流读取抛异常属预期：已读到的内容仍然有效。
        }
        CappedOutput(out.toString(Charsets.UTF_8.name()), truncated, completed = true)
    }
}

private data class CappedOutput(
    val text: String,
    val truncated: Boolean,
    val completed: Boolean,
)

/** 有界等待采集线程：超时未完成则标记未完成，由调用方按截断处理。 */
private fun java.util.concurrent.Future<CappedOutput>.joinBounded(
    timeoutMs: Long,
): CappedOutput = try {
    get(timeoutMs, TimeUnit.MILLISECONDS)
} catch (e: Exception) {
    CappedOutput("", truncated = true, completed = false)
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

private fun cleanupOldDiagnosticArchives(context: Context) {
    context.cacheDir.listFiles()
        ?.filter { it.isFile && it.name.startsWith("material-cleaner-diagnostics-") }
        ?.sortedByDescending { it.lastModified() }
        ?.drop(3)
        ?.forEach { it.delete() }
}

internal data class CommandResult(
    val exitCode: Int,
    val timedOut: Boolean,
    val truncated: Boolean = false,
    val output: String,
)

// P0-2 fallback DataBus 只读导出所需辅助函数（复用 server 侧同名逻辑，不 repair 避免权限变更）
private fun healthToJson(health: DataBusProtocol.HealthReport): org.json.JSONObject = org.json.JSONObject().apply {
    put("initialized", health.initialized)
    put("healthy", health.healthy)
    put("criticalSnapshotsReady", health.criticalSnapshotsReady)
    put("missingDirectories", org.json.JSONArray(health.missingDirectories))
    put("permissionIssues", org.json.JSONArray(health.permissionIssues))
    put("eventQueueCounts", org.json.JSONObject(health.eventQueueCounts))
    put("leaseCounts", org.json.JSONObject(health.leaseCounts))
    put("snapshots", org.json.JSONArray().apply {
        for (snapshot in health.snapshots) {
            put(org.json.JSONObject().apply {
                put("name", snapshot.name)
                put("exists", snapshot.exists)
                put("validJson", snapshot.validJson)
                put("error", snapshot.error)
            })
        }
    })
    // 归档侧补齐：隔离/重试计数/总账（不改协议，不动 HealthReport 类）
    val busRoot = java.io.File(DataBus.BUS_ROOT)
    val q1 = java.io.File(busRoot, "events/" + DataBusProtocol.EVENT_FILESYSTEM + ".quarantine")
    val q2 = java.io.File(busRoot, "events/" + DataBusProtocol.EVENT_REDIRECT_NOTICE + ".quarantine")
    val a1 = java.io.File(busRoot, "cursors/" + DataBusProtocol.EVENT_FILESYSTEM + ".attempts")
    val a2 = java.io.File(busRoot, "cursors/" + DataBusProtocol.EVENT_REDIRECT_NOTICE + ".attempts")
    val recovery = java.io.File(busRoot, "cursors/media_provider_recovery.json")
    put("quarantineCounts", org.json.JSONObject().apply {
        put("filesystem", q1.listFiles()?.count { it.isFile() && it.name.endsWith(".json") } ?: 0)
        put("redirectNotice", q2.listFiles()?.count { it.isFile() && it.name.endsWith(".json") } ?: 0)
    })
    put("attemptCounts", org.json.JSONObject().apply {
        put("filesystem", a1.listFiles()?.count { it.isFile() } ?: 0)
        put("redirectNotice", a2.listFiles()?.count { it.isFile() && it.name.endsWith(".json") } ?: 0)
    })
    put("recoveryStateExists", recovery.exists())
}

/** 归档条目写入结果：三种失败/跳过语义必须分开，诊断包不得静默遗漏证据。 */
internal enum class ArchiveWriteResult {
    INCLUDED,
    SKIPPED_BY_BUDGET,
    READ_FAILED,
}

/**
 * 诊断导出资源预算：诊断工具不得在系统故障时成为资源压力源。
 *
 * 统计范围是写入 ZIP 的**全部**条目内容字节（压缩前），
 * 不是最终 ZIP 体积；上限的目的在于限制内存与写入量级。
 */
internal class ArchiveBudget(private val maxTotalBytes: Long) {
    private val used = java.util.concurrent.atomic.AtomicLong(0L)
    val skippedByBudget = java.util.concurrent.atomic.AtomicInteger(0)
    val readFailed = java.util.concurrent.atomic.AtomicInteger(0)

    /** 为 content 预留字节；超限返回 false 并由本对象累计跳过数。 */
    fun tryReserve(content: String): Boolean {
        val bytes = content.toByteArray(Charsets.UTF_8).size.toLong()
        while (true) {
            val current = used.get()
            if (current + bytes > maxTotalBytes) {
                skippedByBudget.incrementAndGet()
                return false
            }
            if (used.compareAndSet(current, current + bytes)) return true
        }
    }

    fun recordReadFailure() {
        readFailed.incrementAndGet()
    }

    fun usedBytes(): Long = used.get()

    fun maxBytes(): Long = maxTotalBytes
}

/** fallback 归档内容总量预算（压缩前字节），超出部分跳过并记录。 */
private const val FALLBACK_MAX_TOTAL_BYTES = 8L * 1024 * 1024

/** 写文本条目并按预算记账；返回写入结果供调用方统计。 */
internal fun addTextEntry(
    zip: java.util.zip.ZipOutputStream,
    entryName: String,
    rawContent: String,
    budget: ArchiveBudget? = null,
): ArchiveWriteResult {
    val content = redact(rawContent)
    if (budget != null && !budget.tryReserve(content)) {
        return ArchiveWriteResult.SKIPPED_BY_BUDGET
    }
    zip.putNextEntry(java.util.zip.ZipEntry(entryName))
    zip.write(content.toByteArray(Charsets.UTF_8))
    zip.closeEntry()
    return ArchiveWriteResult.INCLUDED
}

/** 候选文件的最终处理结果：四种遗漏/纳入原因必须可逐条核对。 */
private enum class CandidateResult {
    INCLUDED,
    SKIPPED_BY_BUDGET,
    SKIPPED_BY_FILE_LIMIT,
    READ_FAILED,
}

/**
 * 追加目录文件并记账。manifest 在处理完文件后生成，
 * 逐文件记录最终结果，使“证据为何缺失”可核对。
 */
private fun addDirectoryFiles(
    zip: java.util.zip.ZipOutputStream,
    dir: java.io.File,
    entryPrefix: String,
    maxFiles: Int,
    budget: ArchiveBudget? = null,
) {
    val all = dir.listFiles()
        ?.filter { java.nio.file.Files.isRegularFile(it.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()
    val candidates = all.take(maxFiles)
    val results = LinkedHashMap<String, CandidateResult>()
    for (file in candidates) {
        results[file.name] = when (
            addFileTail(zip, file, "$entryPrefix/${file.name}", 512 * 1024, budget)
        ) {
            ArchiveWriteResult.INCLUDED -> CandidateResult.INCLUDED
            ArchiveWriteResult.SKIPPED_BY_BUDGET -> CandidateResult.SKIPPED_BY_BUDGET
            ArchiveWriteResult.READ_FAILED -> CandidateResult.READ_FAILED
        }
    }
    // 因目录文件数上限未进入候选集的文件：独立账目，不与预算跳过混淆。
    for (file in all.drop(maxFiles)) {
        results[file.name] = CandidateResult.SKIPPED_BY_FILE_LIMIT
    }
    val counts = results.values.groupingBy { it }.eachCount()
    addTextEntry(zip, "$entryPrefix/manifest.txt", buildString {
        appendLine("path=${dir.path}")
        appendLine("exists=${dir.exists()}")
        appendLine("total=${all.size}")
        appendLine("candidates=${candidates.size}")
        appendLine("included=${counts[CandidateResult.INCLUDED] ?: 0}")
        appendLine("skippedByBudget=${counts[CandidateResult.SKIPPED_BY_BUDGET] ?: 0}")
        appendLine("skippedByFileLimit=${counts[CandidateResult.SKIPPED_BY_FILE_LIMIT] ?: 0}")
        appendLine("readFailed=${counts[CandidateResult.READ_FAILED] ?: 0}")
        if (budget != null) appendLine("budgetUsedBytes=${budget.usedBytes()}")
        appendLine("results:")
        for ((name, result) in results) {
            appendLine("  $name\t$result")
        }
    }, budget)
}

/** 读文件尾部并写入；读失败与预算跳过分别记账，不再静默遗漏。 */
private fun addFileTail(
    zip: java.util.zip.ZipOutputStream,
    file: java.io.File,
    entryName: String,
    maxBytes: Int,
    budget: ArchiveBudget? = null,
): ArchiveWriteResult {
    val content = runCatching {
        buildString {
            appendLine("path=${file.path}")
            appendLine("size=${file.length()}")
            appendLine("modified=${file.lastModified()}")
            if (file.length() > maxBytes) {
                appendLine("truncated=head omitted, tailBytes=$maxBytes")
            }
            appendLine()
            append(readFileTail(file, maxBytes))
        }
    }.getOrElse {
        budget?.recordReadFailure()
        return ArchiveWriteResult.READ_FAILED
    }
    return addTextEntry(zip, entryName, content, budget)
}

private fun readFileTail(file: java.io.File, maxBytes: Int): String {
    val output = java.io.ByteArrayOutputStream()
    if (file.length() <= maxBytes) {
        java.io.FileInputStream(file).use { input ->
            input.copyTo(output)
        }
    } else {
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(file.length() - maxBytes)
            val buffer = ByteArray(4096)
            var remaining = maxBytes
            while (remaining > 0) {
                val read = raf.read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
    }
    return output.toString(java.nio.charset.StandardCharsets.UTF_8.name())
}
