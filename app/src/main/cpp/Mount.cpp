#include <cerrno>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <ios>
#include <jni.h>
#include <mutex>
#include <sstream>
#include <string>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <sys/mount.h>
#include <unistd.h>

#include "android-base/stringprintf.h"
#include "android-base/unique_fd.h"
#include "android_filesystem_config.h"
#include "linux_syscall_support.h"
#include "logging.h"
#include "misc.h"
#include "obfs-string.h"
#include "socket.h"
#include "Mount.h"

using android::base::StringPrintf;

struct MountStatus {
    int ok;
    int err;
    int index;
    char stage[32];
    char source[PATH_MAX];
    char target[PATH_MAX];
};

static MountStatus make_mount_status(int ok, const char *stage, int err = 0, int index = -1,
                                     const char *source = nullptr, const char *target = nullptr) {
    MountStatus status{};
    status.ok = ok;
    status.err = err;
    status.index = index;
    snprintf(status.stage, sizeof(status.stage), "%s", stage == nullptr ? "" : stage);
    snprintf(status.source, sizeof(status.source), "%s", source == nullptr ? "" : source);
    snprintf(status.target, sizeof(status.target), "%s", target == nullptr ? "" : target);
    return status;
}

static bool write_mount_status(int fd, const MountStatus &status) {
    return fd >= 0 && write_full(fd, &status, sizeof(status)) == 0;
}

static bool read_mount_status(int fd, MountStatus *status) {
    return fd >= 0 && status != nullptr && read_full(fd, status, sizeof(*status)) == 0;
}

static std::string json_escape(const char *value) {
    std::ostringstream out;
    if (value == nullptr) {
        return "";
    }
    for (const char *p = value; *p; ++p) {
        switch (*p) {
            case '\\':
                out << "\\\\";
                break;
            case '"':
                out << "\\\"";
                break;
            case '\n':
                out << "\\n";
                break;
            case '\r':
                out << "\\r";
                break;
            case '\t':
                out << "\\t";
                break;
            default:
                out << *p;
        }
    }
    return out.str();
}

static std::string mount_status_to_json(const MountStatus &status, int pid, int uid) {
    std::ostringstream out;
    out << "{\"success\":" << (status.ok == 0 ? "true" : "false")
        << ",\"stage\":\"" << json_escape(status.stage) << "\""
        << ",\"errno\":" << status.err
        << ",\"error\":\"" << json_escape(status.err == 0 ? "" : strerror(status.err)) << "\""
        << ",\"failedIndex\":" << status.index
        << ",\"pid\":" << pid
        << ",\"uid\":" << uid
        << ",\"source\":\"" << json_escape(status.source) << "\""
        << ",\"target\":\"" << json_escape(status.target) << "\"}";
    return out.str();
}

static bool is_storage_path(const char *path) {
    if (path == nullptr) {
        return false;
    }
    return strncmp(path, "/storage/"_iobfs.c_str(), strlen("/storage/"_iobfs.c_str())) == 0;
}

static void fail_child(int sock, const char *stage, int err, int index = -1,
                       const char *source = nullptr, const char *target = nullptr) {
    write_mount_status(sock, make_mount_status(-1, stage, err, index, source, target));
    sys__exit(1);
}

/// wait for system mount
static bool wait_zygote(int pid) {
    const int sleep_time = 5 * 1000;
    int slept_time = 0;
    std::string path = StringPrintf("/proc/%d/attr/current"_iobfs.c_str(), pid);
    if (access(path.c_str(), F_OK)) {
        return false;
    }
    char nice_name[PATH_MAX];
    while (true) {
        int fd = sys_open(path.c_str(), O_RDONLY, 0);
        nice_name[sys_read(fd, nice_name, sizeof(nice_name) - 1)] = 0;
        sys_close(fd);
        if (!strcmp("u:r:zygote:s0"_iobfs.c_str(), nice_name)) {
            usleep(sleep_time);
            slept_time += sleep_time;
            if (slept_time > 5 * 1000 * 1000) {
                // Waited more than 5s.
                return false;
            }
        } else {
#ifdef DEBUG
            // LOGE("wait %d for %dms"_iobfs.c_str(), pid, slept_time / 1000);
#endif
            return true;
        }
    }
}

