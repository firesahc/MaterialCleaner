#ifndef FUSE_POLICY_H
#define FUSE_POLICY_H

#include <cstdlib>
#include <cstring>
#include <sys/system_properties.h>

namespace storage_platform {

constexpr int kFuseAlwaysAvailableApiLevel = 30;

constexpr bool is_fuse_available(int api_level, bool legacy_property_enabled) {
    return api_level >= kFuseAlwaysAvailableApiLevel || legacy_property_enabled;
}

inline int device_api_level() {
    char value[PROP_VALUE_MAX] = {};
    __system_property_get("ro.build.version.sdk", value);
    return atoi(value);
}

inline bool is_fuse_available() {
    const int api_level = device_api_level();
    if (api_level >= kFuseAlwaysAvailableApiLevel) {
        return true;
    }
    char value[PROP_VALUE_MAX] = {};
    __system_property_get("persist.sys.fuse", value);
    return is_fuse_available(api_level, strcmp(value, "true") == 0);
}

static_assert(!is_fuse_available(29, false));
static_assert(is_fuse_available(29, true));
static_assert(is_fuse_available(30, false));
static_assert(is_fuse_available(35, false));

}  // namespace storage_platform

#endif  // FUSE_POLICY_H
