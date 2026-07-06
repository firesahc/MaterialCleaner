package me.gm.cleaner.runtime.mediaprovider.hook;

import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import me.gm.cleaner.core.storage.redirect.databus.DataBus;

import org.json.JSONObject;

/**
 * MediaProvider Hook 进程内的 query session 缓存。
 *
 * query 结果中的路径映射是短期会话状态，FUSE Java 入口在同一进程内即可判断。
 * root server 只通过 DataBus lease 异步维护临时目录，不再参与热路径判断。
 */
final class QuerySessionCache {
    private static final String TAG = "QuerySessionCache";
    private static final long SESSION_TTL_MS = 5_000L;
    private static final Pattern ANDROID_DATA_PATH =
            Pattern.compile("(?i)^/storage/[^/]+/(?:[0-9]+/)?Android/data(?:/.*)?");
    private static final Pattern KNOWN_APP_DIR_PATHS = Pattern.compile(
            "(?i)(^/storage/[^/]+/(?:([0-9]+)/)?Android/(?:data|obb)/)([^/]+)(/.*)?");

    private static final Object LOCK = new Object();
    private static final Map<Integer, List<Entry>> SESSIONS_BY_UID = new HashMap<>();

    private QuerySessionCache() {
    }

    static void recordQueriedPaths(String packageName, int uid, List<String> paths) {
        if (paths == null || paths.isEmpty() || HookPolicyCache.INSTANCE.isDenied(packageName)) {
            return;
        }

        final var now = System.currentTimeMillis();
        final var expiresAt = now + SESSION_TTL_MS;
        var shouldSignalLeases = false;

        synchronized (LOCK) {
            gcLocked(now);

            for (final var path : paths) {
                if (path == null || path.isEmpty()) {
                    continue;
                }
                final var mountedPath = HookPolicyCache.INSTANCE.getMountedPath(packageName, path);
                if (mountedPath == null || mountedPath.equals(path)) {
                    continue;
                }

                if (HookPolicyCache.INSTANCE.getRecordExternalAppSpecificStorage()) {
                    final var entry = new Entry(packageName, uid, path, mountedPath, expiresAt);
                    SESSIONS_BY_UID
                            .computeIfAbsent(uid, ignored -> new ArrayList<>())
                            .add(entry);
                    shouldSignalLeases |= writeLease(entry);
                } else if (HookPolicyCache.INSTANCE.getAggressivelyPromptForReadingMediaFiles()) {
                    emitMediaNotFound(packageName, path, true);
                    break;
                } else {
                    break;
                }
            }
        }

        if (shouldSignalLeases) {
            DataBus.INSTANCE.signal(DataBus.SIGNAL_QUERY_SESSION_LEASES_CHANGED);
        }
    }

    static boolean maybeAccessQueriedPath(String packageName, int uid, String mountedPath) {
        if (mountedPath == null || mountedPath.isEmpty()) {
            return false;
        }

        final var now = System.currentTimeMillis();
        synchronized (LOCK) {
            gcLocked(now);
            final var entries = SESSIONS_BY_UID.get(uid);
            if (entries == null || entries.isEmpty()) {
                return false;
            }
            final var iterator = entries.iterator();
            while (iterator.hasNext()) {
                final var entry = iterator.next();
                if (!entry.packageName.equals(packageName) ||
                        !entry.mountedPath.equals(mountedPath)) {
                    continue;
                }
                if (!shouldNotifyMediaNotFound(entry.packageName, entry.originalPath)) {
                    return false;
                }
                emitMediaNotFound(entry.packageName, entry.originalPath, false);
                iterator.remove();
                if (entries.isEmpty()) {
                    SESSIONS_BY_UID.remove(uid);
                }
                return true;
            }
        }
        return false;
    }

    private static void gcLocked(long now) {
        final var iterator = SESSIONS_BY_UID.entrySet().iterator();
        while (iterator.hasNext()) {
            final var mapEntry = iterator.next();
            mapEntry.getValue().removeIf(entry -> entry.expiresAt <= now);
            if (mapEntry.getValue().isEmpty()) {
                iterator.remove();
            }
        }
    }

    private static boolean writeLease(Entry entry) {
        try {
            final var event = new JSONObject();
            event.put("schemaVersion", 1);
            event.put("createdAt", System.currentTimeMillis());
            event.put("expiresAt", entry.expiresAt);
            event.put("packageName", entry.packageName);
            event.put("uid", entry.uid);
            event.put("originalPath", entry.originalPath);
            event.put("mountedPath", entry.mountedPath);
            event.put("sourceLayer", "MEDIA_PROVIDER_QUERY_SESSION");
            return DataBus.INSTANCE.writeLease(
                    DataBus.LEASE_QUERY_SESSIONS,
                    leaseKey(entry),
                    event.toString()
            );
        } catch (Exception e) {
            Log.e(TAG, "writeLease failed", e);
            return false;
        }
    }

    private static String leaseKey(Entry entry) {
        final var hash = Integer.toHexString(
                (entry.packageName + "\n" + entry.uid + "\n" +
                        entry.originalPath + "\n" + entry.mountedPath).hashCode());
        return entry.uid + "-" + entry.packageName + "-" + hash;
    }

    private static boolean shouldNotifyMediaNotFound(String packageName, String originalPath) {
        if (HookPolicyCache.INSTANCE.isDenied(packageName)) {
            return false;
        }
        return !(ANDROID_DATA_PATH.matcher(originalPath).matches() &&
                !isKnownAppDirPaths(originalPath, packageName));
    }

    private static boolean isKnownAppDirPaths(String path, String packageName) {
        final var matcher = KNOWN_APP_DIR_PATHS.matcher(path);
        return matcher.matches() && packageName.equals(matcher.group(3));
    }

    private static void emitMediaNotFound(String packageName, String originalPath, boolean aggressive) {
        try {
            final var event = new JSONObject();
            event.put("schemaVersion", 1);
            event.put("timeMillis", System.currentTimeMillis());
            event.put("packageName", packageName);
            event.put("originalPath", originalPath);
            event.put("mountedPath", originalPath);
            event.put("type", "QUERY");
            event.put("reason", aggressive ? "MEDIA_NOT_FOUND_AGGRESSIVE" : "MEDIA_NOT_FOUND");
            final var seq = DataBus.INSTANCE.writeEvent(DataBus.EVENT_REDIRECT_NOTICE, event.toString());
            if (seq >= 0) {
                DataBus.INSTANCE.signal(DataBus.SIGNAL_REDIRECT_NOTICE_EVENTS_CHANGED);
            }
        } catch (Exception e) {
            Log.e(TAG, "emitMediaNotFound failed", e);
        }
    }

    private static final class Entry {
        final String packageName;
        final int uid;
        final String originalPath;
        final String mountedPath;
        final long expiresAt;

        Entry(String packageName, int uid, String originalPath, String mountedPath, long expiresAt) {
            this.packageName = packageName;
            this.uid = uid;
            this.originalPath = originalPath;
            this.mountedPath = mountedPath;
            this.expiresAt = expiresAt;
        }
    }
}
