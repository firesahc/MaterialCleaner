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

    // Match method for diagnostic output
    enum MatchMethod {
        MATCH_NONE  = 0,
        MATCH_EXACT = 1,
        MATCH_FUZZY = 2,
    };

    static const char *MatchMethodName(MatchMethod method) {
        return method == MATCH_EXACT ? "exact" : (method == MATCH_FUZZY ? "fuzzy" : "none");
    }

    struct EmbeddedFuseHookResult {
        bool foundFuseJni = false;
        bool startsWithHooked = false;
        bool containsMountHooked = false;
        bool isFuseBpfEnabledHooked = false;
        bool fuseReqUserdataHooked = false;
        bool fuseBpfInstallHooked = false;
        // Diagnostic: which match method was used for each symbol
        MatchMethod startsWithMethod = MATCH_NONE;
        MatchMethod containsMountMethod = MATCH_NONE;
        MatchMethod isFuseBpfEnabledMethod = MATCH_NONE;
        MatchMethod fuseReqUserdataMethod = MATCH_NONE;
        MatchMethod fuseBpfInstallMethod = MATCH_NONE;
    };

    struct EmbeddedHookTarget {
        const char *symbol;        // exact mangled name (for strcmp)
        const char *shortName;     // unqualified function name for fuzzy match (NULL = exact-only)
        const char *namespaceName; // expected C++ namespace for fuzzy match (NULL = global/C symbol)
        int paramCount;            // expected param count (-1 = don't check)
        void *replacement;
        void **original;
        bool *hooked;
        MatchMethod *method;       // [out] which method matched (diagnostics)
    };

    static std::string BuildHookStatusJson(bool fuseAvailable,
                                           bool fuseLibraryLoaded,
                                           const char *fuseLibraryName,
                                           const char *hookMode,
                                           const char *fuseJniLoadMode,
                                           bool embeddedFuseJniFound,
                                           bool startsWithHooked,
                                           bool containsMountHooked,
                                           bool isFuseBpfEnabledHooked,
                                           bool fuseReqUserdataHooked,
                                           bool fuseBpfInstallHooked,
                                           const char *startsWithMethod,
                                           const char *containsMountMethod,
                                           const char *isFuseBpfEnabledMethod,
                                           const char *fuseReqUserdataMethod,
                                           const char *fuseBpfInstallMethod,
                                           bool xhookRefreshCalled,
                                           const char *lastError) {
        std::ostringstream out;
        out << "{";
        AppendJsonBool(out, "fuseAvailable", fuseAvailable);
        out << ",";
        AppendJsonBool(out, "fuseLibraryLoaded", fuseLibraryLoaded);
        out << ",";
        AppendJsonString(out, "fuseLibraryName", fuseLibraryName);
        out << ",";
        AppendJsonString(out, "hookMode", hookMode);
        out << ",";
        AppendJsonString(out, "fuseJniLoadMode", fuseJniLoadMode);
        out << ",";
        AppendJsonBool(out, "embeddedFuseJniFound", embeddedFuseJniFound);
        out << ",";
        AppendJsonBool(out, "xhookRefreshCalled", xhookRefreshCalled);
        out << ",\"symbols\":{";
        AppendJsonBool(out, "containsMount", containsMountHooked);
        out << ",";
        AppendJsonBool(out, "startsWith", startsWithHooked);
        out << ",";
        AppendJsonBool(out, "isFuseBpfEnabled", isFuseBpfEnabledHooked);
        out << ",";
        AppendJsonBool(out, "fuseReqUserdata", fuseReqUserdataHooked);
        out << ",";
        AppendJsonBool(out, "fuseBpfInstall", fuseBpfInstallHooked);
        out << "},\"symbolMethods\":{";
        AppendJsonString(out, "containsMount", containsMountMethod);
        out << ",";
        AppendJsonString(out, "startsWith", startsWithMethod);
        out << ",";
        AppendJsonString(out, "isFuseBpfEnabled", isFuseBpfEnabledMethod);
        out << ",";
        AppendJsonString(out, "fuseReqUserdata", fuseReqUserdataMethod);
        out << ",";
        AppendJsonString(out, "fuseBpfInstall", fuseBpfInstallMethod);
        out << "},";
        AppendJsonString(out, "lastError", lastError);
        out << "}";
        return out.str();
    }

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

    struct MangledNameComponent {
        const char *name = nullptr;
        size_t length = 0;
    };

    static bool IsDigit(char value) {
        return value >= '0' && value <= '9';
    }

    static bool ReadLengthPrefixedName(const char **cursor,
                                       MangledNameComponent *component = nullptr) {
        if (cursor == nullptr || *cursor == nullptr || !IsDigit(**cursor)) {
            return false;
        }
        const char *p = *cursor;
        size_t length = 0;
        while (IsDigit(*p)) {
            length = length * 10 + static_cast<size_t>(*p - '0');
            p++;
        }
        if (length == 0 || strlen(p) < length) {
            return false;
        }
        if (component != nullptr) {
            component->name = p;
            component->length = length;
        }
        *cursor = p + length;
        return true;
    }

    static bool ParseMangledNameComponents(const char *mangled,
                                           MangledNameComponent *components,
                                           size_t maxComponents,
                                           size_t *componentCount,
                                           const char **nameEnd) {
        if (mangled == nullptr || components == nullptr || componentCount == nullptr
                || maxComponents == 0) {
            return false;
        }
        *componentCount = 0;
        if (nameEnd != nullptr) {
            *nameEnd = nullptr;
        }

        if (mangled[0] != '_' || mangled[1] != 'Z') {
            components[0].name = mangled;
            components[0].length = strlen(mangled);
            *componentCount = 1;
            if (nameEnd != nullptr) {
                *nameEnd = mangled + components[0].length;
            }
            return components[0].length > 0;
        }

        const char *p = mangled + 2;
        const bool nested = *p == 'N';
        if (nested) {
            p++;
        }

        while (IsDigit(*p)) {
            if (*componentCount >= maxComponents) {
                return false;
            }
            if (!ReadLengthPrefixedName(&p, &components[*componentCount])) {
                return false;
            }
            (*componentCount)++;
        }

        if (*componentCount == 0) {
            return false;
        }
        if (nested) {
            if (*p != 'E') {
                return false;
            }
            p++;
        }
        if (nameEnd != nullptr) {
            *nameEnd = p;
        }
        return true;
    }

    static bool ComponentEquals(const MangledNameComponent &component, const char *expected) {
        return expected != nullptr
               && strlen(expected) == component.length
               && strncmp(component.name, expected, component.length) == 0;
    }

    static bool NamespaceMatches(const MangledNameComponent *components, size_t componentCount,
                                 const char *expectedNamespace) {
        if (expectedNamespace == nullptr || *expectedNamespace == '\0') {
            return componentCount == 1;
        }
        if (componentCount < 2) {
            return false;
        }

        size_t componentIndex = 0;
        const char *segment = expectedNamespace;
        while (*segment != '\0') {
            const char *segmentEnd = strstr(segment, "::");
            const size_t segmentLength = segmentEnd == nullptr
                                         ? strlen(segment)
                                         : static_cast<size_t>(segmentEnd - segment);
            if (componentIndex >= componentCount - 1
                    || components[componentIndex].length != segmentLength
                    || strncmp(components[componentIndex].name, segment, segmentLength) != 0) {
                return false;
            }
            componentIndex++;
            if (segmentEnd == nullptr) {
                break;
            }
            segment = segmentEnd + 2;
        }
        return componentIndex == componentCount - 1;
    }

    /**
     * Extract the unqualified function name from an Itanium C++ ABI mangled symbol.
     *
     * For `_ZN7android4base10StartsWithE...` → "StartsWith"
     * For `_ZN13mediaprovider4fuse13containsMountE...` → "containsMount"
     * For C symbols (like `fuse_req_userdata`) → returned as-is.
     *
     * Async-signal-safe, no heap allocation, no external dependencies.
     */
    static bool ExtractFunctionName(const char *mangled, char *out, size_t out_size) {
        if (!mangled || !out || out_size == 0) return false;

        MangledNameComponent components[8];
        size_t componentCount = 0;
        if (!ParseMangledNameComponents(mangled, components,
                                        sizeof(components) / sizeof(components[0]),
                                        &componentCount, nullptr)) {
            return false;
        }

        const auto &lastName = components[componentCount - 1];
        const size_t copyLen = lastName.length < out_size - 1 ? lastName.length : out_size - 1;
        memcpy(out, lastName.name, copyLen);
        out[copyLen] = '\0';
        return true;
    }

    static const char *SkipType(const char *sig);

    static const char *SkipSubstitution(const char *sig) {
        if (sig == nullptr || *sig != 'S') {
            return nullptr;
        }
        sig++;
        if (*sig == 't') {
            return sig + 1; // St = std::
        }
        while (IsDigit(*sig)) {
            sig++;
        }
        return *sig == '_' ? sig + 1 : nullptr;
    }

    static const char *SkipTemplateArgs(const char *sig) {
        if (sig == nullptr || *sig != 'I') {
            return nullptr;
        }
        sig++;
        while (*sig != '\0' && *sig != 'E') {
            sig = SkipType(sig);
            if (sig == nullptr) {
                return nullptr;
            }
        }
        return *sig == 'E' ? sig + 1 : nullptr;
    }

    static const char *SkipNestedName(const char *sig) {
        if (sig == nullptr || *sig != 'N') {
            return nullptr;
        }
        sig++;
        while (*sig != '\0' && *sig != 'E') {
            if (*sig == 'S') {
                sig = SkipSubstitution(sig);
            } else if (IsDigit(*sig)) {
                if (!ReadLengthPrefixedName(&sig)) {
                    return nullptr;
                }
                if (*sig == 'I') {
                    sig = SkipTemplateArgs(sig);
                }
            } else {
                return nullptr;
            }
            if (sig == nullptr) {
                return nullptr;
            }
        }
        return *sig == 'E' ? sig + 1 : nullptr;
    }

    static const char *SkipType(const char *sig) {
        if (sig == nullptr || *sig == '\0') {
            return nullptr;
        }
        while (*sig == 'R' || *sig == 'O' || *sig == 'P' || *sig == 'K'
               || *sig == 'V' || *sig == 'r') {
            sig++;
        }
        if (*sig == 'N') {
            return SkipNestedName(sig);
        }
        if (*sig == 'S') {
            return SkipSubstitution(sig);
        }
        if (IsDigit(*sig)) {
            if (!ReadLengthPrefixedName(&sig)) {
                return nullptr;
            }
            return *sig == 'I' ? SkipTemplateArgs(sig) : sig;
        }
        if (*sig == 'F') {
            sig++;
            while (*sig != '\0' && *sig != 'E') {
                sig = SkipType(sig);
                if (sig == nullptr) {
                    return nullptr;
                }
            }
            return *sig == 'E' ? sig + 1 : nullptr;
        }
        return sig + 1; // builtin or vendor extended one-letter type code
    }

    /**
     * Count top-level parameter types in an Itanium ABI signature suffix.
     *
     * Walks the signature string (the part after the function name's closing 'E')
     * tracking depth through nested names ('N'), template args ('I'), and
     * substitution back-references ('S{digits}_').  Each top-level type encoding
     * at depth 0 increments the count.
     *
     * Used for overload disambiguation — currently only `containsMount`
     * has two variants (1-arg for API 31+, 2-arg for API 30).
     */
    static int CountTopLevelParams(const char *sig) {
        if (!sig || !*sig) return 0;
        if (sig[0] == 'v' && sig[1] == '\0') return 0;
        int count = 0;
        while (*sig != '\0') {
            const char *next = SkipType(sig);
            if (next == nullptr || next <= sig) {
                return -1;
            }
            count++;
            sig = next;
        }
        return count;
    }

    /**
     * Given an Itanium ABI mangled function name, return a pointer to the
     * signature suffix (the part after the function name's closing 'E').
     *
     * For `_ZN13mediaprovider4fuse13containsMountERKNSt6__ndk1...` → pointer to `RKNSt6__ndk1...`
     * For C symbols (non-mangled) → nullptr.
     */
    static const char* FindSignatureSuffix(const char *mangled) {
        if (!mangled || mangled[0] != '_' || mangled[1] != 'Z') return nullptr;
        MangledNameComponent components[8];
        size_t componentCount = 0;
        const char *nameEnd = nullptr;
        if (!ParseMangledNameComponents(mangled, components,
                                        sizeof(components) / sizeof(components[0]),
                                        &componentCount, &nameEnd)) {
            return nullptr;
        }
        return (nameEnd != nullptr && *nameEnd != '\0') ? nameEnd : nullptr;
    }

    static bool FuzzyMatchesTarget(const char *symbolName, const EmbeddedHookTarget &target) {
        MangledNameComponent components[8];
        size_t componentCount = 0;
        if (!ParseMangledNameComponents(symbolName, components,
                                        sizeof(components) / sizeof(components[0]),
                                        &componentCount, nullptr)) {
            return false;
        }
        if (!ComponentEquals(components[componentCount - 1], target.shortName)) {
            return false;
        }
        if (!NamespaceMatches(components, componentCount, target.namespaceName)) {
            return false;
        }
        if (target.paramCount < 0) {
            return true;
        }
        const char *sig = FindSignatureSuffix(symbolName);
        return sig != nullptr && CountTopLevelParams(sig) == target.paramCount;
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
        const char *containsMount30Symbol = AY_OBFUSCATE(
                "_ZN13mediaprovider4fuse13containsMountERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEES9_");
        const char *isFuseBpfEnabledSymbol = AY_OBFUSCATE(
                "_ZN13mediaprovider4fuse16IsFuseBpfEnabledEv");
        const char *fuseReqUserdataSymbol = AY_OBFUSCATE("fuse_req_userdata");
        const char *fuseBpfInstallSymbol = AY_OBFUSCATE(
                "_ZN13mediaprovider4fuse16fuse_bpf_installEP4fuseP16fuse_entry_paramRKNSt6__ndk112basic_stringIcNS5_11char_traitsIcEENS5_9allocatorIcEEEERi");

        // Targets:
        //   symbol          shortName           namespace             paramCount  replacement              original                   hooked flag              method out
        EmbeddedHookTarget targets[] = {
                {startsWithSymbol,       AY_OBFUSCATE("StartsWith"),
                        AY_OBFUSCATE("android::base"), -1,
                        reinterpret_cast<void *>(new_StartsWith),
                        reinterpret_cast<void **>(&old_StartsWith), &result->startsWithHooked,
                        &result->startsWithMethod},
                {containsMount31Symbol,  AY_OBFUSCATE("containsMount"),
                        AY_OBFUSCATE("mediaprovider::fuse"), 1,
                        reinterpret_cast<void *>(new_containsMount_31),
                        reinterpret_cast<void **>(&old_containsMount_31), &result->containsMountHooked,
                        &result->containsMountMethod},
                {containsMount30Symbol,  AY_OBFUSCATE("containsMount"),
                        AY_OBFUSCATE("mediaprovider::fuse"), 2,
                        reinterpret_cast<void *>(new_containsMount_30),
                        reinterpret_cast<void **>(&old_containsMount_30), &result->containsMountHooked,
                        &result->containsMountMethod},
                {isFuseBpfEnabledSymbol, AY_OBFUSCATE("IsFuseBpfEnabled"),
                        AY_OBFUSCATE("mediaprovider::fuse"), -1,
                        reinterpret_cast<void *>(new_IsFuseBpfEnabled),
                        reinterpret_cast<void **>(&old_IsFuseBpfEnabled), &result->isFuseBpfEnabledHooked,
                        &result->isFuseBpfEnabledMethod},
                {fuseReqUserdataSymbol,  AY_OBFUSCATE("fuse_req_userdata"), nullptr, -1,
                        reinterpret_cast<void *>(new_fuse_req_userdata),
                        reinterpret_cast<void **>(&old_fuse_req_userdata), &result->fuseReqUserdataHooked,
                        &result->fuseReqUserdataMethod},
                {fuseBpfInstallSymbol,   AY_OBFUSCATE("fuse_bpf_install"),
                        AY_OBFUSCATE("mediaprovider::fuse"), -1,
                        reinterpret_cast<void *>(new_fuse_bpf_install),
                        reinterpret_cast<void **>(&old_fuse_bpf_install), &result->fuseBpfInstallHooked,
                        &result->fuseBpfInstallMethod},
        };

        const auto count = pltrelsz / sizeof(ElfW(Rela));
        for (size_t i = 0; i < count; i++) {
            const auto &relocation = jmprel[i];
            const auto symbolIndex = MC_ELF_R_SYM(relocation.r_info);
            const char *symbolName = strtab + symtab[symbolIndex].st_name;

            for (auto &target: targets) {
                if (*target.hooked) continue;

                // --- Phase 1: Exact match (strcmp) ---
                bool matched = (strcmp(symbolName, target.symbol) == 0);
                MatchMethod method = MATCH_EXACT;

                // --- Phase 2: Fuzzy match via Itanium name extraction ---
                if (!matched && target.shortName != nullptr && FuzzyMatchesTarget(symbolName, target)) {
                    matched = true;
                    method = MATCH_FUZZY;
                }

                // --- Apply GOT patch if matched ---
                if (!matched) continue;

                const auto slotAddress = info->dlpi_addr + relocation.r_offset;
                *target.hooked = PatchGotSlot(slotAddress, target.replacement, target.original);
                *target.method = method;
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
        const bool startsWithRequired = GetApiLevel() >= 31;
        const char *startsWithMethod = "none";
        const char *containsMountMethod = "none";
        const char *isFuseBpfEnabledMethod = "none";
        const char *fuseReqUserdataMethod = "none";
        const char *fuseBpfInstallMethod = "none";

        if (!IsFuse()) {
            LOGE("%s", std::string(AY_OBFUSCATE("FUSE not available, skipping hook")).c_str()); // "FUSE not available, skipping hook"
            return BuildHookStatusJson(false, true, "libfuse_jni.so",
                                       "NONE", "UNKNOWN", false,
                                       false, false, false, false, false,
                                       "none", "none", "none", "none", "none",
                                       false, "FUSE not available");
        }
        LOGI("%s", std::string(AY_OBFUSCATE("Initializing bpf_hook")).c_str()); // "Initializing bpf_hook"
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
            return BuildHookStatusJson(true, fuseLibraryMapped,
                                       "MediaProvider.apk/libfuse_jni.so",
                                       "EMBEDDED_GOT_PATCH", "APEX_APK_EMBEDDED",
                                       embeddedResult.foundFuseJni,
                                       startsWithHooked, containsMountHooked,
                                       isFuseBpfEnabledHooked, fuseReqUserdataHooked,
                                       fuseBpfInstallHooked,
                                       MatchMethodName(embeddedResult.startsWithMethod),
                                       MatchMethodName(embeddedResult.containsMountMethod),
                                       MatchMethodName(embeddedResult.isFuseBpfEnabledMethod),
                                       MatchMethodName(embeddedResult.fuseReqUserdataMethod),
                                       MatchMethodName(embeddedResult.fuseBpfInstallMethod),
                                       false, lastError.c_str());
        }
        if (startsWithRequired) {
            const char *startsWithSymbol = AY_OBFUSCATE(
                    "_ZN7android4base10StartsWithENSt6__ndk117basic_string_viewIcNS1_11char_traitsIcEEEES5_");
            auto startsWith = handle == nullptr ? nullptr : dlsym(handle, startsWithSymbol);
            auto startsWithRegistered = RegisterHook(startsWithSymbol, (void *) new_StartsWith,
                                                     (void **) &old_StartsWith);
            startsWithHooked = startsWith != nullptr && startsWithRegistered;
            if (startsWithHooked) {
                startsWithMethod = "exact";
            }
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
        if (containsMountHooked) {
            containsMountMethod = "exact";
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
        if (isFuseBpfEnabledHooked) {
            isFuseBpfEnabledMethod = "exact";
        }
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
        if (fuseReqUserdataHooked) {
            fuseReqUserdataMethod = "exact";
        }
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
        if (fuseBpfInstallHooked) {
            fuseBpfInstallMethod = "exact";
        }
        if (!fuseBpfInstallHooked) {
            LOGE("%s", std::string(AY_OBFUSCATE("failed to find fuse_bpf_install")).c_str()); // "failed to find fuse_bpf_install"
            if (handle != nullptr) {
                lastError = "failed to find fuse_bpf_install";
            }
        }

        xhook_refresh(0);
        xhookRefreshCalled = true;

        // If any symbol failed exact xhook match, try GOT Patch as fallback
        const bool xhookAllSucceeded = (!startsWithRequired || startsWithHooked) && containsMountHooked
                               && isFuseBpfEnabledHooked && fuseReqUserdataHooked
                               && fuseBpfInstallHooked;

        bool fallbackResolvedAny = false;
        if (!xhookAllSucceeded) {
            auto embeddedResult = HookEmbeddedFuseJni();
            // Merge: GOT Patch succeeded where xhook failed
            if (!startsWithHooked && embeddedResult.startsWithHooked) {
                startsWithHooked = true;
                startsWithMethod = MatchMethodName(embeddedResult.startsWithMethod);
                fallbackResolvedAny = true;
            }
            if (!containsMountHooked && embeddedResult.containsMountHooked) {
                containsMountHooked = true;
                containsMountMethod = MatchMethodName(embeddedResult.containsMountMethod);
                fallbackResolvedAny = true;
            }
            if (!isFuseBpfEnabledHooked && embeddedResult.isFuseBpfEnabledHooked) {
                isFuseBpfEnabledHooked = true;
                isFuseBpfEnabledMethod = MatchMethodName(embeddedResult.isFuseBpfEnabledMethod);
                fallbackResolvedAny = true;
            }
            if (!fuseReqUserdataHooked && embeddedResult.fuseReqUserdataHooked) {
                fuseReqUserdataHooked = true;
                fuseReqUserdataMethod = MatchMethodName(embeddedResult.fuseReqUserdataMethod);
                fallbackResolvedAny = true;
            }
            if (!fuseBpfInstallHooked && embeddedResult.fuseBpfInstallHooked) {
                fuseBpfInstallHooked = true;
                fuseBpfInstallMethod = MatchMethodName(embeddedResult.fuseBpfInstallMethod);
                fallbackResolvedAny = true;
            }
            const bool hookAvailableAfterFallback =
                    (!startsWithRequired || startsWithHooked) && containsMountHooked
                    && isFuseBpfEnabledHooked && fuseReqUserdataHooked
                    && fuseBpfInstallHooked;
            if (hookAvailableAfterFallback && fallbackResolvedAny) {
                lastError = "some symbols resolved via GOT patch fallback";
            } else if (!hookAvailableAfterFallback) {
                lastError = "native symbols missing after xhook/GOT fallback";
            }
        }

        const char *hookMode = xhookAllSucceeded
                               ? "XHOOK"
                               : (fallbackResolvedAny
                                  ? "XHOOK_WITH_GOT_FALLBACK"
                                  : "XHOOK_PARTIAL");
        return BuildHookStatusJson(true, fuseLibraryMapped, "libfuse_jni.so",
                                   hookMode,
                                   "SYSTEM_LIB", false,
                                   startsWithHooked, containsMountHooked,
                                   isFuseBpfEnabledHooked, fuseReqUserdataHooked,
                                   fuseBpfInstallHooked,
                                   startsWithMethod, containsMountMethod,
                                   isFuseBpfEnabledMethod, fuseReqUserdataMethod,
                                   fuseBpfInstallMethod,
                                   xhookRefreshCalled, lastError.c_str());
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
