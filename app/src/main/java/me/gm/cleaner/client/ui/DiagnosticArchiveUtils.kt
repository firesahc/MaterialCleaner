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

// ── 事实类取证（server 不可用时的降级证据）────────────────────────────
//
// 与 server 侧 commands/ 语义对齐（同名条目便于交叉比对），但执行主体是
// app UID（无 root）。两条底线：
// 1. 命令直连可执行文件、不经 /system/bin/sh：untrusted_app 域执行 shell
//    常被 SELinux 拒绝，过滤在进程内完成，避免"命令跑不起来"被误读成
//    "现象不存在"；
// 2. 取不到就显式记录 status/failure，不得产出空文件——空文件会被排障者
//    误读成"现象不存在"，而真实原因往往只是权限受限。
//
// status 词表（见 FACT_STATUS_* 常量）：
// - ok：命令成功且有输出；
// - degraded：主命令不可用，但已用替代来源取到部分事实；
// - empty：命令退出码 0 但无输出（多为权限受限或无匹配项）；
// - timeout：超时被强杀；
// - failed：命令不可执行或非 0 退出；
// - unavailable：取证前提不可得（如 pid 无法解析），根本没有取。

private const val FACT_STATUS_OK = "ok"
private const val FACT_STATUS_DEGRADED = "degraded"
private const val FACT_STATUS_EMPTY = "empty"
private const val FACT_STATUS_TIMEOUT = "timeout"
private const val FACT_STATUS_FAILED = "failed"
private const val FACT_STATUS_UNAVAILABLE = "unavailable"

/** 进程匹配口径：与 server 侧 ps | grep 对齐（大小写不敏感为超集）。 */
private val CLEANER_PROCESS_PATTERN =
    Regex("cleaner|material|gm\\.cleaner", RegexOption.IGNORE_CASE)

private val MEDIA_PROVIDER_PROCESS_PATTERN =
    Regex("media[._]?provider|providers[._]media", RegexOption.IGNORE_CASE)

/** 挂载行过滤口径：与 server 侧 mount | grep 对齐。 */
private const val MOUNT_STORAGE_FILTER = "/storage|/mnt/runtime|/Android/data|fuse"

private val MOUNT_STORAGE_PATTERN = Regex(MOUNT_STORAGE_FILTER, RegexOption.IGNORE_CASE)

/** MediaProvider 包名候选：与 server 侧 pidof 候选一致。 */
private val MEDIA_PROVIDER_PACKAGES = listOf(
    "com.android.providers.media.module",
    "com.google.android.providers.media.module",
    "com.android.providers.media",
)

/** mountinfo 最多解析的 pid 数：防止异常情况下条目膨胀。 */
private const val MAX_MOUNTINFO_PIDS = 4

/** mountinfo 正文最多保留的行数（与 server 侧 head -200 对齐）。 */
private const val MAX_MOUNTINFO_LINES = 200

/** 读取 /proc 文件的单文件字节上限。 */
private const val MAX_PROC_FILE_BYTES = 256 * 1024

/**
 * 事实类取证条目集合。
 *
 * 顺序按排障价值排列：身份/权限上下文 → SELinux 模式 → 进程视图 → 挂载视图
 * → MediaProvider mountinfo。全部条目共享同一份 ps 快照，避免重复采集造成
 * 视图漂移（同一包内两条 ps 结果不一致会误导排障）。
 */
private fun addFactCommandEntries(
    zip: ZipOutputStream,
    budget: ArchiveBudget?,
): List<Pair<String, String>> {
    val entries = mutableListOf<Pair<String, String>>()

    entries += addIdentityFactEntry(zip, budget)

    val getenforce = runCommand(listOf("getenforce"), FACT_COMMAND_TIMEOUT_SECONDS)
    val getenforceStatus = describeCommandResult(getenforce)
    entries += addFactCommandEntry(
        zip = zip,
        entryName = "commands/getenforce.txt",
        command = "getenforce",
        budget = budget,
        result = getenforce,
        body = buildString {
            appendLine(getenforce.output.trim())
            if (getenforceStatus.first != FACT_STATUS_OK) {
                appendLine("note=SELinux mode not readable via getenforce under app UID")
            }
        },
    )

    // 进程视图：单次 ps 快照供多条目共用
    val ps = runCommand(listOf("ps", "-A"), FACT_COMMAND_TIMEOUT_SECONDS)
    entries += addProcessFactEntry(
        zip, "commands/processes_cleaner.txt", ps, CLEANER_PROCESS_PATTERN, budget,
    )
    entries += addProcessFactEntry(
        zip, "commands/processes_mediaprovider.txt", ps, MEDIA_PROVIDER_PROCESS_PATTERN, budget,
    )

    entries += addMountStorageFactEntry(zip, budget)
    entries += addMediaProviderMountInfoFactEntry(zip, ps, budget)

    return entries
}

