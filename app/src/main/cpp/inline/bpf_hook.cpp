#include <dlfcn.h>
#include <libgen.h>
#include <sstream>
#include <shared_mutex>
#include <regex>
#include <sys/system_properties.h>

#include "bpf_hook.h"
#include "xhook/xhook.h"
#include "fuse_i.h"
#include "fuse_lowlevel.h"
#include "logging.h"
#include "obfuscate.h"

// Regex copied from FileUtils.java in MediaProvider, but without media directory.
const std::regex PATTERN_OWNED_PATH(
        "^/storage/[^/]+/(?:[0-9]+/)?Android/(?:data|obb)/([^/]+)(/?.*)?",
        std::regex_constants::icase);

static constexpr char PRIMARY_VOLUME_PREFIX[] = "/storage/emulated";

static bool isPackageOwnedPath(const std::string &path) {
    return std::regex_match(path, PATTERN_OWNED_PATH);
}

namespace bpf_hook {
    static constexpr char FUSE_HOOK_PATH_REGEX[] = ".*(libfuse_jni\\.so|MediaProvider\\.apk).*";

    bool isFuseBpfEnabled = false;
    std::set<std::string> mountPoint = {};
    std::shared_mutex mountPointMutex;
    bool recordExternalAppSpecificStorage = false;

    bool (*old_StartsWith)(std::string_view s, std::string_view prefix);

    bool new_StartsWith(std::string_view s, std::string_view prefix) {
        if (!isFuseBpfEnabled && recordExternalAppSpecificStorage &&
            prefix == PRIMARY_VOLUME_PREFIX) {
            auto path = std::string(s);
            if (isPackageOwnedPath(path)) {
                return false;
            }
        }
        return old_StartsWith(s, prefix);
    }

    bool isMountPoint(const std::string &path) {
        if (path.starts_with(PRIMARY_VOLUME_PREFIX)) {
            std::shared_lock<std::shared_mutex> lock(mountPointMutex);
            return mountPoint.find(path) != mountPoint.end();
        }
        return false;
    }

    // hook stubs
    bool (*old_containsMount_31)(const std::string &path);

    bool new_containsMount_31(const std::string &path) {
        if (isMountPoint(path)) {
            return true;
        }
        return old_containsMount_31(path);
    }

    bool (*old_containsMount_30)(const std::string &path, const std::string &userid);

    bool new_containsMount_30(const std::string &path, const std::string &userid) {
        if (isMountPoint(path)) {
            return true;
        }
        return old_containsMount_30(path, userid);
    }

    bool (*old_IsFuseBpfEnabled)();

    bool new_IsFuseBpfEnabled() {
        isFuseBpfEnabled = old_IsFuseBpfEnabled();
        return isFuseBpfEnabled;
    }

    thread_local fuse_req_t fuse_req;

    void *(*old_fuse_req_userdata)(fuse_req_t req);

    void *new_fuse_req_userdata(fuse_req_t req) {
        fuse_req = req;
        return old_fuse_req_userdata(req);
    }

    void (*old_fuse_bpf_install)(struct fuse *fuse, struct fuse_entry_param *e,
                                 const std::string &child_path, int &backing_fd);

    void new_fuse_bpf_install(struct fuse *fuse, struct fuse_entry_param *e,
                              const std::string &child_path, int &backing_fd) {
        if (recordExternalAppSpecificStorage || (fuse_req != nullptr && fuse_req->ctx.uid == 0)) {
            return;
        }
        return old_fuse_bpf_install(fuse, e, child_path, backing_fd);
    }

    static int GetApiLevel() {
        char prop[PROP_VALUE_MAX] = {0};
        __system_property_get("ro.build.version.sdk", prop);
        return atoi(prop);
    }

    // FUSE is always enabled on Android 11+ (API >= 30).
    // The persist.sys.fuse property was removed in Android 11,
    // so only check it on older versions where FUSE is optional.
    static bool IsFuse() {
        // Android 11+ (API >= 30) 上 FUSE 是默认文件系统，
        // persist.sys.fuse 属性已被移除，直接返回 true
        if (GetApiLevel() >= 30) {
            return true;
        }
        char prop[PROP_VALUE_MAX] = {0};
        __system_property_get("persist.sys.fuse", prop);
        return strcmp(prop, "true") == 0;
    }

    static void AppendJsonBool(std::ostringstream &out, const char *name, bool value) {
        out << '"' << name << "\":" << (value ? "true" : "false");
    }

