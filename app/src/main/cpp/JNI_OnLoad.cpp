#include <jni.h>
#include "genuine_extra.h"

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *jvm, void *reserved) {
    JNIEnv *env = nullptr;
    if (jvm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    if (JNI_OnLoad_Extra(env, nullptr) < 0) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