/**
 * 写一条事实类取证条目。
 *
 * 条目结构：命令行 → status → failure → exitCode/timedOut/truncated →
 * 事实行 → 正文。取到了什么与为什么没取到必须分开陈述。
 */
private fun addFactCommandEntry(
    zip: ZipOutputStream,
    entryName: String,
    command: String,
    budget: ArchiveBudget?,
    result: CommandResult? = null,
    status: String? = null,
    failure: String? = null,
    facts: List<String> = emptyList(),
    body: String = result?.output.orEmpty(),
): Pair<String, String> {
    val described = result?.let { describeCommandResult(it) }
    val effectiveStatus = status ?: described?.first ?: FACT_STATUS_OK
    val effectiveFailure = failure ?: described?.second
    addTextEntry(zip, entryName, buildString {
        appendLine("$ $command")
        appendLine("status=$effectiveStatus")
        if (effectiveFailure != null) appendLine("failure=$effectiveFailure")
        if (result != null) {
            appendLine("exitCode=${result.exitCode}")
            appendLine("timedOut=${result.timedOut}")
            appendLine("truncated=${result.truncated}")
        }
        for (fact in facts) appendLine(fact)
        appendLine()
        append(body)
    }, budget)
    return entryName to effectiveStatus
}

/**
 * 命令结果 → (状态, 失败原因)。
 *
 * 退出码 0 但无输出**不算成功**：app UID 下"看不到别人的日志/进程"时命令
 * 常以 0 退出却什么都不给，记成 ok 会让排障者误判"现象不存在"。
 */
private fun describeCommandResult(result: CommandResult): Pair<String, String?> {
    if (result.timedOut) {
        return FACT_STATUS_TIMEOUT to "command did not finish before the timeout"
    }
    if (result.exitCode != 0) {
        val detail = result.output.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.take(300)
        return FACT_STATUS_FAILED to
            ("exitCode=${result.exitCode}" + (detail?.let { "; $it" } ?: ""))
    }
    if (result.output.isBlank()) {
        return FACT_STATUS_EMPTY to
            "command exited 0 with no output (permission restricted, or nothing to report)"
    }
    return FACT_STATUS_OK to null
}

/**
 * 身份与权限上下文条目：命令不可执行时，/proc/self 仍能给出 uid 与 SELinux
 * 上下文，因此本条通常不会空手而归（status=degraded 而非 failed）。
 */
private fun addIdentityFactEntry(
    zip: ZipOutputStream,
    budget: ArchiveBudget?,
): Pair<String, String> {
    val result = runCommand(listOf("id"), FACT_COMMAND_TIMEOUT_SECONDS)
    val described = describeCommandResult(result)
    val facts = appIdentityFacts()
    val hasFallback = facts.any { !it.endsWith("=unavailable") }
    return addFactCommandEntry(
        zip = zip,
        entryName = "commands/id.txt",
        command = "id",
        budget = budget,
        result = result,
        status = if (described.first != FACT_STATUS_OK && hasFallback) {
            FACT_STATUS_DEGRADED
        } else {
            described.first
        },
        failure = described.second,
        facts = facts,
        body = result.output,
    )
}

/**
 * app 进程自身的事实：不依赖任何命令执行权限，命令不可用时也能给出
 * uid/pid/gid 与 SELinux 上下文（/proc/self/attr/current 对 self 可读）。
 */
private fun appIdentityFacts(): List<String> = buildList {
    add("appUid=${runCatching { Os.getuid() }.getOrDefault(-1)}")
    add("appPid=${runCatching { Os.getpid() }.getOrDefault(-1)}")
    add("appGid=${runCatching { Os.getgid() }.getOrDefault(-1)}")
    add("selinuxContext=${readProcSelfAttrCurrent() ?: "unavailable"}")
}

/** 读取 app 自身 SELinux 上下文；不可读时返回 null（由调用方记为 unavailable）。 */
private fun readProcSelfAttrCurrent(): String? = runCatching {
    readFileHead(File("/proc/self/attr/current"), 256).trim()
        .takeIf { it.isNotEmpty() }
}.getOrNull()

/**
 * 进程视图条目：与 server 侧 commands/processes_*.txt 语义一致。
 *
 * 过滤在进程内完成（不经 shell grep）：app UID 下能否看到他人进程本身就
 * 是结论的一部分，所以同时记录 totalProcessLines（可见进程总数）与
 * matchedLines（命中数），空结果必须能区分"没这个进程"与"看不见别人进程"。
 */
