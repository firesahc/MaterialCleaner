package me.gm.cleaner.runtime.server;

import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;

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
}
