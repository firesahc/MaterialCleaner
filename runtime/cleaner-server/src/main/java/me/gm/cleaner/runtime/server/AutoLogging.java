package me.gm.cleaner.runtime.server;

import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;

import me.gm.cleaner.runtime.server.BuildConfig;


public class AutoLogging {
    public static final int MODE_CONTINUOUSLY = 1;
    private static final File sLogsDir = new File("/data/local/tmp/cleaner_logs");

    public AutoLogging(final android.content.pm.PackageInfo packageInfo) {
    }

    private static File prepareLogsDir() throws IOException {
        final var path = sLogsDir.toPath();
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
                (Files.isSymbolicLink(path) ||
                        !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))) {
            Files.delete(path);
        }
        Files.createDirectories(path);
        setOwnerOnly(sLogsDir, true);
        return sLogsDir;
    }

    private static File createLogFile(final Object logName) throws IOException {
        final var dir = prepareLogsDir();
        final var logFile = Files.createTempFile(
                dir.toPath(), logName.toString() + "-", ".log").toFile();
        setOwnerOnly(logFile, false);
        return logFile;
    }

    private static void setOwnerOnly(final File file, final boolean executable) {
        file.setReadable(false, false);
        file.setWritable(false, false);
        file.setExecutable(false, false);
        file.setReadable(true, true);
        file.setWritable(true, true);
        if (executable) {
            file.setExecutable(true, true);
        }
    }

    private void grabLogs(final Object logName, final String cmd) {
        try {
            final var logFile = createLogFile(logName);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                new ProcessBuilder("sh", "-c", cmd)
                        .redirectOutput(logFile)
                        .start();
            }
        } catch (final IOException e) {
            Log.w(BuildConfig.LIBRARY_PACKAGE_NAME, "grabLogs failed", e);
        }
    }

    public void registerBootShutdownReceiver(int mode) {
        if (mode == MODE_CONTINUOUSLY) {
            grabLogs(System.currentTimeMillis(),
                    "/system/bin/logcat -b main,system,crash");
        }
    }

    /**
     * 只读统计日志目录的总大小和文件数量。
     * 不创建、不删除、不修改任何文件。
     * <p>
     * 统计口径：仅统计目录第一层中的普通文件，不递归子目录，不跟随符号链接。
     *
     * @return Bundle 包含 totalBytes (long)、fileCount (int)、available (boolean)
     */
    public Bundle getLogDirStats() {
        final var result = new Bundle();
        try {
            final var path = sLogsDir.toPath();

            // 读取目录属性，区分：目录明确不存在、目录状态不可用、正常目录
            BasicFileAttributes dirAttrs;
            try {
                dirAttrs = Files.readAttributes(
                        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
                // 目录明确不存在，返回零占用
                result.putLong("totalBytes", 0L);
                result.putInt("fileCount", 0);
                result.putBoolean("available", true);
                return result;
            } catch (Exception e) {
                // 其他 I/O 异常，无法确认目录状态，不伪装成零占用
                result.putLong("totalBytes", 0L);
                result.putInt("fileCount", 0);
                result.putBoolean("available", false);
                return result;
            }

            if (!dirAttrs.isDirectory()) {
                // 路径存在但不是目录（可能是符号链接或普通文件），无法统计
                result.putLong("totalBytes", 0L);
                result.putInt("fileCount", 0);
                result.putBoolean("available", false);
                return result;
            }

            // 正常目录：统计第一层普通文件
            long totalBytes = 0L;
            int fileCount = 0;

            try (var stream = Files.list(path)) {
                var iterator = stream.iterator();
                while (iterator.hasNext()) {
                    var file = iterator.next();
                    try {
                        BasicFileAttributes fileAttrs = Files.readAttributes(
                                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        if (fileAttrs.isRegularFile()) {
                            totalBytes += fileAttrs.size();
                            fileCount++;
                        }
                        // 如果不是普通文件（目录、符号链接等），静默跳过
                    } catch (NoSuchFileException e) {
                        // 文件在遍历过程中已被删除，静默跳过
                    } catch (Exception e) {
                        // 无法读取单个文件属性，统计不可用，不伪装成完整结果
                        result.putLong("totalBytes", totalBytes);
                        result.putInt("fileCount", fileCount);
                        result.putBoolean("available", false);
                        return result;
                    }
                }
            }

            result.putLong("totalBytes", totalBytes);
            result.putInt("fileCount", fileCount);
            result.putBoolean("available", true);
        } catch (Exception e) {
            Log.w(BuildConfig.LIBRARY_PACKAGE_NAME, "getLogDirStats failed", e);
            result.putLong("totalBytes", 0L);
            result.putInt("fileCount", 0);
            result.putBoolean("available", false);
        }
        return result;
    }
}
