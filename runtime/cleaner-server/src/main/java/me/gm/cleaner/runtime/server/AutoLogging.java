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
import java.util.concurrent.TimeUnit;

import me.gm.cleaner.core.config.ServicePreferences;
import me.gm.cleaner.runtime.server.BuildConfig;


public class AutoLogging {
    private static final File sLogsDir = new File("/data/local/tmp/cleaner_logs");

    /**
     * 当前日志采集进程；非 null 表示正在写日志文件。
     * 由本类 synchronized 公开方法独占维护，状态对账在单点收敛。
     */
    private volatile Process mLogProcess;

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

    /**
     * 按当前配置对账日志采集进程的实际状态：应启用而未运行则启动，应停用而仍在运行则停止。
     * <p>
     * 幂等：状态已一致时为无操作；可在任意线程调用。
     */
    public synchronized void reconcileWithPreferences() {
        final boolean desired = ServicePreferences.INSTANCE.getAutoLogging()
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
        // 结算死引用：进程被外部终止等异常退出时，不把"引用存在"误判为"仍在运行"。
        // 结算后若 desired 仍成立，走下方 start 分支即自然完成自愈重启。
        if (mLogProcess != null && !mLogProcess.isAlive()) {
            Log.w(BuildConfig.LIBRARY_PACKAGE_NAME, "logcat process exited unexpectedly");
            mLogProcess = null;
        }
        if (desired && mLogProcess == null) {
            startContinuousLogging();
        } else if (!desired && mLogProcess != null) {
            shutdownProcess("disabled by preference");
        }
    }

    /**
     * 无条件停止日志采集进程，用于服务退出前的子进程回收。
     * <p>
     * 幂等：进程未运行时为无操作。
     */
    public synchronized void shutdown() {
        if (mLogProcess != null) {
            shutdownProcess("server exiting");
        }
    }

    /**
     * 启动持续日志采集：logcat 输出被重定向到日志目录中的新文件。
     * <p>
     * 直接启动 logcat 可执行文件，不经 {@code sh -c}：停止时
     * {@code destroy()} 无条件命中采集进程本身，不依赖 shell 对单条
     * 命令的 exec 替换优化。仅在调用方已持有状态锁时执行。
     */
    private void startContinuousLogging() {
        File logFile = null;
        try {
            logFile = createLogFile(System.currentTimeMillis());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mLogProcess = new ProcessBuilder(
                        "/system/bin/logcat", "-b", "main,system,crash")
                        .redirectOutput(logFile)
                        .start();
                Log.i(BuildConfig.LIBRARY_PACKAGE_NAME,
                        "auto logging started: " + logFile.getName());
            }
        } catch (final IOException e) {
            // 进程启动失败时本次创建的文件不可保留，防止反复失败累积空文件。
            if (logFile != null) {
                try {
                    Files.deleteIfExists(logFile.toPath());
                } catch (final IOException ignored) {
                    // 删除失败最多残留 0 字节文件，不升级告警
                }
            }
            Log.w(BuildConfig.LIBRARY_PACKAGE_NAME, "startContinuousLogging failed", e);
        }
    }

    /**
     * 停止并回收采集进程：先 SIGTERM 优雅退出，超时则 SIGKILL 兜底。
     * 先清引用再销毁，防止停止过程中的并发对账命中半完成状态。
     */
    private void shutdownProcess(final String reason) {
        final var process = mLogProcess;
        mLogProcess = null;
        Log.i(BuildConfig.LIBRARY_PACKAGE_NAME, "auto logging stopped: " + reason);
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } catch (final RuntimeException e) {
            Log.w(BuildConfig.LIBRARY_PACKAGE_NAME, "shutdownProcess wait failed", e);
            process.destroyForcibly();
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