private fun addProcessFactEntry(
    zip: ZipOutputStream,
    entryName: String,
    psResult: CommandResult,
    pattern: Regex,
    budget: ArchiveBudget?,
): Pair<String, String> {
    val described = describeCommandResult(psResult)
    // 命令失败时输出是异常栈，不能当进程表解析（栈里的包名会误命中过滤器）。
    val usable = described.first == FACT_STATUS_OK
    val all = if (usable) {
        psResult.output.lineSequence().filter { it.isNotBlank() }.toList()
    } else {
        emptyList()
    }
    val matched = all.filter { pattern.containsMatchIn(it) }
    val body = buildString {
        if (!usable) {
            append(psResult.output)
        } else if (matched.isEmpty()) {
            appendLine("note=no process matched the filter")
            appendLine("note=under app UID the process view may be limited to this app")
            appendLine("     (proc hidepid / SELinux proc_pid rules); empty is not 'absent'")
        } else {
            matched.forEach { appendLine(it) }
        }
    }
    return addFactCommandEntry(
        zip = zip,
        entryName = entryName,
        command = "ps -A",
        budget = budget,
        result = psResult,
        status = described.first,
        failure = described.second,
        facts = listOf(
            "filter=${pattern.pattern}",
            "totalProcessLines=${if (usable) all.size.toString() else "unavailable"}",
            "matchedLines=${if (usable) matched.size.toString() else "unavailable"}",
        ),
        body = body,
    )
}

/**
 * 挂载视图条目：与 server 侧 commands/mount_storage.txt 对齐。
 *
 * 主命令 mount 不可用时退化到 /proc/self/mounts（app 自身 mount 视图始终
 * 可读），并把退化原因写进 status/failure，不静默产出空文件。
 */
private fun addMountStorageFactEntry(
    zip: ZipOutputStream,
    budget: ArchiveBudget?,
): Pair<String, String> {
    val result = runCommand(listOf("mount"), FACT_COMMAND_TIMEOUT_SECONDS)
    val described = describeCommandResult(result)
    var status = described.first
    var failure = described.second
    var source = "mount"
    var lines: List<String> = emptyList()

    if (status == FACT_STATUS_OK) {
        lines = result.output.lineSequence().filter { it.isNotBlank() }.toList()
    }
    if (status != FACT_STATUS_OK || lines.isEmpty()) {
        // 退化路径：直接读 app 自身 mount 视图（/proc/self/mounts 对 self 始终可读）
        val fallback = runCatching {
            readFileHead(File("/proc/self/mounts"), MAX_PROC_FILE_BYTES)
        }.getOrNull()
        if (!fallback.isNullOrBlank()) {
            source = "proc_self_mounts"
            lines = fallback.lineSequence().filter { it.isNotBlank() }.toList()
            status = FACT_STATUS_DEGRADED
            failure = "mount command unusable (${described.first}" +
                (described.second?.let { ": $it" } ?: "") + "); used /proc/self/mounts"
        }
    }

    val matched = lines.filter { MOUNT_STORAGE_PATTERN.containsMatchIn(it) }
    val body = buildString {
        if (source == "mount" && status != FACT_STATUS_OK) {
            // mount 不可用且退化来源也失败：原样保留输出（失败原因在 header 的 failure 里）
            append(result.output)
        } else if (matched.isEmpty()) {
            appendLine("note=no storage/fuse line visible in this mount view")
            appendLine("note=an empty match does not prove the mount does not exist")
        } else {
            matched.forEach { appendLine(it) }
        }
    }
    return addFactCommandEntry(
        zip = zip,
        entryName = "commands/mount_storage.txt",
        command = "mount",
        budget = budget,
        result = result,
        status = status,
        failure = failure,
        facts = listOf(
            "source=$source",
            "filter=$MOUNT_STORAGE_FILTER",
            "totalMountLines=${lines.size}",
            "matchedLines=${matched.size}",
        ),
        body = body,
    )
}

/**
 * MediaProvider mountinfo 条目：与 server 侧 commands/media_provider_mountinfo.txt 对齐。
 *
 * /proc/<pid>/mountinfo 需要 pid；app UID 下 pidof 常不可用或看不到他人进程，
 * 因此顺序为 pidof → ps 快照解析，两者都拿不到时必须显式说明"未取到"，
 * 而不是输出空文件。
 */