/// @see https://android.googlesource.com/platform/frameworks/native/+/master/cmds/dumpstate/DumpstateUtil.cpp#46
static bool waitpid_with_timeout(pid_t pid, int timeout_ms, int *status) {
    sigset_t child_mask, old_mask;
    sigemptyset(&child_mask);
    sigaddset(&child_mask, SIGCHLD);
    // block SIGCHLD before we check if a process has exited
    if (sigprocmask(SIG_BLOCK, &child_mask, &old_mask) == -1) {
        printf("*** sigprocmask failed: %s\n"_iobfs.c_str(), strerror(errno));
        return false;
    }
    // if the child has exited already, handle and reset signals before leaving
    pid_t child_pid = sys_waitpid(pid, status, WNOHANG);
    if (child_pid != pid) {
        if (child_pid > 0) {
            printf("*** Waiting for pid %d, got pid %d instead\n"_iobfs.c_str(), pid, child_pid);
            sigprocmask(SIG_SETMASK, &old_mask, nullptr);
            return false;
        }
    } else {
        sigprocmask(SIG_SETMASK, &old_mask, nullptr);
        return true;
    }
    // wait for a SIGCHLD
    timespec ts;
    ts.tv_sec = timeout_ms / 1000;
    ts.tv_nsec = (timeout_ms % 1000) * 1000000;
    int ret = TEMP_FAILURE_RETRY(sigtimedwait(&child_mask, nullptr, &ts));
    int saved_errno = errno;
    // Set the signals back the way they were.
    if (sigprocmask(SIG_SETMASK, &old_mask, nullptr) == -1) {
        printf("*** sigprocmask failed: %s\n"_iobfs.c_str(), strerror(errno));
        if (ret == 0) {
            return false;
        }
    }
    if (ret == -1) {
        errno = saved_errno;
        if (errno == EAGAIN) {
            errno = ETIMEDOUT;
        } else {
            printf("*** sigtimedwait failed: %s\n"_iobfs.c_str(), strerror(errno));
        }
        return false;
    }
    child_pid = sys_waitpid(pid, status, WNOHANG);
    if (child_pid != pid) {
        if (child_pid != -1) {
            printf("*** Waiting for pid %d, got pid %d instead\n"_iobfs.c_str(), pid, child_pid);
        } else {
            printf("*** waitpid failed: %s\n"_iobfs.c_str(), strerror(errno));
        }
        return false;
    }
    return true;
}

static void kill_child_if_stuck(int child) {
    int status;
    if (!waitpid_with_timeout(child, 1000, &status) || errno == ETIMEDOUT) {
        sys_kill(child, SIGKILL);
    }
}

static bool switch_mnt_ns(int pid) {
    std::string mnt = StringPrintf("/proc/%d/ns/mnt"_iobfs.c_str(), pid);
    int nsFd = TEMP_FAILURE_RETRY(sys_open(mnt.c_str(), O_RDONLY | O_CLOEXEC, 0));
    if (nsFd == -1) {
        LOGE("Unable to open %s"_iobfs.c_str(), mnt.c_str());
        return false;
    }
    if (setns(nsFd, CLONE_NEWNS) != 0) {
        LOGE("Failed to setns %s"_iobfs.c_str(), strerror(errno));
        return false;
    }
    return true;
}

static bool isFuse() {
    char prop[PROP_VALUE_MAX] = {0};
    __system_property_get("persist.sys.fuse"_iobfs.c_str(), prop);
    return !strcmp(prop, "true"_iobfs.c_str());
}

