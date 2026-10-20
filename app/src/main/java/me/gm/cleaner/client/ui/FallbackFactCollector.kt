package me.gm.cleaner.client.ui

import android.system.Os
import java.io.File
import java.util.zip.ZipOutputStream

/**
 * 无 root 权限下的事实类证据采集。
 *
 * 职责边界：本文件只负责"在没有 root 时用什么替代手段取事实"，
 * 含身份/权限上下文、SELinux 模式、进程视图、挂载视图与 MediaProvider mountinfo。
 * 与同包的降级包组装（隐私声明、摘要、清单、zip 写入、条目预算）是并列职责。
 *
 * 状态词表：
 * - ok：命令成功且有输出；
 * - degraded：主命令不可用，但已用替代来源取到部分事实；
 * - empty：命令退出码 0 但无输出（多为权限受限或无匹配项）；
 * - timeout：超时被强杀；
 * - failed：命令不可执行或非 0 退出；
 * - unavailable：取证前提不可得（如 pid 无法解析），根本没有取。
 *
 * "取到了什么"与"为什么没取到"必须分开陈述：每条条目都同时给出
 * status 与 failure，避免排障者把"看不到他人日志"误读成"现象不存在"。
 */
internal const val FACT_STATUS_OK = "ok"
internal const val FACT_STATUS_DEGRADED = "degraded"
internal const val FACT_STATUS_EMPTY = "empty"
internal const val FACT_STATUS_TIMEOUT = "timeout"
internal const val FACT_STATUS_FAILED = "failed"
internal const val FACT_STATUS_UNAVAILABLE = "unavailable"

/** 进程匹配口径：与 server 侧 ps | grep 对齐（大小写不敏感为超集）。 */
internal val CLEANER_PROCESS_PATTERN =
    Regex("cleaner|material|gm\\.cleaner", RegexOption.IGNORE_CASE)

internal val MEDIA_PROVIDER_PROCESS_PATTERN =
    Regex("media[._]?provider|providers[._]media", RegexOption.IGNORE_CASE)

/** 挂载行过滤口径：与 server 侧 mount | grep 对齐。 */
internal const val MOUNT_STORAGE_FILTER = "/storage|/mnt/runtime|/Android/data|fuse"

internal val MOUNT_STORAGE_PATTERN = Regex(MOUNT_STORAGE_FILTER, RegexOption.IGNORE_CASE)

/** MediaProvider 包名候选：与 server 侧 pidof 候选一致。 */
internal val MEDIA_PROVIDER_PACKAGES = listOf(
    "com.android.providers.media.module",
    "com.google.android.providers.media.module",
    "com.android.providers.media",
)

/** mountinfo 最多解析的 pid 数：防止异常情况下条目膨胀。 */
internal const val MAX_MOUNTINFO_PIDS = 4

/** mountinfo 正文最多保留的行数（与 server 侧 head -200 对齐）。 */
internal const val MAX_MOUNTINFO_LINES = 200

/** 读取 /proc 文件的单文件字节上限。 */
internal const val MAX_PROC_FILE_BYTES = 256 * 1024

/**
 * 事实类取证条目集合。
 *
 * 顺序按排障价值排列：身份/权限上下文 → SELinux 模式 → 进程视图 → 挂载视图
 * → MediaProvider mountinfo。全部条目共享同一份 ps 快照，避免重复采集造成
 * 视图漂移（同一包内两条 ps 结果不一致会误导排障）。
 */
internal fun addFactCommandEntries(
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
internal fun addFactCommandEntry(
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
internal fun describeCommandResult(result: CommandResult): Pair<String, String?> {
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
internal fun addIdentityFactEntry(
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
internal fun appIdentityFacts(): List<String> = buildList {
    add("appUid=${runCatching { Os.getuid() }.getOrDefault(-1)}")
    add("appPid=${runCatching { Os.getpid() }.getOrDefault(-1)}")
    add("appGid=${runCatching { Os.getgid() }.getOrDefault(-1)}")
    add("selinuxContext=${readProcSelfAttrCurrent() ?: "unavailable"}")
}

/** 读取 app 自身 SELinux 上下文；不可读时返回 null（由调用方记为 unavailable）。 */
internal fun readProcSelfAttrCurrent(): String? = runCatching {
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
internal fun addProcessFactEntry(
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
internal fun addMountStorageFactEntry(
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
internal fun addMediaProviderMountInfoFactEntry(
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
internal fun parsePids(output: String): List<Int> =
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
internal fun parseMediaProviderPids(psOutput: String): List<Int> =
    psOutput.lineSequence()
        .filter { it.isNotBlank() }
        .filter { MEDIA_PROVIDER_PROCESS_PATTERN.containsMatchIn(it) }
        .mapNotNull { line -> line.trim().split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() }
        .filter { it > 0 }
        .distinct()
        .toList()

/** 读文件头部（最多 maxBytes 字节）：/proc 文件用，避免整文件入内存。 */
internal fun readFileHead(file: File, maxBytes: Int): String {
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