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
#include <sys/mount.h>
#include <unistd.h>

#include "android-base/stringprintf.h"
#include "android-base/unique_fd.h"
#include "android_filesystem_config.h"
#include "fuse_policy.h"
#include "linux_syscall_support.h"
#include "logging.h"
#include "misc.h"
#include "obfs-string.h"
#include "socket.h"
#include "Mount.h"

using android::base::StringPrintf;

/// 挂载事务的阶段划分：用于错误定位与进度上报。
enum class MountPhase : int {
    UNKNOWN = -1,
    ARGS = 0,
    ZYGOTE_WAIT = 1,
    NAMESPACE = 2,
    BASELINE = 3,
    RULES = 4,
    REPORT = 5,
};

static const char *mount_phase_name(MountPhase phase) {
    switch (phase) {
        case MountPhase::ARGS:
            return "args";
        case MountPhase::ZYGOTE_WAIT:
            return "zygote_wait";
        case MountPhase::NAMESPACE:
            return "namespace";
        case MountPhase::BASELINE:
            return "baseline";
        case MountPhase::RULES:
            return "rules";
        case MountPhase::REPORT:
            return "report";
        default:
            return "unknown";
    }
}

/// socket 消息类型：RESULT 为终态消息（恰好一条），PROGRESS 为过程消息（零或多条）。
enum class MountMessageType : int {
    RESULT = 0,
    PROGRESS = 1,
};

/// MountStatus 的 socket wire 协议版本：父子进程同 APK 编译部署，仅防混版兜底。
constexpr int kMountStatusSchemaVersion = 1;

struct MountStatus {
    int schema_version;
    int message_type;
    int phase;
    int ok;
    int err;
    int index;
    int namespace_dirty;
    char stage[32];
    char source[PATH_MAX];
    char target[PATH_MAX];
};

static void copy_status_value(char *destination, size_t size, const char *value) {
    if (size == 0) return;
    if (value == nullptr) {
        destination[0] = '\0';
        return;
    }
    snprintf(destination, size, "%s", value);
}

static MountStatus make_mount_status(int ok, const char *stage, int err = 0, int index = -1,
                                     const char *source = nullptr, const char *target = nullptr,
                                     MountPhase phase = MountPhase::UNKNOWN) {
    MountStatus status{};
    status.schema_version = kMountStatusSchemaVersion;
    status.message_type = static_cast<int>(MountMessageType::RESULT);
    status.phase = static_cast<int>(phase);
    status.ok = ok;
    status.err = err;
    status.index = index;
    status.namespace_dirty = 0;
    copy_status_value(status.stage, sizeof(status.stage), stage);
    copy_status_value(status.source, sizeof(status.source), source);
    copy_status_value(status.target, sizeof(status.target), target);
    return status;
}

static bool write_mount_progress_with_index(int fd, MountPhase phase, int index) {
    MountStatus progress{};
    progress.schema_version = kMountStatusSchemaVersion;
    progress.message_type = static_cast<int>(MountMessageType::PROGRESS);
    progress.phase = static_cast<int>(phase);
    progress.index = index;
    copy_status_value(progress.stage, sizeof(progress.stage), mount_phase_name(phase));
    return fd >= 0 && write_full(fd, &progress, sizeof(progress)) == 0;
}

