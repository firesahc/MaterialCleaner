package me.gm.cleaner.runtime.server

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.ZipOutputStream

/**
 * 诊断包内文件内容的取用与截断策略。
 *
 * 职责边界：本文件只负责"把一个文件（或一个目录下的若干文件）按预算放进 zip"，
 * 具体包含三种取用策略：
 * - [addDirectoryFiles]：目录类条目（DataBus 快照/信号/游标/事件等），
 *   附一份可审计的清单；
 * - [addFileTail]：纯尾部取用，适用于"当前状态"类文件；
 * - [addFileHeadAndTail]：首尾两段取用，适用于持续采集的日志文件——
 *   头段覆盖服务启动瞬间（启动失败证据位于采集周期开头），
 *   尾段覆盖最近现场；只取尾部会丢掉启动期上下文。
 *
 * 与 [DiagnosticArchive] 分离的动因：后者负责"诊断包的整体编排"（摘要、状态、
 * 快照、取证命令），而文件取用有自己的预算语义与截断策略，
 * 两者是并列职责。函数置于顶层 internal，故既有调用点无需改动。
 */
internal const val MAX_TEXT_FILE_BYTES = 512 * 1024

/**
 * 导出目录下的普通文件（按最后修改时间倒序），并附一份可审计清单。
 *
 * @param maxFiles 最多纳入的文件数。
 */
internal fun addDirectoryFiles(
    zip: ZipOutputStream,
    dir: File,
    entryPrefix: String,
    maxFiles: Int,
) {
    val all = dir.listFiles()
        ?.filter { isRegularFileNoFollow(it) }
        ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })
        ?: emptyList()
    val files = all.take(maxFiles)
    addText(zip, "$entryPrefix/manifest.txt", buildString {
        appendLine("path=${dir.path}")
        appendLine("exists=${dir.exists()}")
        // 可审计：记录目录总数，排障者可判断证据是否完整
        appendLine("total=${all.size}")
        appendLine("included=${files.size}")
        for (file in files) {
            appendLine("${file.name}\tsize=${file.length()}\tmodified=${file.lastModified()}")
        }
    })
    for (file in files) {
        addFileTail(zip, file, "$entryPrefix/${file.name}", MAX_TEXT_FILE_BYTES)
    }
}

/**
 * 取文件首尾两段并标注中段省略。
 *
 * 头段覆盖服务启动瞬间（启动失败证据位于采集周期的开头），尾段覆盖最近现场。
 * 只取尾部会丢掉启动期上下文，而"排查启动或崩溃问题"正是该功能的目的。
 */
internal fun addFileHeadAndTail(zip: ZipOutputStream, file: File, entryName: String, maxBytes: Int) {
    runCatching {
        val headBytes = maxBytes / 2
        val tailBytes = maxBytes - headBytes
        val content = buildString {
            appendLine("path=${file.path}")
            appendLine("size=${file.length()}")
            appendLine("modified=${file.lastModified()}")
            if (file.length() > maxBytes) {
                appendLine("truncated=middle omitted, headBytes=$headBytes, tailBytes=$tailBytes")
            }
            appendLine()
            append(readFileHead(file, headBytes))
            if (file.length() > maxBytes) {
                appendLine()
                appendLine("... [middle omitted: ${file.length() - maxBytes} bytes] ...")
                appendLine()
            }
            append(readFileTail(file, tailBytes))
        }
        addText(zip, entryName, content)
    }.onFailure {
        addText(zip, "$entryName.error.txt", it.stackTraceToString())
    }
}

internal fun readFileHead(file: File, maxBytes: Int): String {
    val output = ByteArrayOutputStream()
    FileInputStream(file).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var remaining = maxBytes
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
    }
    return output.toString(StandardCharsets.UTF_8.name())
}

/** 取文件尾部 [maxBytes] 字节。 */
internal fun addFileTail(zip: ZipOutputStream, file: File, entryName: String, maxBytes: Int) {
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

internal fun readFileTail(file: File, maxBytes: Int): String {
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

/** 不跟随符号链接的普通文件判定（避免把链接指向的内容重复计入）。 */
internal fun isRegularFileNoFollow(file: File): Boolean =
    Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
