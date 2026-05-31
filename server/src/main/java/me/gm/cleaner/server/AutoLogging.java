package me.gm.cleaner.server;

import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.IOException;


public class AutoLogging {
    public static final int MODE_CONTINUOUSLY = 1;
    private static final File sLogsDir = new File("/data/local/tmp/cleaner_logs");

    public AutoLogging(final android.content.pm.PackageInfo packageInfo) {
    }

    private void grabLogs(final Object logName, final String cmd) {
        sLogsDir.mkdirs();
        try {
            final var logFile = new File(sLogsDir, logName.toString() + ".log");
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
