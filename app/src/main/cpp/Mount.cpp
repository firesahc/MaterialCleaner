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
#include "include/mount_transaction_policy.h"
#include "Mount.h"

using android::base::StringPrintf;

/// 挂载事务阶段：语义对齐 mount_transaction::Phase，
/// 仅变异阶段（MUTATING_BASELINE/APPLYING_RULES/ROLLING_BACK）可能弄脏目标 namespace。
enum class MountPhase : int {
    PREPARE = static_cast<int>(mount_transaction::Phase::PREPARE),
    NAMESPACE_ENTERED = static_cast<int>(mount_transaction::Phase::NAMESPACE_ENTERED),
    MUTATING_BASELINE = static_cast<int>(mount_transaction::Phase::MUTATING_BASELINE),
    BASELINE_READY = static_cast<int>(mount_transaction::Phase::BASELINE_READY),
    APPLYING_RULES = static_cast<int>(mount_transaction::Phase::APPLYING_RULES),
    ROLLING_BACK = static_cast<int>(mount_transaction::Phase::ROLLING_BACK),
    /// RESULT 消息专用终态标注；非 mount_transaction 阶段，恒为安全态。
    COMPLETED = 6,
    UNKNOWN = -1,
};

static const char *mount_phase_name(MountPhase phase) {
    switch (phase) {
        case MountPhase::PREPARE:
            return "prepare";
        case MountPhase::NAMESPACE_ENTERED:
            return "namespace_entered";
        case MountPhase::MUTATING_BASELINE:
            return "mutating_baseline";
        case MountPhase::BASELINE_READY:
            return "baseline_ready";
        case MountPhase::APPLYING_RULES:
            return "applying_rules";
        case MountPhase::ROLLING_BACK:
            return "rolling_back";
        case MountPhase::COMPLETED:
            return "completed";
        default:
            return "unknown";
    }
}

constexpr bool phase_may_have_dirty_namespace(MountPhase phase) {
    return phase != MountPhase::COMPLETED && phase != MountPhase::UNKNOWN &&
            mount_transaction::phase_may_have_dirty_namespace(
                    static_cast<mount_transaction::Phase>(phase));
}

/// socket 消息类型：RESULT 为终态消息（恰好一条），PROGRESS 为过程消息（零或多条）。
enum class MountMessageType : int {
    RESULT = 0,
    PROGRESS = 1,
};

/// MountStatus 的 socket wire 协议版本：父子进程同 APK 编译部署，仅防混版兜底。
constexpr int kMountStatusSchemaVersion = 2;

