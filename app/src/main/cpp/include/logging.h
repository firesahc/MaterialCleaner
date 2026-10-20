#ifndef _LOGGING_H
#define _LOGGING_H

#include <errno.h>
#include "android/log.h"

#ifndef LOG_TAG
#define LOG_TAG "me.gm.cleaner"
#endif

/// 日志级别约定（改前先读这里，避免后人误判）：
/// - LOGE/LOGW/LOGI/LOGV：所有构建（release 在内）都会编译进包。
///   错误路径的日志必须走这些级别，禁止再包在 #ifdef DEBUG 内：
///   诊断包以 logcat 为证据源，release 包一旦丢弃错误日志，取证就是零证据。
/// - LOGD：仅在定义了 DEBUG 宏的构建中编译生效。
///   当前 Gradle/CMake 构建并未定义 DEBUG（CMakeLists 只按 CMAKE_BUILD_TYPE 切换优化参数），
///   因此 LOGD 在所有构建下都是空宏；需要 D 级证据请改用 LOGI/LOGE，不要依赖 LOGD。
#ifdef DEBUG
#define LOGD(...)  __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#else
#define LOGD(...)
#endif
#define LOGV(...)  __android_log_print(ANDROID_LOG_VERBOSE, LOG_TAG, __VA_ARGS__)
#define LOGI(...)  __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...)  __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...)  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#endif // _LOGGING_H
