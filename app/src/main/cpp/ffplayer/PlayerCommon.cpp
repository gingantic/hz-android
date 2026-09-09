#include "PlayerCommon.h"

JavaVM* g_jvm = nullptr;

JNIEnv* getJniEnv(bool* outAttached) {
    if (!g_jvm) {
        if (outAttached) *outAttached = false;
        return nullptr;
    }
    JNIEnv* env = nullptr;
    jint res = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            if (outAttached) *outAttached = false;
            return nullptr;
        }
        if (outAttached) *outAttached = true;
    } else {
        if (outAttached) *outAttached = false;
    }
    return env;
}
