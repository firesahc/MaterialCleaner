package me.gm.cleaner.core.common;

import java.util.Locale;

public class AndroidFilesystemConfig {
    public static final int AID_APP_START = 10000;
    public static final int AID_APP_END = 19999;
    public static final int AID_CACHE_GID_START = 20000;
    public static final int AID_CACHE_GID_END = 29999;
    public static final int AID_EXT_GID_START = 30000;
    public static final int AID_EXT_GID_END = 39999;
    public static final int AID_EXT_CACHE_GID_START = 40000;
    public static final int AID_EXT_CACHE_GID_END = 49999;
    public static final int AID_SHARED_GID_START = 50000;
    public static final int AID_SHARED_GID_END = 59999;
    public static final int AID_ISOLATED_START = 90000;
    public static final int AID_ISOLATED_END = 99999;
    public static final int AID_USER_OFFSET = 100000;

    public static String getAppPrincipalName(int uid) {
        final var userid = uid / AID_USER_OFFSET;
        final var appid = uid % AID_USER_OFFSET;
        if (appid > AID_ISOLATED_START) {
            return String.format(Locale.ENGLISH, "u%d_i%d", userid, appid - AID_ISOLATED_START);
        } else if (userid == 0 && appid >= AID_SHARED_GID_START && appid <= AID_SHARED_GID_END) {
            return String.format(Locale.ENGLISH, "all_a%d", appid - AID_SHARED_GID_START);
        } else if (appid >= AID_EXT_CACHE_GID_START && appid <= AID_EXT_CACHE_GID_END) {
            return String.format(Locale.ENGLISH, "u%d_a%d_ext_cache", userid, appid - AID_EXT_CACHE_GID_START);
        } else if (appid >= AID_EXT_GID_START && appid <= AID_EXT_GID_END) {
            return String.format(Locale.ENGLISH, "u%d_a%d_ext", userid, appid - AID_EXT_GID_START);
        } else if (appid >= AID_CACHE_GID_START && appid <= AID_CACHE_GID_END) {
            return String.format(Locale.ENGLISH, "u%d_a%d_cache", userid, appid - AID_CACHE_GID_START);
        } else {
            return String.format(Locale.ENGLISH, "u%d_a%d", userid, appid - AID_APP_START);
        }
    }
}
