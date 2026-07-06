#include <cstring>
#include <dlfcn.h>
#include <jni.h>
#include "bpf_hook.h"
#include "logging.h"
#include "obfuscate.h"

static std::string xhook_init() {
    void *handle = dlopen("libfuse_jni.so", RTLD_NOLOAD);
    if (handle == nullptr) {
        handle = dlopen("libfuse_jni.so", RTLD_LAZY);
    }
    if (handle != nullptr) {
        return bpf_hook::Hook(handle);
    }
    return "{\"fuseLibraryLoaded\":false,\"fuseLibraryName\":\"libfuse_jni.so\","
           "\"xhookRefreshCalled\":false,\"lastError\":\"dlopen libfuse_jni.so failed\"}";
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
jstring xhook_init_jni(JNIEnv *env, jclass clazz) {
    auto status = xhook_init();
    return env->NewStringUTF(status.c_str());
}

extern "C" [[gnu::visibility("default")]] [[gnu::used]]
jint JNI_OnLoad(JavaVM *jvm, void *v __unused) {
    JNIEnv *env;
    if (jvm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass clazz = env->FindClass(AY_OBFUSCATE("me/gm/cleaner/runtime/mediaprovider/hook/InlineHookConfig")); // "me/gm/cleaner/runtime/mediaprovider/hook/InlineHookConfig"
    if (clazz == nullptr) {
        return JNI_ERR;
    }
    auto a = AY_OBFUSCATE("a"); // "a" - short method name
    auto init = AY_OBFUSCATE("init"); // "init"
    JNINativeMethod methods[] = {
            {a, AY_OBFUSCATE("([Ljava/lang/String;)V"), (void *) bpf_hook::setMountPoint}, // "([Ljava/lang/String;)V"
            {a, AY_OBFUSCATE( // "(Z)V"
                        "(Z)V"),                        (void *) bpf_hook::setRecordExternalAppSpecificStorage},
            {init, AY_OBFUSCATE("()Ljava/lang/String;"), (void *) xhook_init_jni},
    };
    if (env->RegisterNatives(clazz, methods, sizeof(methods) / sizeof(methods[0]))) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
