#include <dlfcn.h>
#include <elf.h>
#include <cstdint>
#include <libgen.h>
#include <link.h>
#include <sstream>
#include <shared_mutex>
#include <regex>
#include <sys/mman.h>
#include <sys/system_properties.h>
#include <unistd.h>

#include "bpf_hook.h"
#include "xhook/xhook.h"
#include "fuse_i.h"
#include "fuse_lowlevel.h"
#include "logging.h"
#include "obfuscate.h"

#if defined(__LP64__)
#define MC_ELF_R_SYM ELF64_R_SYM
#else
#define MC_ELF_R_SYM ELF32_R_SYM
#endif

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
    static constexpr char FUSE_JNI_SONAME[] = "libfuse_jni.so";

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

    struct EmbeddedFuseHookResult {
        bool foundFuseJni = false;
        bool startsWithHooked = false;
        bool containsMountHooked = false;
        bool isFuseBpfEnabledHooked = false;
        bool fuseReqUserdataHooked = false;
        bool fuseBpfInstallHooked = false;
    };

    struct EmbeddedHookTarget {
        const char *symbol;
        void *replacement;
        void **original;
        bool *hooked;
    };

    static bool PatchGotSlot(ElfW(Addr) slotAddress, void *replacement, void **original) {
        auto slot = reinterpret_cast<void **>(slotAddress);
        if (slot == nullptr || replacement == nullptr || original == nullptr) {
            return false;
        }
        if (*slot == replacement) {
            return true;
        }
        *original = *slot;
        const long pageSize = sysconf(_SC_PAGESIZE);
        if (pageSize <= 0) {
            return false;
        }
        auto page = reinterpret_cast<void *>(
                reinterpret_cast<uintptr_t>(slot) & ~(static_cast<uintptr_t>(pageSize) - 1));
        if (mprotect(page, static_cast<size_t>(pageSize), PROT_READ | PROT_WRITE) != 0) {
            return false;
        }
        *slot = replacement;
        __builtin___clear_cache(reinterpret_cast<char *>(slot),
                                reinterpret_cast<char *>(slot) + sizeof(void *));
        mprotect(page, static_cast<size_t>(pageSize), PROT_READ);
        return true;
    }

    static bool IsRuntimeAddress(ElfW(Addr) value, ElfW(Addr) base) {
        return value >= base;
    }

    static ElfW(Addr) ResolveDynamicAddress(ElfW(Addr) value, ElfW(Addr) base) {
        return IsRuntimeAddress(value, base) ? value : base + value;
    }

    static int PatchEmbeddedFuseJniCallback(struct dl_phdr_info *info, size_t, void *data) {
        auto result = static_cast<EmbeddedFuseHookResult *>(data);
        if (info == nullptr || result == nullptr) {
            return 0;
        }

        ElfW(Dyn) *dynamic = nullptr;
        for (int i = 0; i < info->dlpi_phnum; i++) {
            const auto &phdr = info->dlpi_phdr[i];
            if (phdr.p_type == PT_DYNAMIC) {
                dynamic = reinterpret_cast<ElfW(Dyn) *>(info->dlpi_addr + phdr.p_vaddr);
                break;
            }
        }
        if (dynamic == nullptr) {
            return 0;
        }

        const char *strtab = nullptr;
        ElfW(Sym) *symtab = nullptr;
        ElfW(Rela) *jmprel = nullptr;
        size_t pltrelsz = 0;
        const char *soname = nullptr;
        for (auto dyn = dynamic; dyn->d_tag != DT_NULL; dyn++) {
            switch (dyn->d_tag) {
                case DT_STRTAB:
                    strtab = reinterpret_cast<const char *>(
                            ResolveDynamicAddress(dyn->d_un.d_ptr, info->dlpi_addr));
                    break;
                case DT_SYMTAB:
                    symtab = reinterpret_cast<ElfW(Sym) *>(
                            ResolveDynamicAddress(dyn->d_un.d_ptr, info->dlpi_addr));
                    break;
                case DT_JMPREL:
                    jmprel = reinterpret_cast<ElfW(Rela) *>(
                            ResolveDynamicAddress(dyn->d_un.d_ptr, info->dlpi_addr));
                    break;
                case DT_PLTRELSZ:
                    pltrelsz = dyn->d_un.d_val;
                    break;
                case DT_SONAME:
                    if (strtab != nullptr) {
                        soname = strtab + dyn->d_un.d_val;
                    }
                    break;
                default:
                    break;
            }
        }
        if (strtab == nullptr || symtab == nullptr || jmprel == nullptr || pltrelsz == 0) {
            return 0;
        }
        if (soname == nullptr) {
            for (auto dyn = dynamic; dyn->d_tag != DT_NULL; dyn++) {
                if (dyn->d_tag == DT_SONAME) {
                    soname = strtab + dyn->d_un.d_val;
                    break;
                }
            }
        }
        if (soname == nullptr || strcmp(soname, FUSE_JNI_SONAME) != 0) {
            return 0;
        }

        result->foundFuseJni = true;
        const char *startsWithSymbol = AY_OBFUSCATE(
                "_ZN7android4base10StartsWithENSt6__ndk117basic_string_viewIcNS1_11char_traitsIcEEEES5_");
        const char *containsMount31Symbol = AY_OBFUSCATE(
                "_ZN13mediaprovider4fuse13containsMountERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE");
        const char *isFuseBpfEnabledSymbol = AY_OBFUSCATE(
                "_ZN13mediaprovider4fuse16IsFuseBpfEnabledEv");
        const char *fuseReqUserdataSymbol = AY_OBFUSCATE("fuse_req_userdata");
        const char *fuseBpfInstallSymbol = AY_OBFUSCATE(
                "_ZN13mediaprovider4fuse16fuse_bpf_installEP4fuseP16fuse_entry_paramRKNSt6__ndk112basic_stringIcNS5_11char_traitsIcEENS5_9allocatorIcEEEERi");

        EmbeddedHookTarget targets[] = {
                {startsWithSymbol,       reinterpret_cast<void *>(new_StartsWith),
                        reinterpret_cast<void **>(&old_StartsWith), &result->startsWithHooked},
                {containsMount31Symbol,  reinterpret_cast<void *>(new_containsMount_31),
                        reinterpret_cast<void **>(&old_containsMount_31), &result->containsMountHooked},
                {isFuseBpfEnabledSymbol, reinterpret_cast<void *>(new_IsFuseBpfEnabled),
                        reinterpret_cast<void **>(&old_IsFuseBpfEnabled), &result->isFuseBpfEnabledHooked},
                {fuseReqUserdataSymbol,  reinterpret_cast<void *>(new_fuse_req_userdata),
                        reinterpret_cast<void **>(&old_fuse_req_userdata), &result->fuseReqUserdataHooked},
                {fuseBpfInstallSymbol,   reinterpret_cast<void *>(new_fuse_bpf_install),
                        reinterpret_cast<void **>(&old_fuse_bpf_install), &result->fuseBpfInstallHooked},
        };

        const auto count = pltrelsz / sizeof(ElfW(Rela));
        for (size_t i = 0; i < count; i++) {
            const auto &relocation = jmprel[i];
            const auto symbolIndex = MC_ELF_R_SYM(relocation.r_info);
            const char *symbolName = strtab + symtab[symbolIndex].st_name;
            for (auto &target: targets) {
                if (*target.hooked || strcmp(symbolName, target.symbol) != 0) {
                    continue;
                }
                const auto slotAddress = info->dlpi_addr + relocation.r_offset;
                *target.hooked = PatchGotSlot(slotAddress, target.replacement, target.original);
            }
        }
        return 1;
    }

    static EmbeddedFuseHookResult HookEmbeddedFuseJni() {
        EmbeddedFuseHookResult result;
        dl_iterate_phdr(PatchEmbeddedFuseJniCallback, &result);
        return result;
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
                   "\"hookMode\":\"NONE\",\"fuseJniLoadMode\":\"UNKNOWN\","
                   "\"embeddedFuseJniFound\":false,"
                   "\"lastError\":\"FUSE not available\"}";
        }
        LOGE("%s", std::string(AY_OBFUSCATE("Initializing bpf_hook")).c_str()); // "Initializing bpf_hook"
        if (handle == nullptr) {
            lastError = "FUSE library mapped but symbol handle unavailable";
            auto embeddedResult = HookEmbeddedFuseJni();
            startsWithHooked = embeddedResult.startsWithHooked;
            containsMountHooked = embeddedResult.containsMountHooked;
            isFuseBpfEnabledHooked = embeddedResult.isFuseBpfEnabledHooked;
            fuseReqUserdataHooked = embeddedResult.fuseReqUserdataHooked;
            fuseBpfInstallHooked = embeddedResult.fuseBpfInstallHooked;
            if (embeddedResult.foundFuseJni) {
                lastError = containsMountHooked ? "" : "embedded libfuse_jni.so found but GOT hook failed";
            }
            std::ostringstream out;
            out << "{";
            AppendJsonBool(out, "fuseAvailable", true);
            out << ",";
            AppendJsonBool(out, "fuseLibraryLoaded", fuseLibraryMapped);
            out << ",";
            AppendJsonString(out, "fuseLibraryName", "MediaProvider.apk/libfuse_jni.so");
            out << ",";
            AppendJsonString(out, "hookMode", "EMBEDDED_GOT_PATCH");
            out << ",";
            AppendJsonString(out, "fuseJniLoadMode", "APEX_APK_EMBEDDED");
            out << ",";
            AppendJsonBool(out, "embeddedFuseJniFound", embeddedResult.foundFuseJni);
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
            AppendJsonBool(out, "xhookRefreshCalled", false);
            out << ",";
            AppendJsonString(out, "lastError", lastError.c_str());
            out << "}";
            return out.str();
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
        AppendJsonString(out, "hookMode", "XHOOK");
        out << ",";
        AppendJsonString(out, "fuseJniLoadMode", "SYSTEM_LIB");
        out << ",";
        AppendJsonBool(out, "embeddedFuseJniFound", false);
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