private fun addMediaProviderMountInfoFactEntry(
    zip: ZipOutputStream,
    psResult: CommandResult,
    budget: ArchiveBudget?,
): Pair<String, String> {
    val pidofCommand = listOf("pidof") + MEDIA_PROVIDER_PACKAGES
    val pidofResult = runCommand(pidofCommand, FACT_COMMAND_TIMEOUT_SECONDS)
    val pidofStatus = describeCommandResult(pidofResult)
    val psStatus = describeCommandResult(psResult)
    // 只在命令成功时解析 pid：失败输出是异常栈，里面的数字不是 pid。
    val pidofPids = if (pidofStatus.first == FACT_STATUS_OK) {
        parsePids(pidofResult.output)
    } else {
        emptyList()
    }
    val psPids = if (psStatus.first == FACT_STATUS_OK) {
        parseMediaProviderPids(psResult.output)
    } else {
        emptyList()
    }
    val pids = (if (pidofPids.isNotEmpty()) pidofPids else psPids)
        .distinct()
        .take(MAX_MOUNTINFO_PIDS)
    val pidSource = when {
        pidofPids.isNotEmpty() -> "pidof"
        psPids.isNotEmpty() -> "ps"
        else -> "none"
    }

    var readOk = 0
    var readFailed = 0
    val body = buildString {
        if (pids.isEmpty()) {
            appendLine("no MediaProvider pid resolved; /proc/<pid>/mountinfo cannot be read")
            appendLine("pidofStatus=${pidofStatus.first}")
            appendLine("pidofFailure=${pidofStatus.second ?: "none"}")
            appendLine("psStatus=${psStatus.first}")
            appendLine("note=app UID usually cannot see other processes; for authoritative")
            appendLine("     MediaProvider mount state use the server package (root)")
        } else {
            for (pid in pids) {
                appendLine("=== pid=$pid ===")
                try {
                    val text = readFileHead(File("/proc/$pid/mountinfo"), MAX_PROC_FILE_BYTES)
                    val infoLines = text.lineSequence().filter { it.isNotBlank() }.toList()
                    val matched = infoLines.filter { MOUNT_STORAGE_PATTERN.containsMatchIn(it) }
                    readOk++
                    appendLine("totalMountInfoLines=${infoLines.size}")
                    appendLine("matchedLines=${matched.size}")
                    if (matched.isEmpty()) {
                        appendLine("note=no storage/fuse line visible in this mountinfo")
                    } else {
                        matched.take(MAX_MOUNTINFO_LINES).forEach { appendLine(it) }
                        if (matched.size > MAX_MOUNTINFO_LINES) {
                            appendLine("truncatedTo=$MAX_MOUNTINFO_LINES")
                        }
                    }
                } catch (e: Exception) {
                    readFailed++
                    appendLine("readError=${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    val status = when {
        pids.isEmpty() -> FACT_STATUS_UNAVAILABLE
        readOk > 0 -> FACT_STATUS_OK
        else -> FACT_STATUS_FAILED
    }
    val failure = when {
        pids.isEmpty() -> "MediaProvider pid not resolvable under app UID " +
            "(pidof=${pidofStatus.first}, ps=${psStatus.first})"
        readFailed > 0 -> "$readFailed of ${pids.size} mountinfo reads failed (see readError lines)"
        else -> null
    }
    return addFactCommandEntry(
        zip = zip,
        entryName = "commands/media_provider_mountinfo.txt",
        command = pidofCommand.joinToString(" "),
        budget = budget,
        result = pidofResult,
        status = status,
        failure = failure,
        facts = listOf(
            "pidSource=$pidSource",
            "pids=${pids.joinToString(",").ifEmpty { "none" }}",
            "packages=${MEDIA_PROVIDER_PACKAGES.joinToString(",")}",
        ),
        body = body,
    )
}

/** 解析 pidof 输出的 pid 列表（按空白切分，只认纯数字，避免误取异常栈里的数字）。 */
private fun parsePids(output: String): List<Int> =
    output.trim()
        .split(Regex("\\s+"))
        .mapNotNull { it.toIntOrNull() }
        .filter { it > 0 }
        .distinct()

/**
 * 从 ps 快照解析 MediaProvider 的 pid（pidof 不可用时的退化路径）。
 *
 * toybox/toolbox 的 ps 列序都是 USER PID ... NAME，pid 取第 2 列；
 * 进程名取行尾（包名不含空格）。
 */
private fun parseMediaProviderPids(psOutput: String): List<Int> =
    psOutput.lineSequence()
        .filter { it.isNotBlank() }
        .filter { MEDIA_PROVIDER_PROCESS_PATTERN.containsMatchIn(it) }
        .mapNotNull { line -> line.trim().split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() }
        .filter { it > 0 }
        .distinct()
        .toList()

/** 读文件头部（最多 maxBytes 字节）：/proc 文件用，避免整文件入内存。 */
private fun readFileHead(file: File, maxBytes: Int): String {
    val output = java.io.ByteArrayOutputStream()
    java.io.FileInputStream(file).use { input ->
        val buffer = ByteArray(8 * 1024)
        var remaining = maxBytes
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
    }
    return output.toString(Charsets.UTF_8.name())
}

private fun runCommand(
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
private const val FACT_COMMAND_TIMEOUT_SECONDS = 5L

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

private data class CommandResult(
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
private enum class ArchiveWriteResult {
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
private class ArchiveBudget(private val maxTotalBytes: Long) {
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
private fun addTextEntry(
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