namespace Mount {
    static MountStatus bind_mount_internal(JNIEnv *env, jint pid, jint uid,
                                           jboolean unmountDataRestriction,
                                           jboolean fuseBypass, jobjectArray jsources,
                                           jobjectArray jtargets) {
        /// @see /system/vold/Utils.cpp IsSdcardfsUsed()
        const bool useSdcardFs = !isFuse();
        const uid_t user_id = uid / AID_USER_OFFSET;
        const std::string storage = "/storage"_iobfs.c_str();
        const std::string storageSource = "/mnt/runtime/write"_iobfs.c_str();
        const std::string userSource = StringPrintf("/mnt/user/%d"_iobfs.c_str(), user_id);

        if (jsources == nullptr || jtargets == nullptr ||
            env->GetArrayLength(jsources) != env->GetArrayLength(jtargets)) {
            return make_mount_status(-1, "invalid_args", EINVAL);
        }
        if (!wait_zygote(pid)) {
            return make_mount_status(-1, "wait_zygote", errno == 0 ? ETIMEDOUT : errno);
        }
        int sv[2];
        if (socketpair(AF_UNIX, SOCK_DGRAM, 0, sv) != 0) {
            LOGE("%s"_iobfs.c_str(), "Failed to create Unix-domain socket pair"_iobfs.c_str());
            return make_mount_status(-1, "socketpair", errno);
        }
        int child_pid = sys_fork();
        if (child_pid) {
            // In the parent process.
            if (child_pid == -1) {
                const int saved_errno = errno;
                close(sv[0]);
                close(sv[1]);
                return make_mount_status(-1, "fork", saved_errno);
            } else {
                int sock = sv[0];
                set_socket_timeout(sock, 1);
                MountStatus status{};
                if (!read_mount_status(sock, &status)) {
                    // Child stuck, kill it.
                    sys_kill(child_pid, SIGKILL);
                    status = make_mount_status(-1, "child_result_timeout", errno == 0 ? ETIMEDOUT : errno);
                }
                close(sv[0]);
                close(sv[1]);
                // setns() may stuck the child, kill if this happen.
                kill_child_if_stuck(child_pid);
                return status;
            }
        }
        int sock = sv[1];
        // In the child process we switch to the new namespace and start mounting,
        // so that all mount operations will not affect parent process.
        if (!switch_mnt_ns(pid)) {
            fail_child(sock, "setns", errno == 0 ? EPERM : errno);
        }
#ifdef REMOUNT_STORAGE
        if (TEMP_FAILURE_RETRY(umount2("/storage/"_iobfs.c_str(), UMOUNT_NOFOLLOW | MNT_DETACH)) <
            0 && errno != EINVAL && errno != ENOENT) {
            LOGE("Failed to unmount /storage/: %s"_iobfs.c_str(), strerror(errno));
            fail_child(sock, "unmount_storage", errno, -1, nullptr, "/storage/"_iobfs.c_str());
        }
        /// @see EmulatedVolume::doMount()
        if (useSdcardFs) {
            /// @see /system/vold/VolumeManager.cpp forkAndRemountChild()
            if (TEMP_FAILURE_RETRY(
                    mount(storageSource.c_str(), storage.c_str(), nullptr, MS_BIND | MS_REC,
                          nullptr))) {
                LOGE("Failed to mount %s for %d: %s"_iobfs.c_str(), storageSource.c_str(), pid,
                     strerror(errno));
                fail_child(sock, "remount_storage", errno, -1, storageSource.c_str(),
                           storage.c_str());
            }
            if (TEMP_FAILURE_RETRY(
                    mount(nullptr, storage.c_str(), nullptr, MS_REC | MS_SLAVE, nullptr))) {
                LOGE("Failed to set MS_SLAVE to /storage for %d: %s"_iobfs.c_str(), pid,
                     strerror(errno));
                fail_child(sock, "remount_storage_slave", errno, -1, nullptr, storage.c_str());
            }
            if (TEMP_FAILURE_RETRY(
                    mount(userSource.c_str(), "/storage/self"_iobfs.c_str(), nullptr, MS_BIND,
                          nullptr))) {
                LOGE("Failed to mount %s for %d: %s"_iobfs.c_str(), userSource.c_str(), pid,
                     strerror(errno));
                fail_child(sock, "mount_storage_self", errno, -1, userSource.c_str(),
                           "/storage/self"_iobfs.c_str());
            }
        } else {
            if (TEMP_FAILURE_RETRY(
                    mount(userSource.c_str(), storage.c_str(), nullptr, MS_BIND | MS_REC,
                          nullptr))) {
                LOGE("Failed to mount %s to %s: %s"_iobfs.c_str(),
                     userSource.c_str(), storage.c_str(), strerror(errno));
                fail_child(sock, "remount_storage", errno, -1, userSource.c_str(),
                           storage.c_str());
            }
        }
        // some little tricks on the system with FUSE enabled
        if (!useSdcardFs) {
            // unmount /Android/data to intercept filesystem operations in app-specific dir.
            if (unmountDataRestriction) {
                const std::string fuseDataDir = StringPrintf(
                        "/mnt/user/%d/emulated/%d/Android/data"_iobfs.c_str(), user_id, user_id);
                if (TEMP_FAILURE_RETRY(umount2(fuseDataDir.c_str(), UMOUNT_NOFOLLOW)) < 0 &&
                    errno != EINVAL && errno != ENOENT) {
                    LOGE("Failed to unmount fuseDataDir: %s"_iobfs.c_str(), strerror(errno));
                }
                const std::string androidDataDir = StringPrintf(
                        "/storage/emulated/%d/Android/data"_iobfs.c_str(), user_id);
                if (TEMP_FAILURE_RETRY(umount2(androidDataDir.c_str(), UMOUNT_NOFOLLOW)) < 0 &&
                    errno != EINVAL && errno != ENOENT) {
                    LOGE("Failed to unmount androidDataDir: %s"_iobfs.c_str(), strerror(errno));
                }
            }
            if (fuseBypass) {
                const std::string androidDataSourceDir = StringPrintf(
                        "/data/media/%d/Android/data"_iobfs.c_str(), user_id);
                const std::string androidDataFuseDir = StringPrintf(
                        "/mnt/user/%d/emulated/%d/Android/data"_iobfs.c_str(), user_id, user_id);
                if (TEMP_FAILURE_RETRY(
                        mount(androidDataSourceDir.c_str(), androidDataFuseDir.c_str(), nullptr,
                              MS_BIND | MS_REC, nullptr))) {
                    LOGE("Failed to mount %s to %s: %s"_iobfs.c_str(),
                         androidDataSourceDir.c_str(), androidDataFuseDir.c_str(), strerror(errno));
                    fail_child(sock, "fuse_bypass_data_source", errno, -1,
                               androidDataSourceDir.c_str(), androidDataFuseDir.c_str());
                }
                const std::string androidObbSourceDir = StringPrintf(
                        "/data/media/%d/Android/obb"_iobfs.c_str(), user_id);
                const std::string androidObbFuseDir = StringPrintf(
                        "/mnt/user/%d/emulated/%d/Android/obb"_iobfs.c_str(), user_id, user_id);
                if (TEMP_FAILURE_RETRY(
                        mount(androidObbSourceDir.c_str(), androidObbFuseDir.c_str(), nullptr,
                              MS_BIND | MS_REC, nullptr))) {
                    LOGE("Failed to mount %s to %s: %s"_iobfs.c_str(),
                         androidObbSourceDir.c_str(), androidObbFuseDir.c_str(), strerror(errno));
                    fail_child(sock, "fuse_bypass_obb_source", errno, -1,
                               androidObbSourceDir.c_str(), androidObbFuseDir.c_str());
                }

                const std::string androidDataDir = StringPrintf(
                        "/storage/emulated/%d/Android/data"_iobfs.c_str(), user_id);
                if (TEMP_FAILURE_RETRY(
                        mount(androidDataFuseDir.c_str(), androidDataDir.c_str(), nullptr,
                              MS_BIND | MS_REC, nullptr))) {
                    LOGE("Failed to mount %s to %s: %s"_iobfs.c_str(),
                         androidDataFuseDir.c_str(), androidDataDir.c_str(), strerror(errno));
                    fail_child(sock, "fuse_bypass_data_target", errno, -1,
                               androidDataFuseDir.c_str(), androidDataDir.c_str());
                }
                const std::string androidObbDir = StringPrintf(
                        "/storage/emulated/%d/Android/obb"_iobfs.c_str(), user_id);
                if (TEMP_FAILURE_RETRY(
                        mount(androidObbFuseDir.c_str(), androidObbDir.c_str(), nullptr,
                              MS_BIND | MS_REC, nullptr))) {
                    LOGE("Failed to mount %s to %s: %s"_iobfs.c_str(),
                         androidObbFuseDir.c_str(), androidObbDir.c_str(), strerror(errno));
                    fail_child(sock, "fuse_bypass_obb_target", errno, -1,
                               androidObbFuseDir.c_str(), androidObbDir.c_str());
                }
            }
        }
#endif // REMOUNT_STORAGE
        // Mount as user wish.
        for (int i = 0, length = env->GetArrayLength(jsources); i < length; i++) {
            auto jsource = (jstring) env->GetObjectArrayElement(jsources, i);
            if (jsource == nullptr) {
                fail_child(sock, "invalid_source", EINVAL, i);
            }
            const char *source = env->GetStringUTFChars(jsource, nullptr);
            if (source == nullptr) {
                fail_child(sock, "invalid_source", EINVAL, i);
            }
            auto jtarget = (jstring) env->GetObjectArrayElement(jtargets, i);
            if (jtarget == nullptr) {
                fail_child(sock, "invalid_target", EINVAL, i, source);
            }
            const char *target = env->GetStringUTFChars(jtarget, nullptr);
            if (target == nullptr) {
                fail_child(sock, "invalid_target", EINVAL, i, source);
            }
            if (!is_storage_path(source)) {
                fail_child(sock, "invalid_source", EINVAL, i, source, target);
            }
            if (!is_storage_path(target)) {
                fail_child(sock, "invalid_target", EINVAL, i, source, target);
            }
            const std::string mnt_source = (useSdcardFs ? storageSource : userSource) +
                                           std::string(source).substr(storage.length(),
                                                                      strlen(source));
            if (TEMP_FAILURE_RETRY(
                    mount(mnt_source.c_str(), target, nullptr, MS_BIND | MS_REC, nullptr))) {
                LOGE("Failed to mount %s to %s: %s"_iobfs.c_str(),
                     mnt_source.c_str(), target, strerror(errno));
                fail_child(sock, "mount_rule", errno, i, mnt_source.c_str(), target);
            }
            env->ReleaseStringUTFChars(jsource, source);
            env->ReleaseStringUTFChars(jtarget, target);
            env->DeleteLocalRef(jsource);
            env->DeleteLocalRef(jtarget);
        }
        write_mount_status(sock, make_mount_status(0, "success"));
        close(sv[0]);
        close(sv[1]);
        sys__exit(0);
        return make_mount_status(0, "success");
    }

    jboolean bind_mount(JNIEnv *env, jclass clazz, jint pid, jint uid,
                        jboolean unmountDataRestriction,
                        jboolean fuseBypass, jobjectArray jsources, jobjectArray jtargets) {
        return bind_mount_internal(env, pid, uid, unmountDataRestriction, fuseBypass,
                                   jsources, jtargets).ok == 0;
    }

    jstring bind_mount_status(JNIEnv *env, jclass clazz, jint pid, jint uid,
                              jboolean unmountDataRestriction,
                              jboolean fuseBypass, jobjectArray jsources, jobjectArray jtargets) {
        const auto status = bind_mount_internal(env, pid, uid, unmountDataRestriction, fuseBypass,
                                                jsources, jtargets);
        const auto json = mount_status_to_json(status, pid, uid);
        return env->NewStringUTF(json.c_str());
    }
}  // namespace Mount
