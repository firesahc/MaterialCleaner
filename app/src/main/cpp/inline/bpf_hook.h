#pragma once

#include <jni.h>
#include <string>

namespace bpf_hook {

    std::string Hook(void *handle, bool fuseLibraryMapped);

    void setMountPoint(JNIEnv *env, jclass clazz, jobjectArray value);

    void setRecordExternalAppSpecificStorage(JNIEnv *env, jclass clazz, jboolean value);

    /** 单次调用原子应用策略两维度（挂载点集合 + 记录偏好）。 */
    void commitPolicy(JNIEnv *env, jclass clazz, jobjectArray value, jboolean record);
}  // namespace bpf_hook