static bool write_mount_progress(int fd, MountPhase phase) {
    return write_mount_progress_with_index(fd, phase, -1);
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

static std::string mount_status_to_json(const MountStatus &status, int pid, int uid,
                                        MountPhase last_progress_phase = MountPhase::UNKNOWN,
                                        int last_progress_index = -1) {
    std::ostringstream out;
    out << "{\"schemaVersion\":" << status.schema_version
        << ",\"messageType\":" << status.message_type
        << ",\"success\":" << (status.ok == 0 ? "true" : "false")
        << ",\"stage\":\"" << json_escape(status.stage) << "\""
        << ",\"phase\":" << status.phase
        << ",\"phaseName\":\"" << mount_phase_name(static_cast<MountPhase>(status.phase)) << "\"";
    if (last_progress_phase != MountPhase::UNKNOWN) {
        // 仅在 RESULT 缺失（如子进程被看门狗终止）时，以最后进度补足定位信息。
        out << ",\"lastProgressPhase\":\"" << mount_phase_name(last_progress_phase) << "\""
            << ",\"lastProgressIndex\":" << last_progress_index;
    }
    out << ",\"errno\":" << status.err
        << ",\"error\":\"" << json_escape(status.err == 0 ? "" : strerror(status.err)) << "\""
        << ",\"failedIndex\":" << status.index
        << ",\"namespaceDirty\":" << (status.namespace_dirty != 0 ? "true" : "false")
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
    const char *storage = "/storage/"_iobfs.c_str();
    if (strncmp(path, storage, strlen(storage)) != 0) {
        return false;
    }
    for (const char *p = path; *p != '\0'; ++p) {
        if (*p != '/') {
            continue;
        }
        if (p[1] == '/') {
            return false;
        }
        if (p[1] == '.' && (p[2] == '/' || p[2] == '\0')) {
            return false;
        }
        if (p[1] == '.' && p[2] == '.' && (p[3] == '/' || p[3] == '\0')) {
            return false;
        }
    }
    return true;
}

static void fail_child(int sock, const char *stage, int err, int index = -1,
                       const char *source = nullptr, const char *target = nullptr,
                       MountPhase phase = MountPhase::UNKNOWN) {
    write_mount_status(sock, make_mount_status(-1, stage, err, index, source, target, phase));
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

namespace Mount {
    static MountStatus bind_mount_internal(JNIEnv *env, jint pid, jint uid,
                                           jboolean unmountDataRestriction,
                                           jboolean fuseBypass, jobjectArray jsources,
                                           jobjectArray jtargets) {
        /// @see /system/vold/Utils.cpp IsSdcardfsUsed()
        const bool useSdcardFs = !storage_platform::is_fuse_available();
        const uid_t user_id = uid / AID_USER_OFFSET;
        const std::string storage = "/storage"_iobfs.c_str();
        const std::string storageSource = "/mnt/runtime/write"_iobfs.c_str();
        const std::string userSource = StringPrintf("/mnt/user/%d"_iobfs.c_str(), user_id);

        if (jsources == nullptr || jtargets == nullptr ||
            env->GetArrayLength(jsources) != env->GetArrayLength(jtargets)) {
            return make_mount_status(-1, "invalid_args", EINVAL, -1, nullptr, nullptr,
                                     MountPhase::ARGS);
        }
        if (!wait_zygote(pid)) {
            return make_mount_status(-1, "wait_zygote", errno == 0 ? ETIMEDOUT : errno,
                                     -1, nullptr, nullptr, MountPhase::ZYGOTE_WAIT);
        }
        int sv[2];
        if (socketpair(AF_UNIX, SOCK_DGRAM, 0, sv) != 0) {
            LOGE("%s"_iobfs.c_str(), "Failed to create Unix-domain socket pair"_iobfs.c_str());
            return make_mount_status(-1, "socketpair", errno, -1, nullptr, nullptr,
                                     MountPhase::NAMESPACE);
        }
        int child_pid = sys_fork();
        if (child_pid) {
            // In the parent process.
            if (child_pid == -1) {
                const int saved_errno = errno;
                close(sv[0]);
                close(sv[1]);
                return make_mount_status(-1, "fork", saved_errno, -1, nullptr, nullptr,
                                         MountPhase::NAMESPACE);
            } else {
                int sock = sv[0];
                set_socket_timeout(sock, 1);
                // 消息循环：PROGRESS 记录最后进度用于超时定位，RESULT 终止循环。
                MountStatus status{};
                bool got_result = false;
                MountPhase last_progress_phase = MountPhase::UNKNOWN;
                int last_progress_index = -1;
                while (!got_result) {
                    MountStatus message{};
                    if (!read_mount_status(sock, &message)) {
                        // Child stuck, kill it.
                        sys_kill(child_pid, SIGKILL);
                        status = make_mount_status(-1, "child_result_timeout",
                                                   errno == 0 ? ETIMEDOUT : errno);
                        break;
                    }
                    if (static_cast<MountMessageType>(message.message_type)
                        == MountMessageType::PROGRESS) {
                        last_progress_phase = static_cast<MountPhase>(message.phase);
                        last_progress_index = message.index;
                        continue;
                    }
                    status = message;
                    got_result = true;
                }
                close(sv[0]);
                close(sv[1]);
                // setns() may stuck the child, kill if this happen.
                kill_child_if_stuck(child_pid);
                if (!got_result && last_progress_phase != MountPhase::UNKNOWN) {
                    // 超时场景：以最后进度阶段补足定位信息，stage 保留超时标记。
                    copy_status_value(status.stage, sizeof(status.stage), "child_result_timeout");
                    status.phase = static_cast<int>(last_progress_phase);
                    status.index = last_progress_index;
                }
                return status;
            }
        }
        int sock = sv[1];
        // In the child process we switch to the new namespace and start mounting,
        // so that all mount operations will not affect parent process.
        if (!switch_mnt_ns(pid)) {
            fail_child(sock, "setns", errno == 0 ? EPERM : errno, -1, nullptr, nullptr,
                       MountPhase::NAMESPACE);
        }
#ifdef REMOUNT_STORAGE
        write_mount_progress(sock, MountPhase::BASELINE);
        if (TEMP_FAILURE_RETRY(umount2("/storage/"_iobfs.c_str(), UMOUNT_NOFOLLOW | MNT_DETACH)) <
            0 && errno != EINVAL && errno != ENOENT) {
            LOGE("Failed to unmount /storage/: %s"_iobfs.c_str(), strerror(errno));
            fail_child(sock, "unmount_storage", errno, -1, nullptr, "/storage/"_iobfs.c_str(),
                       MountPhase::BASELINE);
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
                           storage.c_str(), MountPhase::BASELINE);
            }
            if (TEMP_FAILURE_RETRY(
                    mount(nullptr, storage.c_str(), nullptr, MS_REC | MS_SLAVE, nullptr))) {
                LOGE("Failed to set MS_SLAVE to /storage for %d: %s"_iobfs.c_str(), pid,
                     strerror(errno));
                fail_child(sock, "remount_storage_slave", errno, -1, nullptr, storage.c_str(),
                           MountPhase::BASELINE);
            }
            if (TEMP_FAILURE_RETRY(
                    mount(userSource.c_str(), "/storage/self"_iobfs.c_str(), nullptr, MS_BIND,
                          nullptr))) {
                LOGE("Failed to mount %s for %d: %s"_iobfs.c_str(), userSource.c_str(), pid,
                     strerror(errno));
                fail_child(sock, "mount_storage_self", errno, -1, userSource.c_str(),
                           "/storage/self"_iobfs.c_str(), MountPhase::BASELINE);
            }
        } else {
            if (TEMP_FAILURE_RETRY(
                    mount(userSource.c_str(), storage.c_str(), nullptr, MS_BIND | MS_REC,
                          nullptr))) {
                LOGE("Failed to mount %s to %s: %s"_iobfs.c_str(),
                     userSource.c_str(), storage.c_str(), strerror(errno));
                fail_child(sock, "remount_storage", errno, -1, userSource.c_str(),
                           storage.c_str(), MountPhase::BASELINE);
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
                               androidDataSourceDir.c_str(), androidDataFuseDir.c_str(),
                               MountPhase::BASELINE);
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
                               androidObbSourceDir.c_str(), androidObbFuseDir.c_str(),
                               MountPhase::BASELINE);
                }

                const std::string androidDataDir = StringPrintf(
                        "/storage/emulated/%d/Android/data"_iobfs.c_str(), user_id);
                if (TEMP_FAILURE_RETRY(
                        mount(androidDataFuseDir.c_str(), androidDataDir.c_str(), nullptr,
                              MS_BIND | MS_REC, nullptr))) {
                    LOGE("Failed to mount %s to %s: %s"_iobfs.c_str(),
                         androidDataFuseDir.c_str(), androidDataDir.c_str(), strerror(errno));
                    fail_child(sock, "fuse_bypass_data_target", errno, -1,
                               androidDataFuseDir.c_str(), androidDataDir.c_str(),
                               MountPhase::BASELINE);
                }
                const std::string androidObbDir = StringPrintf(
                        "/storage/emulated/%d/Android/obb"_iobfs.c_str(), user_id);
                if (TEMP_FAILURE_RETRY(
                        mount(androidObbFuseDir.c_str(), androidObbDir.c_str(), nullptr,
                              MS_BIND | MS_REC, nullptr))) {
                    LOGE("Failed to mount %s to %s: %s"_iobfs.c_str(),
                         androidObbFuseDir.c_str(), androidObbDir.c_str(), strerror(errno));
                    fail_child(sock, "fuse_bypass_obb_target", errno, -1,
                               androidObbFuseDir.c_str(), androidObbDir.c_str(),
                               MountPhase::BASELINE);
                }
            }
        }