struct MountStatus {
    int schema_version;
    int message_type;
    int phase;
    int ok;
    int err;
    int index;
    int namespace_dirty;
    int target_terminated;
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
                                     MountPhase phase = MountPhase::UNKNOWN,
                                     bool namespace_dirty = false,
                                     bool target_terminated = false) {
    MountStatus status{};
    status.schema_version = kMountStatusSchemaVersion;
    status.message_type = static_cast<int>(MountMessageType::RESULT);
    status.phase = static_cast<int>(phase);
    status.ok = ok;
    status.err = err;
    status.index = index;
    status.namespace_dirty = namespace_dirty ? 1 : 0;
    status.target_terminated = target_terminated ? 1 : 0;
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
        << ",\"targetTerminated\":" << (status.target_terminated != 0 ? "true" : "false")
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
                       MountPhase phase = MountPhase::UNKNOWN,
                       bool namespace_dirty = false) {
    write_mount_status(sock, make_mount_status(-1, stage, err, index, source, target,
                                               phase, namespace_dirty));
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

/// 解析 /proc/<pid>/stat 第 22 字段（starttime）：PID 被系统复用后该值必然变化。
static bool read_process_start_time(pid_t pid, unsigned long long *start_time) {
    if (pid <= 0 || start_time == nullptr) {
        errno = EINVAL;
        return false;
    }
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/stat", pid);
    const int fd = TEMP_FAILURE_RETRY(sys_open(path, O_RDONLY | O_CLOEXEC, 0));
    if (fd < 0) return false;
    char stat[4096];
    const ssize_t length = TEMP_FAILURE_RETRY(sys_read(fd, stat, sizeof(stat) - 1));
    const int saved_errno = errno;
    sys_close(fd);
    if (length <= 0) {
        errno = length < 0 ? saved_errno : EIO;
        return false;
    }
    stat[length] = '\0';
    // comm 字段可含空格与括号，从最后一个 ')' 之后开始才是数字字段。
    char *cursor = strrchr(stat, ')');
    if (cursor == nullptr || cursor[1] != ' ') {
        errno = EINVAL;
        return false;
    }
    cursor += 2;
    for (int field = 3; field <= 22; ++field) {
        while (*cursor == ' ') ++cursor;
        if (*cursor == '\0') break;
        char *end = cursor;
        while (*end != '\0' && *end != ' ') ++end;
        if (field == 22) {
            const char saved = *end;
            *end = '\0';
            char *parse_end = nullptr;
            errno = 0;
            const unsigned long long value = strtoull(cursor, &parse_end, 10);
            *end = saved;
            if (errno != 0 || parse_end == cursor || parse_end != end || value == 0) {
                errno = EINVAL;
                return false;
            }
            *start_time = value;
            return true;
        }
        cursor = end;
    }
    errno = EINVAL;
    return false;
}

static bool read_process_uid(pid_t pid, uid_t *uid) {
    if (pid <= 0 || uid == nullptr) {
        errno = EINVAL;
        return false;
    }
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/status", pid);
    const int fd = TEMP_FAILURE_RETRY(sys_open(path, O_RDONLY | O_CLOEXEC, 0));
    if (fd < 0) return false;
    char status[4096];
    const ssize_t length = TEMP_FAILURE_RETRY(sys_read(fd, status, sizeof(status) - 1));
    const int saved_errno = errno;
    sys_close(fd);
    if (length <= 0) {
        errno = length < 0 ? saved_errno : EIO;
        return false;
    }
    status[length] = '\0';
    const char *line = strstr(status, "Uid:\t");
    if (line == nullptr) {
        errno = EINVAL;
        return false;
    }
    line += strlen("Uid:\t");
    char *end = nullptr;
    errno = 0;
    const unsigned long value = strtoul(line, &end, 10);
    if (errno != 0 || end == line || value > UINT_MAX) {
        errno = EINVAL;
        return false;
    }
    *uid = static_cast<uid_t>(value);
    return true;
}

/// 双读收敛的目标身份采集：前后 starttime 一致且 UID 匹配才采信，
/// 防止读取间隙目标死亡且 PID 被系统复用导致张冠李戴。
static bool read_target_identity(
        pid_t pid,
        uid_t expected_uid,
        unsigned long long *start_time) {
    unsigned long long before = 0;
    unsigned long long after = 0;
    uid_t observed_uid = 0;
    if (!read_process_start_time(pid, &before) ||
        !read_process_uid(pid, &observed_uid) ||
        !read_process_start_time(pid, &after)) {
        return false;
    }
    if (before != after || observed_uid != expected_uid) {
        errno = ESTALE;
        return false;
    }
    *start_time = after;
    return true;
}

/// 仅当目标身份仍与事务登记一致时才允许 SIGKILL；
/// PID 已被复用的新进程绝不能因旧事务被误杀。
static bool terminate_target_if_same(pid_t pid, unsigned long long expected_start_time) {
    unsigned long long observed_start_time = 0;
    if (!read_process_start_time(pid, &observed_start_time)) {
        // 无法读取身份时仅探测存活：已消失即视为达成，不再补刀。
        const int probe = sys_kill(pid, 0);
        return probe < 0 && errno == ESRCH;
    }
    if (observed_start_time != expected_start_time) {
        // 原目标进程已退出且 PID 被复用，不能终止新进程。
        return true;
    }
    return sys_kill(pid, SIGKILL) == 0 || errno == ESRCH;
}

/// 将 /storage 整体重建为该 user 的 baseline 视图：
/// lazy detach 携带全部子挂载脱离后，以 bind|REC 重挂用户视图。
/// 回滚的"命令成功"不等于"视图已一致"（MNT_DETACH 异步回收），
/// 调用方必须把 dataRestriction 类修改保守地视为污染。
static MountStatus restore_storage_baseline(bool useSdcardFs, const std::string &storage,
                                            const std::string &storageSource,
                                            const std::string &userSource) {
    if (TEMP_FAILURE_RETRY(
            umount2("/storage/"_iobfs.c_str(), UMOUNT_NOFOLLOW | MNT_DETACH)) < 0 &&
        errno != EINVAL && errno != ENOENT) {
        return make_mount_status(-1, "unmount_storage", errno, -1, nullptr,
                                 "/storage/"_iobfs.c_str(), MountPhase::MUTATING_BASELINE);
    }
    if (useSdcardFs) {
        if (TEMP_FAILURE_RETRY(
                mount(storageSource.c_str(), storage.c_str(), nullptr, MS_BIND | MS_REC,
                      nullptr))) {
            return make_mount_status(-1, "remount_storage", errno, -1,
                                     storageSource.c_str(), storage.c_str(),
                                     MountPhase::MUTATING_BASELINE);
        }
        if (TEMP_FAILURE_RETRY(
                mount(nullptr, storage.c_str(), nullptr, MS_REC | MS_SLAVE, nullptr))) {
            return make_mount_status(-1, "remount_storage_slave", errno, -1, nullptr,
                                     storage.c_str(), MountPhase::MUTATING_BASELINE);
        }
        if (TEMP_FAILURE_RETRY(
                mount(userSource.c_str(), "/storage/self"_iobfs.c_str(), nullptr, MS_BIND,
                      nullptr))) {
            return make_mount_status(-1, "mount_storage_self", errno, -1,
                                     userSource.c_str(), "/storage/self"_iobfs.c_str(),
                                     MountPhase::MUTATING_BASELINE);
        }
    } else if (TEMP_FAILURE_RETRY(
            mount(userSource.c_str(), storage.c_str(), nullptr, MS_BIND | MS_REC, nullptr))) {
        return make_mount_status(-1, "remount_storage", errno, -1, userSource.c_str(),
                                 storage.c_str(), MountPhase::MUTATING_BASELINE);
    }
    return make_mount_status(0, "success", 0, -1, nullptr, nullptr,
                             MountPhase::BASELINE_READY);
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
                                     MountPhase::PREPARE);
        }
        if (!wait_zygote(pid)) {
            return make_mount_status(-1, "wait_zygote", errno == 0 ? ETIMEDOUT : errno,
                                     -1, nullptr, nullptr, MountPhase::PREPARE);
        }
        // 事务登记目标身份：starttime 在 PID 复用后必然变化，是全流程防误伤的锚点。
        unsigned long long target_start_time = 0;
        if (!read_target_identity(pid, static_cast<uid_t>(uid), &target_start_time)) {
            return make_mount_status(-1, "target_identity", errno == 0 ? ESRCH : errno,
                                     -1, nullptr, nullptr, MountPhase::PREPARE);
        }
        int sv[2];
        if (socketpair(AF_UNIX, SOCK_DGRAM, 0, sv) != 0) {
            LOGE("%s"_iobfs.c_str(), "Failed to create Unix-domain socket pair"_iobfs.c_str());
            return make_mount_status(-1, "socketpair", errno, -1, nullptr, nullptr,
                                     MountPhase::NAMESPACE_ENTERED);
        }
        int child_pid = sys_fork();
        if (child_pid) {
            // In the parent process.
            if (child_pid == -1) {
                const int saved_errno = errno;
                close(sv[0]);
                close(sv[1]);
                return make_mount_status(-1, "fork", saved_errno, -1, nullptr, nullptr,
                                         MountPhase::NAMESPACE_ENTERED);
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
                    // 超时时若最后进度处于变异阶段，保守判定 namespace 可能已脏。
                    if (phase_may_have_dirty_namespace(last_progress_phase)) {
                        status.namespace_dirty = 1;
                    }
                }
                if (status.namespace_dirty != 0) {
                    // namespace 已污染且无法确认恢复：按安全策略终止目标应用，
                    // 终止前强制复核身份，绝不误杀 PID 复用后的新进程。
                    status.target_terminated =
                            terminate_target_if_same(pid, target_start_time) ? 1 : 0;
                }
                return status;
            }
        }
        int sock = sv[1];
        // In the child process we switch to the new namespace and start mounting,
        // so that all mount operations will not affect parent process.
        if (!switch_mnt_ns(pid)) {
            fail_child(sock, "setns", errno == 0 ? EPERM : errno, -1, nullptr, nullptr,
                       MountPhase::NAMESPACE_ENTERED);
        }
        // setns 后二次确认身份：采集与进入之间目标若死亡且 PID 被复用，
        // 继续操作会把挂载打进无关进程的 namespace。
        unsigned long long confirmed_start_time = 0;
        if (!read_target_identity(pid, static_cast<uid_t>(uid), &confirmed_start_time) ||
            confirmed_start_time != target_start_time) {
            fail_child(sock, "target_identity", errno == 0 ? ESRCH : errno, -1,
                       nullptr, nullptr, MountPhase::NAMESPACE_ENTERED);
        }
