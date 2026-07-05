package me.gm.cleaner.runtime.server;

import android.ddm.DdmHandleAppName;
import android.os.Build;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

import me.gm.cleaner.runtime.server.BuildConfig;

public class CleanerServerLoader {

    public static void main(final String[] args) throws IOException, ClassNotFoundException,
            NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Log.i(BuildConfig.LIBRARY_PACKAGE_NAME,
                "Starting Cleaner server v" + BuildConfig.VERSION_CODE +
                        " on " + Build.VERSION.SDK_INT
        );
        var libClassLoader = ClassLoader.getSystemClassLoader();

        System.loadLibrary("android");
        System.loadLibrary("compiler_rt");
        System.loadLibrary("jnigraphics");
        libClassLoader.loadClass(CleanerServerLoader.class.getName())
                .getDeclaredMethod("entry", String[].class)
                .invoke(null, (Object) args);
    }

    public static void entry(final String[] args) {
        DdmHandleAppName.setAppName("cleaner_server", 0);

        Looper.prepareMainLooper();
        new CleanerServer().onStorageManagerServiceReady();
        Looper.loop();
        throw new RuntimeException("Main thread loop unexpectedly exited");
    }
}