#endif // REMOUNT_STORAGE
        // Mount as user wish.
        for (int i = 0, length = env->GetArrayLength(jsources); i < length; i++) {
            write_mount_progress_with_index(sock, MountPhase::RULES, i);
            auto jsource = (jstring) env->GetObjectArrayElement(jsources, i);
            if (jsource == nullptr) {
                fail_child(sock, "invalid_source", EINVAL, i, nullptr, nullptr,
                           MountPhase::RULES);
            }
            const char *source = env->GetStringUTFChars(jsource, nullptr);
            if (source == nullptr) {
                fail_child(sock, "invalid_source", EINVAL, i, nullptr, nullptr,
                           MountPhase::RULES);
            }
            auto jtarget = (jstring) env->GetObjectArrayElement(jtargets, i);
            if (jtarget == nullptr) {
                fail_child(sock, "invalid_target", EINVAL, i, source, nullptr,
                           MountPhase::RULES);
            }
            const char *target = env->GetStringUTFChars(jtarget, nullptr);
            if (target == nullptr) {
                fail_child(sock, "invalid_target", EINVAL, i, source, nullptr,
                           MountPhase::RULES);
            }
            if (!is_storage_path(source)) {
                fail_child(sock, "invalid_source", EINVAL, i, source, target,
                           MountPhase::RULES);
            }
            if (!is_storage_path(target)) {
                fail_child(sock, "invalid_target", EINVAL, i, source, target,
                           MountPhase::RULES);
            }
            const std::string mnt_source = (useSdcardFs ? storageSource : userSource) +
                                           std::string(source).substr(storage.length(),
                                                                      strlen(source));
            if (TEMP_FAILURE_RETRY(
                    mount(mnt_source.c_str(), target, nullptr, MS_BIND | MS_REC, nullptr))) {
                LOGE("Failed to mount %s to %s: %s"_iobfs.c_str(),
                     mnt_source.c_str(), target, strerror(errno));
                fail_child(sock, "mount_rule", errno, i, mnt_source.c_str(), target,
                           MountPhase::RULES);
            }
            env->ReleaseStringUTFChars(jsource, source);
            env->ReleaseStringUTFChars(jtarget, target);
            env->DeleteLocalRef(jsource);
            env->DeleteLocalRef(jtarget);
        }
        write_mount_status(sock, make_mount_status(0, "success", 0, -1, nullptr, nullptr,
                                                   MountPhase::REPORT));
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
