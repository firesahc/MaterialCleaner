package me.gm.cleaner.runtime.mediaprovider.hook;

import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.File;
import java.util.ArrayList;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

import me.gm.cleaner.core.storage.redirect.databus.DataBus;
import org.json.JSONObject;

/**
 * MediaProvider insertFile Hook。
 *
 * <p>继承 {@link AbstractGuardedHook} 获得统一防护：任何 handler 异常不外抛、
 * 参数回滚、每方法熔断 —— 与 FuseJavaGate 同进程统一异常策略。
 * 本 Hook 对 args 槽位的修改只发生在 {@link MediaStore.MediaColumns#DATA}
 * 重定向一步且位于方法末尾，异常路径上由 {@link AbstractGuardedHook} 的
 * 槽位快照兜底即可，无需额外 guardedArgIndexes（传空数组）；
 * {@link #ensureUniqueFileColumns} 内部以「先算后写」隔离 ContentValues 写入，
 * 中途异常时宿主拿到未污染的 values。
 */
@RequiresApi(api = Build.VERSION_CODES.Q)
public class InsertHooker extends AbstractGuardedHook {
    private final static String DIRECTORY_THUMBNAILS = ".thumbnails";

    private final static int IMAGES_MEDIA = 1;
    private final static int IMAGES_MEDIA_ID = 2;
    private final static int IMAGES_MEDIA_ID_THUMBNAIL = 3;
    private final static int IMAGES_THUMBNAILS = 4;
    private final static int IMAGES_THUMBNAILS_ID = 5;

    private final static int AUDIO_MEDIA = 100;
    private final static int AUDIO_MEDIA_ID = 101;
    private final static int AUDIO_MEDIA_ID_GENRES = 102;
    private final static int AUDIO_MEDIA_ID_GENRES_ID = 103;
    private final static int AUDIO_GENRES = 106;
    private final static int AUDIO_GENRES_ID = 107;
    private final static int AUDIO_GENRES_ID_MEMBERS = 108;
    private final static int AUDIO_GENRES_ALL_MEMBERS = 109;
    private final static int AUDIO_PLAYLISTS = 110;
    private final static int AUDIO_PLAYLISTS_ID = 111;
    private final static int AUDIO_PLAYLISTS_ID_MEMBERS = 112;
    private final static int AUDIO_PLAYLISTS_ID_MEMBERS_ID = 113;
    private final static int AUDIO_ARTISTS = 114;
    private final static int AUDIO_ARTISTS_ID = 115;
    private final static int AUDIO_ALBUMS = 116;
    private final static int AUDIO_ALBUMS_ID = 117;
    private final static int AUDIO_ARTISTS_ID_ALBUMS = 118;
    private final static int AUDIO_ALBUMART = 119;
    private final static int AUDIO_ALBUMART_ID = 120;
    private final static int AUDIO_ALBUMART_FILE_ID = 121;

    private final static int VIDEO_MEDIA = 200;
    private final static int VIDEO_MEDIA_ID = 201;
    private final static int VIDEO_MEDIA_ID_THUMBNAIL = 202;
    private final static int VIDEO_THUMBNAILS = 203;
    private final static int VIDEO_THUMBNAILS_ID = 204;

    private final static int DOWNLOADS = 800;
    private final static int DOWNLOADS_ID = 801;

    private final MediaProviderHook mHook;
    private final ClassLoader mClassLoader;

    public InsertHooker(MediaProviderHook hook, ClassLoader classLoader) {
        super("InsertHooker", "insertFile", new int[0]);
        mHook = hook;
        mClassLoader = classLoader;
    }

    private final static Pattern KNOWN_APP_DIR_PATHS = Pattern.compile(
            "(?i)(^/storage/[^/]+/(?:([0-9]+)/)?Android/(?:data|media|obb|sandbox)/)([^/]+)(/.*)?");

    private String extractPathOwnerPackageName(String path) {
        final var m = KNOWN_APP_DIR_PATHS.matcher(path);
        if (m.matches()) {
            return m.group(3);
        }
        return null;
    }

