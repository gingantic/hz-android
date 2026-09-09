#pragma once

#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <aaudio/AAudio.h>

#ifndef EGL_OPENGL_ES3_BIT
#define EGL_OPENGL_ES3_BIT 0x00000040
#endif

extern "C" {
#ifdef __cplusplus
#define __STDC_CONSTANT_MACROS
#ifdef _STDINT_H
#undef _STDINT_H
#endif
#include <stdint.h>
#endif
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavcodec/bsf.h>
#include <libavutil/avutil.h>
#include <libavutil/opt.h>
#include <libavutil/time.h>
#include <libavutil/imgutils.h>
#include <libavutil/channel_layout.h>
#include <libavutil/display.h>
#include <libswscale/swscale.h>
#include <libswresample/swresample.h>
#include <libavfilter/avfilter.h>
#include <libavfilter/buffersink.h>
#include <libavfilter/buffersrc.h>
}

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>

#include <thread>
#include <atomic>
#include <mutex>
#include <condition_variable>
#include <chrono>
#include <vector>
#include <deque>
#include <string>
#include <unordered_set>
#include <algorithm>
#include <cstring>
#include <cinttypes>

#define LOG_TAG "FfmpegPlayerNative"
#define LOGD(...) ((void)__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__))
#define LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__))
#define LOGW(...) ((void)__android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__))
#define LOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))

extern JavaVM* g_jvm;

JNIEnv* getJniEnv(bool* outAttached = nullptr);

// AAudio output is intentionally normalized to mono or stereo. FFmpeg's
// libswresample uses the decoded input AVChannelLayout to fold multichannel
// audio (including the center/dialogue channel) into this output layout.
inline int nativeOutputChannelCount(int inputChannels) {
    return inputChannels == 1 ? 1 : 2;
}

// ─── Playback States (matching Kotlin PlayerState) ───────────────────────────
enum NativePlayerState {
    STATE_IDLE = 0,
    STATE_BUFFERING = 1,
    STATE_READY = 2,
    STATE_ENDED = 3,
    STATE_ERROR = 4
};

inline int64_t getMonotonicTimeMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}
