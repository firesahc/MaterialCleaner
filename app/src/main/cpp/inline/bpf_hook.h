#pragma once

#include <jni.h>
#include <set>
#include <string>

namespace bpf_hook {

    extern std::set<std::string> mountPoint;

    extern bool recordExternalAppSpecificStorage;

    void Hook(void *handle);

    void setMountPoint(JNIEnv *env, jclass clazz, jobjectArray value);

    void setRecordExternalAppSpecificStorage(JNIEnv *env, jclass clazz, jboolean value);
}  // namespace bpf_hook