    static void AppendJsonString(std::ostringstream &out, const char *name, const char *value) {
        out << '"' << name << "\":\"" << value << '"';
    }

    static bool RegisterHook(const char *symbol, void *newFunc, void **oldFunc) {
        return xhook_register(FUSE_HOOK_PATH_REGEX, symbol, newFunc, oldFunc) == 0;
    }

    std::string Hook(void *handle, bool fuseLibraryMapped) {
        bool startsWithHooked = false;
        bool containsMountHooked = false;
        bool isFuseBpfEnabledHooked = false;
        bool fuseReqUserdataHooked = false;
        bool fuseBpfInstallHooked = false;
        bool xhookRefreshCalled = false;
        std::string lastError;

        if (!IsFuse()) {
            LOGE("%s", std::string(AY_OBFUSCATE("FUSE not available, skipping hook")).c_str()); // "FUSE not available, skipping hook"
            return "{\"fuseAvailable\":false,\"fuseLibraryLoaded\":true,"
                   "\"fuseLibraryName\":\"libfuse_jni.so\",\"xhookRefreshCalled\":false,"
                   "\"lastError\":\"FUSE not available\"}";
        }
        LOGE("%s", std::string(AY_OBFUSCATE("Initializing bpf_hook")).c_str()); // "Initializing bpf_hook"
        if (handle == nullptr) {
            lastError = "FUSE library mapped but symbol handle unavailable";
        }
        if (GetApiLevel() >= 31) {
            const char *startsWithSymbol = AY_OBFUSCATE(
                    "_ZN7android4base10StartsWithENSt6__ndk117basic_string_viewIcNS1_11char_traitsIcEEEES5_");
            auto startsWith = handle == nullptr ? nullptr : dlsym(handle, startsWithSymbol);
            auto startsWithRegistered = RegisterHook(startsWithSymbol, (void *) new_StartsWith,
                                                     (void **) &old_StartsWith);
            startsWithHooked = startsWith != nullptr && startsWithRegistered;
            if (!startsWithHooked) {
                LOGE("%s", std::string(AY_OBFUSCATE("failed to find StartsWith")).c_str()); // "failed to find StartsWith"
                if (handle != nullptr) {
                    lastError = "failed to find StartsWith";
                }
            }
        }
        const char *containsMount31Symbol = AY_OBFUSCATE(
                "_ZN13mediaprovider4fuse13containsMountERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE");
        auto containsMount_31 = handle == nullptr ? nullptr : dlsym(handle, containsMount31Symbol);
        auto containsMount31Registered = RegisterHook(containsMount31Symbol, (void *) new_containsMount_31,
                                                      (void **) &old_containsMount_31);
        containsMountHooked = containsMount_31 != nullptr && containsMount31Registered;
        if (!containsMountHooked) {
            const char *containsMount30Symbol = AY_OBFUSCATE(
                    "_ZN13mediaprovider4fuse13containsMountERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEES9_");
            auto containsMount_30 = handle == nullptr ? nullptr : dlsym(handle, containsMount30Symbol);
            auto containsMount30Registered = RegisterHook(containsMount30Symbol,
                                                          (void *) new_containsMount_30,
                                                          (void **) &old_containsMount_30);
            containsMountHooked = containsMount_30 != nullptr && containsMount30Registered;
        }
        if (!containsMountHooked) {
            LOGE("%s", std::string(AY_OBFUSCATE("failed to find containsMount")).c_str()); // "failed to find containsMount"
            if (handle != nullptr) {
                lastError = "failed to find containsMount";
            }
        }
        const char *isFuseBpfEnabledSymbol = AY_OBFUSCATE(
                "_ZN13mediaprovider4fuse16IsFuseBpfEnabledEv");
        auto IsFuseBpfEnabled = handle == nullptr ? nullptr : dlsym(handle, isFuseBpfEnabledSymbol);
        auto isFuseBpfEnabledRegistered = RegisterHook(isFuseBpfEnabledSymbol,
                                                       (void *) new_IsFuseBpfEnabled,
                                                       (void **) &old_IsFuseBpfEnabled);
        isFuseBpfEnabledHooked = IsFuseBpfEnabled != nullptr && isFuseBpfEnabledRegistered;
        if (!isFuseBpfEnabledHooked) {
            LOGE("%s", std::string(AY_OBFUSCATE("failed to find IsFuseBpfEnabled")).c_str()); // "failed to find IsFuseBpfEnabled"
            if (handle != nullptr) {
                lastError = "failed to find IsFuseBpfEnabled";
            }
        }

        const char *fuseReqUserdataSymbol = AY_OBFUSCATE("fuse_req_userdata");
        auto fuse_req_userdata = handle == nullptr ? nullptr : dlsym(handle, fuseReqUserdataSymbol); // "fuse_req_userdata"
        auto fuseReqUserdataRegistered = RegisterHook(fuseReqUserdataSymbol,
                                                      (void *) new_fuse_req_userdata,
                                                      (void **) &old_fuse_req_userdata);
        fuseReqUserdataHooked = fuse_req_userdata != nullptr && fuseReqUserdataRegistered;
        if (!fuseReqUserdataHooked) {
            LOGE("%s", std::string(AY_OBFUSCATE("failed to find fuse_req_userdata")).c_str()); // "failed to find fuse_req_userdata"
            if (handle != nullptr) {
                lastError = "failed to find fuse_req_userdata";
            }
        }

        const char *fuseBpfInstallSymbol = AY_OBFUSCATE(
                "_ZN13mediaprovider4fuse16fuse_bpf_installEP4fuseP16fuse_entry_paramRKNSt6__ndk112basic_stringIcNS5_11char_traitsIcEENS5_9allocatorIcEEEERi");
        auto fuse_bpf_install = handle == nullptr ? nullptr : dlsym(handle, fuseBpfInstallSymbol);
        auto fuseBpfInstallRegistered = RegisterHook(fuseBpfInstallSymbol,
                                                     (void *) new_fuse_bpf_install,
                                                     (void **) &old_fuse_bpf_install);
        fuseBpfInstallHooked = fuse_bpf_install != nullptr && fuseBpfInstallRegistered;
        if (!fuseBpfInstallHooked) {
            LOGE("%s", std::string(AY_OBFUSCATE("failed to find fuse_bpf_install")).c_str()); // "failed to find fuse_bpf_install"
            if (handle != nullptr) {
                lastError = "failed to find fuse_bpf_install";
            }
        }

        xhook_refresh(0);
        xhookRefreshCalled = true;

        std::ostringstream out;
        out << "{";
        AppendJsonBool(out, "fuseAvailable", true);
        out << ",";
        AppendJsonBool(out, "fuseLibraryLoaded", fuseLibraryMapped);
        out << ",";
        AppendJsonString(out, "fuseLibraryName", handle == nullptr ? "MediaProvider.apk" : "libfuse_jni.so");
        out << ",";
        AppendJsonBool(out, "startsWithHooked", startsWithHooked);
        out << ",";
        AppendJsonBool(out, "containsMountHooked", containsMountHooked);
        out << ",";
        AppendJsonBool(out, "isFuseBpfEnabledHooked", isFuseBpfEnabledHooked);
        out << ",";
        AppendJsonBool(out, "fuseReqUserdataHooked", fuseReqUserdataHooked);
        out << ",";
        AppendJsonBool(out, "fuseBpfInstallHooked", fuseBpfInstallHooked);
        out << ",";
        AppendJsonBool(out, "xhookRefreshCalled", xhookRefreshCalled);
        out << ",";
        AppendJsonString(out, "lastError", lastError.c_str());
        out << "}";
        return out.str();
    }

    void setMountPoint(JNIEnv *env, jclass clazz, jobjectArray value) {
        std::unique_lock<std::shared_mutex> lock(mountPointMutex);
        mountPoint.clear();
        for (int i = 0, length = env->GetArrayLength(value); i < length; i++) {
            auto jpath = (jstring) env->GetObjectArrayElement(value, i);
            if (jpath == nullptr) continue;
            const char *path = env->GetStringUTFChars(jpath, nullptr);
            if (path == nullptr) { env->DeleteLocalRef(jpath); continue; }

            std::string parent = path;
            while (true) {
                if (!mountPoint.insert(parent).second) {
                    break;
                }
                char *mutable_path = strdup(parent.c_str());
                char *dir = dirname(mutable_path);
                parent = dir;
                free(mutable_path);
                if (parent == PRIMARY_VOLUME_PREFIX || parent == "/") {
                    break;
                }
            }

            env->ReleaseStringUTFChars(jpath, path);
            env->DeleteLocalRef(jpath);
        }
    }

    void setRecordExternalAppSpecificStorage(JNIEnv *env, jclass clazz, jboolean value) {
        recordExternalAppSpecificStorage = value;
    }
}  // namespace bpf_hook