#ifdef REMOUNT_STORAGE
        write_mount_progress(sock, MountPhase::NAMESPACE_ENTERED);
        write_mount_progress(sock, MountPhase::MUTATING_BASELINE);
        const auto baseline = restore_storage_baseline(
                useSdcardFs, storage, storageSource, userSource);
        if (baseline.ok != 0) {
            // 首次恢复失败时再尝试一次，尽量避免目标 namespace 留在无 /storage 状态。
            const auto recovery = restore_storage_baseline(
                    useSdcardFs, storage, storageSource, userSource);
            if (recovery.ok != 0) {
                LOGE("Failed to restore storage baseline after %s: %s"_iobfs.c_str(),
                     baseline.stage, strerror(recovery.err));
                fail_child(sock, "baseline_recovery_failed", recovery.err, recovery.index,
                           recovery.source, recovery.target, MountPhase::MUTATING_BASELINE,
                           true);
            }
            write_mount_progress(sock, MountPhase::BASELINE_READY);
            fail_child(sock, baseline.stage, baseline.err, baseline.index,
                       baseline.source, baseline.target, MountPhase::MUTATING_BASELINE);
        }
        write_mount_progress(sock, MountPhase::BASELINE_READY);

        // 变异阶段副作用跟踪：回滚按标记撤销已产生的效果；
        // dataRestrictionModified 一旦置位即保守判定 namespace 已脏
        // （MNT_DETACH 异步回收，命令成功不代表视图一致）。
        bool dataBypassMounted = false;
        bool obbBypassMounted = false;
        bool dataRestrictionModified = false;

        const std::string androidDataFuseDir = StringPrintf(
                "/mnt/user/%d/emulated/%d/Android/data"_iobfs.c_str(), user_id, user_id);
        const std::string androidObbFuseDir = StringPrintf(
                "/mnt/user/%d/emulated/%d/Android/obb"_iobfs.c_str(), user_id, user_id);

        const auto rollback_and_fail = [&](const char *stage, int err, int index = -1,
                                           const char *source = nullptr,
                                           const char *target = nullptr) -> void {
            write_mount_progress(sock, MountPhase::ROLLING_BACK);
            if (dataBypassMounted) {
                TEMP_FAILURE_RETRY(
                        umount2(androidDataFuseDir.c_str(), UMOUNT_NOFOLLOW | MNT_DETACH));
            }
            if (obbBypassMounted) {
                TEMP_FAILURE_RETRY(
                        umount2(androidObbFuseDir.c_str(), UMOUNT_NOFOLLOW | MNT_DETACH));
            }
            const auto rollback = restore_storage_baseline(
                    useSdcardFs, storage, storageSource, userSource);
            if (rollback.ok != 0) {
                LOGE("Rollback failed after %s at %s: %s"_iobfs.c_str(), stage,
                     rollback.stage, strerror(rollback.err));
            }
            const bool namespace_dirty = dataRestrictionModified || rollback.ok != 0;
            if (namespace_dirty) {
                fail_child(sock, "namespace_rollback_failed",
                           rollback.ok != 0 ? rollback.err : err,
                           index, source, target, MountPhase::ROLLING_BACK, true);
            }
            write_mount_progress(sock, MountPhase::BASELINE_READY);
            fail_child(sock, stage, err, index, source, target, MountPhase::APPLYING_RULES);
        };

        // some little tricks on the system with FUSE enabled
        if (!useSdcardFs) {
            // unmount /Android/data to intercept filesystem operations in app-specific dir.
            if (unmountDataRestriction) {
                if (TEMP_FAILURE_RETRY(
                        umount2(androidDataFuseDir.c_str(), UMOUNT_NOFOLLOW)) == 0) {
                    dataRestrictionModified = true;
                } else if (errno != EINVAL && errno != ENOENT) {
                    const int error = errno;
                    LOGE("Failed to unmount fuseDataDir: %s"_iobfs.c_str(), strerror(errno));
                    rollback_and_fail("unmount_data_restriction_fuse", error, -1,
                                      nullptr, androidDataFuseDir.c_str());
                }
                const std::string androidDataDir = StringPrintf(
                        "/storage/emulated/%d/Android/data"_iobfs.c_str(), user_id);
                if (TEMP_FAILURE_RETRY(
                        umount2(androidDataDir.c_str(), UMOUNT_NOFOLLOW)) == 0) {
                    dataRestrictionModified = true;
                } else if (errno != EINVAL && errno != ENOENT) {
                    const int error = errno;
                    LOGE("Failed to unmount androidDataDir: %s"_iobfs.c_str(), strerror(errno));
                    rollback_and_fail("unmount_data_restriction_storage", error, -1,
                                      nullptr, androidDataDir.c_str());
                }
            }
            if (fuseBypass) {
                const std::string androidDataSourceDir = StringPrintf(
                        "/data/media/%d/Android/data"_iobfs.c_str(), user_id);
                if (TEMP_FAILURE_RETRY(
                        mount(androidDataSourceDir.c_str(), androidDataFuseDir.c_str(), nullptr,
                              MS_BIND | MS_REC, nullptr))) {
                    LOGE("Failed to mount %s to %s: %s"_iobfs.c_str(),
                         androidDataSourceDir.c_str(), androidDataFuseDir.c_str(), strerror(errno));
                    rollback_and_fail("fuse_bypass_data_source", errno, -1,
                                      androidDataSourceDir.c_str(),
                                      androidDataFuseDir.c_str());
                }
                dataBypassMounted = true;
                const std::string androidObbSourceDir = StringPrintf(
                        "/data/media/%d/Android/obb"_iobfs.c_str(), user_id);
                if (TEMP_FAILURE_RETRY(
                        mount(androidObbSourceDir.c_str(), androidObbFuseDir.c_str(), nullptr,
                              MS_BIND | MS_REC, nullptr))) {
                    LOGE("Failed to mount %s to %s: %s"_iobfs.c_str(),
                         androidObbSourceDir.c_str(), androidObbFuseDir.c_str(), strerror(errno));
                    rollback_and_fail("fuse_bypass_obb_source", errno, -1,
                                      androidObbSourceDir.c_str(), androidObbFuseDir.c_str());
                }
                obbBypassMounted = true;

                const std::string androidDataDir = StringPrintf(
                        "/storage/emulated/%d/Android/data"_iobfs.c_str(), user_id);
                if (TEMP_FAILURE_RETRY(
                        mount(androidDataFuseDir.c_str(), androidDataDir.c_str(), nullptr,
                              MS_BIND | MS_REC, nullptr))) {
                    LOGE("Failed to mount %s to %s: %s"_iobfs.c_str(),
                         androidDataFuseDir.c_str(), androidDataDir.c_str(), strerror(errno));
                    rollback_and_fail("fuse_bypass_data_target", errno, -1,
                                      androidDataFuseDir.c_str(), androidDataDir.c_str());
                }
                const std::string androidObbDir = StringPrintf(
                        "/storage/emulated/%d/Android/obb"_iobfs.c_str(), user_id);
                if (TEMP_FAILURE_RETRY(
                        mount(androidObbFuseDir.c_str(), androidObbDir.c_str(), nullptr,
                              MS_BIND | MS_REC, nullptr))) {
                    LOGE("Failed to mount %s to %s: %s"_iobfs.c_str(),
                         androidObbFuseDir.c_str(), androidObbDir.c_str(), strerror(errno));
                    rollback_and_fail("fuse_bypass_obb_target", errno, -1,
                                      androidObbFuseDir.c_str(), androidObbDir.c_str());
                }
            }
        }
