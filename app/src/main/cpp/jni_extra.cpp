#include "jni_extra.h"
#include "obfuscate.h"
#include "FileUtils.h"
#include "Mount.h"
#include "ProcInfo.h"

#ifdef DEBUG

#include "logging.h"

#endif

jint JNI_OnLoad_Extra(JNIEnv *env, jclass clazz) {
    if ((clazz = env->FindClass(AY_OBFUSCATE("me/gm/cleaner/core/common/RuntimeFileUtils"))) == nullptr) { // "me/gm/cleaner/core/common/RuntimeFileUtils"
        return JNI_ERR;
    }
    auto a = AY_OBFUSCATE("a"); // "a" - short method name
    auto b = AY_OBFUSCATE("b"); // "b" - short method name
    auto c = AY_OBFUSCATE("c"); // "c" - short method name
    JNINativeMethod methods[] = {
            {b, AY_OBFUSCATE("(Ljava/lang/String;)I"),                     (void *) FileUtils::rm_dir}, // "(Ljava/lang/String;)I"
            {a, AY_OBFUSCATE( // "([Ljava/lang/String;I)Z"
                        "([Ljava/lang/String;I)Z"),                        (void *) FileUtils::auto_prepare_dirs},
            {a, AY_OBFUSCATE( // "(Ljava/lang/String;IZ)V"
                        "(Ljava/lang/String;IZ)V"),                        (void *) FileUtils::switch_owner},
//            {a, AY_OBFUSCATE(
//                        "(I)Ljava/lang/String;"),                        (void *) ProcInfo::read_cmdline},
            {b, AY_OBFUSCATE( // "(I)I"
                        "(I)I"),                                           (void *) ProcInfo::read_uid},
            {a, AY_OBFUSCATE( // "(I[Ljava/lang/String;)[I"
                        "(I[Ljava/lang/String;)[I"),                       (void *) ProcInfo::check_mounts},
            {a, AY_OBFUSCATE( // "(IIZZ[Ljava/lang/String;[Ljava/lang/String;)Z"
                        "(IIZZ[Ljava/lang/String;[Ljava/lang/String;)Z"), (void *) Mount::bind_mount},
            {c, AY_OBFUSCATE( // "(IIZZ[Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/String;"
                        "(IIZZ[Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/String;"), (void *) Mount::bind_mount_status},
    };
    return env->RegisterNatives(clazz, methods, NELEM(methods));
}