    @Override
    protected void handleBefore(XC_MethodHook.MethodHookParam param) throws Throwable {
        if (mHook.isFuseThread()) {
            Log.d("MC_REDIRECT", "[InsertHooker] Skipping - on FUSE thread");
            return;
        }
        /** ARGUMENTS */
        int match;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            match = (int) param.args[2];
        } else {
            match = (int) param.args[1];
        }
        Uri uri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            uri = (Uri) param.args[3];
        } else {
            uri = (Uri) param.args[2];
        }
        Bundle extras;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            extras = (Bundle) param.args[4];
        } else {
            extras = Bundle.EMPTY;
        }
        ContentValues values;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            values = (ContentValues) param.args[5];
        } else {
            values = (ContentValues) param.args[3];
        }
        int mediaType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            mediaType = (int) param.args[6];
        } else {
            mediaType = (int) param.args[4];
        }

        /** PARSE */
        final var mimeType = values.getAsString(MediaStore.MediaColumns.MIME_TYPE);
        final var wasPathEmpty = wasPathEmpty(values);
        if (wasPathEmpty) {
            // Generate path when undefined
            ensureUniqueFileColumns(param.thisObject, match, uri, values, mimeType);
        }
        final var data = values.getAsString(MediaStore.MediaColumns.DATA);
        if (wasPathEmpty) {
            // Restore to allow mkdir
            values.remove(MediaStore.MediaColumns.DATA);
        }

        /** REDIRECT */
        final var callingPkg = mHook.getCallingPackage(param.thisObject);
        final var originalData = data;

        final var localMountedPath = HookPolicyCache.INSTANCE.getMountedPath(callingPkg, data);
        if (localMountedPath != null && !data.equals(localMountedPath) &&
                TextUtils.isEmpty(extractPathOwnerPackageName(localMountedPath))) {
            values.put(MediaStore.MediaColumns.DATA, localMountedPath);
            Log.i("MC_REDIRECT", "[InsertHooker] PATH REDIRECTED (local cache): " + originalData + " -> " + localMountedPath);
            emitRedirectNotice(callingPkg, data, localMountedPath, "INSERT");
            return;
        }
    }

    private boolean wasPathEmpty(ContentValues values) {
        return !values.containsKey(MediaStore.MediaColumns.DATA)
                || values.getAsString(MediaStore.MediaColumns.DATA).isEmpty();
    }

    /**
     * 补全缺失的文件列并计算唯一 DATA 路径。
     *
     * <p><b>先算后写：</b>全部计算与写入作用于 {@code working} 副本，
     * 全部成功后才在方法末尾一次性回写 {@code values}；
     * 中途任何异常时 {@code values} 保持进入时原状，
     * 由 AbstractGuardedHook 兜底 return 后宿主拿到未污染的 values。
     */
    private void ensureUniqueFileColumns(Object mp, int match, Uri uri,
                                         ContentValues values, String mimeType) {
        // 先算后写：working 是 values 的副本，所有中间计算只作用于它。
        final ContentValues working = new ContentValues(values);
        var defaultPrimary = Environment.DIRECTORY_DOWNLOADS;
        String defaultSecondary = null;
        switch (match) {
            case AUDIO_MEDIA:
            case AUDIO_MEDIA_ID:
                defaultPrimary = Environment.DIRECTORY_MUSIC;
                break;
            case VIDEO_MEDIA:
            case VIDEO_MEDIA_ID:
                defaultPrimary = Environment.DIRECTORY_MOVIES;
                break;
            case IMAGES_MEDIA:
            case IMAGES_MEDIA_ID:
                defaultPrimary = Environment.DIRECTORY_PICTURES;
                break;
            case AUDIO_ALBUMART:
            case AUDIO_ALBUMART_ID:
                defaultPrimary = Environment.DIRECTORY_MUSIC;
                defaultSecondary = DIRECTORY_THUMBNAILS;
                break;
            case VIDEO_THUMBNAILS:
            case VIDEO_THUMBNAILS_ID:
                defaultPrimary = Environment.DIRECTORY_MOVIES;
                defaultSecondary = DIRECTORY_THUMBNAILS;
                break;
            case IMAGES_THUMBNAILS:
            case IMAGES_THUMBNAILS_ID:
                defaultPrimary = Environment.DIRECTORY_PICTURES;
                defaultSecondary = DIRECTORY_THUMBNAILS;
                break;
            case AUDIO_PLAYLISTS:
            case AUDIO_PLAYLISTS_ID:
                defaultPrimary = Environment.DIRECTORY_MUSIC;
                break;
            case DOWNLOADS:
            case DOWNLOADS_ID:
                defaultPrimary = Environment.DIRECTORY_DOWNLOADS;
                break;
        }
        // Give ourselves reasonable defaults when missing
        if (TextUtils.isEmpty(values.getAsString(MediaStore.MediaColumns.DISPLAY_NAME))) {
            working.put(MediaStore.MediaColumns.DISPLAY_NAME, String.valueOf(System.currentTimeMillis()));
        }
        // Use default directories when missing
        if (TextUtils.isEmpty(values.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))) {
            if (defaultSecondary != null) {
                working.put(MediaStore.MediaColumns.RELATIVE_PATH, defaultPrimary + "/" + defaultSecondary);
            } else {
                working.put(MediaStore.MediaColumns.RELATIVE_PATH, defaultPrimary + "/");
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            final var resolvedVolumeName = (String) XposedHelpers.callMethod(
                    mp, "resolveVolumeName", uri);
            final var volumePath = (File) XposedHelpers.callMethod(
                    mp, "getVolumePath", resolvedVolumeName);

            final var fileUtilsClass = XposedHelpers.findClass(
                    "com.android.providers.media.util.FileUtils", mClassLoader);
            final var isFuseThread = (boolean) XposedHelpers.callMethod(mp, "isFuseThread");
            XposedHelpers.callStaticMethod(fileUtilsClass, "sanitizeValues", working, !isFuseThread);
            XposedHelpers.callStaticMethod(
                    fileUtilsClass, "computeDataFromValues", working, volumePath, isFuseThread);

            var res = new File(working.getAsString(MediaStore.MediaColumns.DATA));
            res = (File) XposedHelpers.callStaticMethod(
                    fileUtilsClass, "buildUniqueFile",
                    res.getParentFile(), mimeType, res.getName());

            working.put(MediaStore.MediaColumns.DATA, res.getAbsolutePath());
        } else {
            final var resolvedVolumeName = (String) XposedHelpers.callMethod(
                    mp, "resolveVolumeName", uri);

            final var relativePath = XposedHelpers.callMethod(
                    mp, "sanitizePath",
                    working.getAsString(MediaStore.MediaColumns.RELATIVE_PATH)
            );
            final var displayName = XposedHelpers.callMethod(
                    mp, "sanitizeDisplayName",
                    working.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)
            );

            var res = (File) XposedHelpers.callMethod(
                    mp, "getVolumePath", resolvedVolumeName);
            res = (File) XposedHelpers.callStaticMethod(
                    Environment.class, "buildPath", res, relativePath);
            res = (File) XposedHelpers.callStaticMethod(
                    FileUtils.class, "buildUniqueFile", res, mimeType, displayName);

            working.put(MediaStore.MediaColumns.DATA, res.getAbsolutePath());
        }

        // 全部计算成功，一次性回写：values 最终状态与旧成功路径一致
        // （逐键覆盖 + 移除 working 中被删除的键）。
        values.putAll(working);
        for (final String key : new ArrayList<>(values.keySet())) {
            if (!working.containsKey(key)) {
                values.remove(key);
            }
        }
    }

    /**
     * 向 DataBus 写入重定向提示事件。
     * 异步写入，不影响主流程。
     */
    private static void emitRedirectNotice(String packageName, String originalPath,
                                           String mountedPath, String type) {
        try {
            final var event = new JSONObject();
            event.put("schemaVersion", 1);
            event.put("timeMillis", System.currentTimeMillis());
            event.put("packageName", packageName);
            event.put("originalPath", originalPath);
            event.put("mountedPath", mountedPath);
            event.put("type", type);
            event.put("reason", "REDIRECTED_TO_INTERNAL");
            final var seq = HookDataBusBridge.INSTANCE.writeEvent(
                    DataBus.EVENT_REDIRECT_NOTICE, event.toString());
            if (seq >= 0) {
                HookDataBusBridge.INSTANCE.signal(DataBus.SIGNAL_REDIRECT_NOTICE_EVENTS_CHANGED);
            }
        } catch (Exception e) {
            Log.e("MC_REDIRECT", "[InsertHooker] DataBus write failed", e);
        }
    }
}