#endif // REMOUNT_STORAGE
        // Mount as user wish.
        for (int i = 0, length = env->GetArrayLength(jsources); i < length; i++) {
            write_mount_progress_with_index(sock, MountPhase::APPLYING_RULES, i);
            auto jsource = (jstring) env->GetObjectArrayElement(jsources, i);
            if (jsource == nullptr) {
                rollback_and_fail("invalid_source", EINVAL, i);
            }
            const char *source = env->GetStringUTFChars(jsource, nullptr);
            if (source == nullptr) {
                rollback_and_fail("invalid_source", EINVAL, i);
            }
            auto jtarget = (jstring) env->GetObjectArrayElement(jtargets, i);
            if (jtarget == nullptr) {
                rollback_and_fail("invalid_target", EINVAL, i, source);
            }
            const char *target = env->GetStringUTFChars(jtarget, nullptr);
            if (target == nullptr) {
                rollback_and_fail("invalid_target", EINVAL, i, source);
            }
            if (!is_storage_path(source)) {
                rollback_and_fail("invalid_source", EINVAL, i, source, target);
            }
            if (!is_storage_path(target)) {
                rollback_and_fail("invalid_target", EINVAL, i, source, target);
            }
            const std::string mnt_source = (useSdcardFs ? storageSource : userSource) +
                                           std::string(source).substr(storage.length(),
                                                                      strlen(source));
            if (TEMP_FAILURE_RETRY(
                    mount(mnt_source.c_str(), target, nullptr, MS_BIND | MS_REC, nullptr))) {
                LOGE("Failed to mount %s to %s: %s"_iobfs.c_str(),
                     mnt_source.c_str(), target, strerror(errno));
                rollback_and_fail("mount_rule", errno, i, mnt_source.c_str(), target);
            }
            env->ReleaseStringUTFChars(jsource, source);
            env->ReleaseStringUTFChars(jtarget, target);
            env->DeleteLocalRef(jsource);
            env->DeleteLocalRef(jtarget);
        }
        write_mount_status(sock, make_mount_status(0, "success", 0, -1, nullptr, nullptr,
                                                   MountPhase::COMPLETED));
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
