/*
 * FfmpegDecoder.cpp — JNI bridge for the FFmpeg software decode fallback
 * (libffcodec.so).
 *
 * Audio path adapted from media3's decoder_ffmpeg ffmpeg_jni.cc (Apache 2.0),
 * repackaged for com.rhnxdev.hzplayer and extended with a video decode path:
 * decode keeps the AVFrame native-side (handle in
 * VideoDecoderOutputBuffer.decoderPrivate); render converts with swscale to
 * RGBA and blits into the Surface through ANativeWindow.
 */
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <stdlib.h>

extern "C" {
#ifdef __cplusplus
#define __STDC_CONSTANT_MACROS
#ifdef _STDINT_H
#undef _STDINT_H
#endif
#include <stdint.h>
#endif
#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>
}

#define LOG_TAG "ffcodec_jni"
#define LOGE(...) \
  ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))
#define LOGD(...) \
  ((void)__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__))

#define JNI_PKG(NAME) \
  Java_com_rhnxdev_hzplayer_data_datasource_player_ffmpeg_##NAME

#define LIBRARY_FUNC(RETURN_TYPE, NAME, ...)                            \
  extern "C" {                                                          \
  JNIEXPORT RETURN_TYPE JNI_PKG(FfmpegLibrary_##NAME)(                  \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__);                        \
  }                                                                     \
  JNIEXPORT RETURN_TYPE JNI_PKG(FfmpegLibrary_##NAME)(                  \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__)

#define AUDIO_DECODER_FUNC(RETURN_TYPE, NAME, ...)                      \
  extern "C" {                                                          \
  JNIEXPORT RETURN_TYPE JNI_PKG(FfmpegAudioDecoder_##NAME)(             \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__);                        \
  }                                                                     \
  JNIEXPORT RETURN_TYPE JNI_PKG(FfmpegAudioDecoder_##NAME)(             \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__)

#define VIDEO_DECODER_FUNC(RETURN_TYPE, NAME, ...)                      \
  extern "C" {                                                          \
  JNIEXPORT RETURN_TYPE JNI_PKG(FfmpegVideoDecoder_##NAME)(             \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__);                        \
  }                                                                     \
  JNIEXPORT RETURN_TYPE JNI_PKG(FfmpegVideoDecoder_##NAME)(             \
      JNIEnv* env, jobject thiz, ##__VA_ARGS__)

#define ERROR_STRING_BUFFER_LENGTH 256

// Output format corresponding to AudioFormat.ENCODING_PCM_16BIT.
static const AVSampleFormat OUTPUT_FORMAT_PCM_16BIT = AV_SAMPLE_FMT_S16;
// Output format corresponding to AudioFormat.ENCODING_PCM_FLOAT.
static const AVSampleFormat OUTPUT_FORMAT_PCM_FLOAT = AV_SAMPLE_FMT_FLT;

// LINT.IfChange
static const int AUDIO_DECODER_ERROR_INVALID_DATA = -1;
static const int AUDIO_DECODER_ERROR_OTHER = -2;
// LINT.ThenChange(../java/.../ffmpeg/FfmpegAudioDecoder.java)

// LINT.IfChange(video_decode_result)
static const int VIDEO_DECODER_SUCCESS = 0;
static const int VIDEO_DECODER_ERROR_INVALID_DATA = -1;
static const int VIDEO_DECODER_ERROR_OTHER = -2;
// LINT.ThenChange(../java/.../ffmpeg/FfmpegVideoDecoder.java)

static jmethodID growOutputBufferMethod;
// VideoDecoderOutputBuffer accessors, resolved once in JNI_OnLoad.
static jmethodID initForPrivateFrameMethod;
static jfieldID decoderPrivateField;
static jfieldID timeUsField;

/**
 * Returns the AVCodec with the specified name, or NULL if it is not available.
 */
const AVCodec* getCodecByName(JNIEnv* env, jstring codecName);

/**
 * Allocates and opens a new AVCodecContext for the specified audio codec,
 * passing the provided extraData as initialization data for the decoder if it
 * is non-NULL. Returns the created context.
 */
AVCodecContext* createContext(JNIEnv* env, const AVCodec* codec,
                              jbyteArray extraData, jboolean outputFloat,
                              jint rawSampleRate, jint rawChannelCount);

struct GrowOutputBufferCallback {
  uint8_t* operator()(int requiredSize) const;

  JNIEnv* env;
  jobject thiz;
  jobject decoderOutputBuffer;
};

/**
 * Decodes the packet into the output buffer, returning the number of bytes
 * written, or a negative AUDIO_DECODER_ERROR constant value in the case of an
 * error.
 */
int decodePacket(AVCodecContext* context, AVPacket* packet,
                 uint8_t* outputBuffer, int outputSize,
                 GrowOutputBufferCallback growBuffer);

/**
 * Transforms ffmpeg AVERROR into a negative AUDIO_DECODER_ERROR constant
 * value.
 */
int transformError(int errorNumber);

/**
 * Outputs a log message describing the avcodec error number.
 */
void logError(const char* functionName, int errorNumber);

/**
 * Releases the specified context.
 */
void releaseContext(AVCodecContext* context);

/** Native side of the FFmpeg video decoder. */
struct VideoContext {
  // Owned by the decode thread (SimpleDecoder's internal thread).
  AVCodecContext* codecContext;
  // Owned by the playback thread (renderFrame only).
  SwsContext* swsContext;
  ANativeWindow* cachedWindow = nullptr;
  jobject cachedSurface = nullptr;
  AVPacket* decodePacket = nullptr;
};

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
  JNIEnv* env;
  if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
    LOGE("JNI_OnLoad: GetEnv failed");
    return -1;
  }
  jclass audioDecoderClass = env->FindClass(
      "com/rhnxdev/hzplayer/data/datasource/player/ffmpeg/FfmpegAudioDecoder");
  if (!audioDecoderClass) {
    LOGE("JNI_OnLoad: FfmpegAudioDecoder FindClass failed");
    return -1;
  }
  growOutputBufferMethod =
      env->GetMethodID(audioDecoderClass, "growOutputBuffer",
                       "(Landroidx/media3/decoder/"
                       "SimpleDecoderOutputBuffer;I)Ljava/nio/ByteBuffer;");
  if (!growOutputBufferMethod) {
    LOGE("JNI_OnLoad: growOutputBuffer GetMethodID failed");
    return -1;
  }
  jclass videoOutputBufferClass =
      env->FindClass("androidx/media3/decoder/VideoDecoderOutputBuffer");
  if (!videoOutputBufferClass) {
    LOGE("JNI_OnLoad: VideoDecoderOutputBuffer FindClass failed");
    return -1;
  }
  initForPrivateFrameMethod =
      env->GetMethodID(videoOutputBufferClass, "initForPrivateFrame", "(II)V");
  decoderPrivateField =
      env->GetFieldID(videoOutputBufferClass, "decoderPrivate", "J");
  timeUsField = env->GetFieldID(videoOutputBufferClass, "timeUs", "J");
  if (!initForPrivateFrameMethod || !decoderPrivateField || !timeUsField) {
    LOGE("JNI_OnLoad: VideoDecoderOutputBuffer member lookup failed");
    return -1;
  }
  return JNI_VERSION_1_6;
}

LIBRARY_FUNC(jstring, ffmpegGetVersion) {
  return env->NewStringUTF(LIBAVCODEC_IDENT);
}

LIBRARY_FUNC(jint, ffmpegGetInputBufferPaddingSize) {
  return (jint)AV_INPUT_BUFFER_PADDING_SIZE;
}

LIBRARY_FUNC(jboolean, ffmpegHasDecoder, jstring codecName) {
  return getCodecByName(env, codecName) != NULL;
}

// ─── Audio ──────────────────────────────────────────────────────────────────

AUDIO_DECODER_FUNC(jlong, ffmpegInitialize, jstring codecName,
                   jbyteArray extraData, jboolean outputFloat,
                   jint rawSampleRate, jint rawChannelCount) {
  const AVCodec* codec = getCodecByName(env, codecName);
  if (!codec) {
    LOGE("Codec not found.");
    return 0L;
  }
  return (jlong)createContext(env, codec, extraData, outputFloat, rawSampleRate,
                              rawChannelCount);
}

AUDIO_DECODER_FUNC(jint, ffmpegDecode, jlong context, jobject inputData,
                   jint inputSize, jobject decoderOutputBuffer,
                   jobject outputData, jint outputSize) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return -1;
  }
  if (!inputData || !decoderOutputBuffer || !outputData) {
    LOGE("Input and output buffers must be non-NULL.");
    return -1;
  }
  if (inputSize < 0) {
    LOGE("Invalid input buffer size: %d.", inputSize);
    return -1;
  }
  if (outputSize < 0) {
    LOGE("Invalid output buffer length: %d", outputSize);
    return -1;
  }
  uint8_t* inputBuffer = (uint8_t*)env->GetDirectBufferAddress(inputData);
  uint8_t* outputBuffer = (uint8_t*)env->GetDirectBufferAddress(outputData);
  AVPacket* packet = av_packet_alloc();
  if (!packet) {
    LOGE("Failed to allocate packet.");
    return -1;
  }
  packet->data = inputBuffer;
  packet->size = inputSize;
  const int ret =
      decodePacket((AVCodecContext*)context, packet, outputBuffer, outputSize,
                   GrowOutputBufferCallback{env, thiz, decoderOutputBuffer});
  av_packet_free(&packet);
  return ret;
}

uint8_t* GrowOutputBufferCallback::operator()(int requiredSize) const {
  jobject newOutputData = env->CallObjectMethod(
      thiz, growOutputBufferMethod, decoderOutputBuffer, requiredSize);
  if (env->ExceptionCheck()) {
    LOGE("growOutputBuffer() failed");
    env->ExceptionDescribe();
    return nullptr;
  }
  return static_cast<uint8_t*>(env->GetDirectBufferAddress(newOutputData));
}

AUDIO_DECODER_FUNC(jint, ffmpegGetChannelCount, jlong context) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return -1;
  }
  return ((AVCodecContext*)context)->ch_layout.nb_channels;
}

AUDIO_DECODER_FUNC(jint, ffmpegGetSampleRate, jlong context) {
  if (!context) {
    LOGE("Context must be non-NULL.");
    return -1;
  }
  return ((AVCodecContext*)context)->sample_rate;
}

AUDIO_DECODER_FUNC(jlong, ffmpegReset, jlong jContext, jbyteArray extraData) {
  AVCodecContext* context = (AVCodecContext*)jContext;
  if (!context) {
    LOGE("Tried to reset without a context.");
    return 0L;
  }

  AVCodecID codecId = context->codec_id;
  if (codecId == AV_CODEC_ID_TRUEHD) {
    jboolean outputFloat =
        (jboolean)(context->request_sample_fmt == OUTPUT_FORMAT_PCM_FLOAT);
    // Release and recreate the context if the codec is TrueHD.
    // TODO: Figure out why flushing doesn't work for this codec.
    releaseContext(context);
    const AVCodec* codec = avcodec_find_decoder(codecId);
    if (!codec) {
      LOGE("Unexpected error finding codec %d.", codecId);
      return 0L;
    }
    return (jlong)createContext(env, codec, extraData, outputFloat,
                                /* rawSampleRate= */ -1,
                                /* rawChannelCount= */ -1);
  }

  avcodec_flush_buffers(context);
  return (jlong)context;
}

AUDIO_DECODER_FUNC(void, ffmpegRelease, jlong context) {
  if (context) {
    releaseContext((AVCodecContext*)context);
  }
}

// ─── Video ──────────────────────────────────────────────────────────────────

VIDEO_DECODER_FUNC(jlong, ffmpegVideoInitialize, jstring codecName,
                   jbyteArray extraData, jint threads) {
  const AVCodec* codec = getCodecByName(env, codecName);
  if (!codec) {
    LOGE("Video codec not found.");
    return 0L;
  }
  AVCodecContext* context = avcodec_alloc_context3(codec);
  if (!context) {
    LOGE("Failed to allocate video context.");
    return 0L;
  }
  if (extraData) {
    jsize size = env->GetArrayLength(extraData);
    context->extradata_size = size;
    // av_mallocz: FFmpeg requires the padding region after extradata to be zeroed.
    context->extradata =
        (uint8_t*)av_mallocz(size + AV_INPUT_BUFFER_PADDING_SIZE);
    if (!context->extradata) {
      LOGE("Failed to allocate video extradata.");
      releaseContext(context);
      return 0L;
    }
    env->GetByteArrayRegion(extraData, 0, size, (jbyte*)context->extradata);
  }
  // 0 lets FFmpeg pick frame/slice threading appropriate for the codec.
  context->thread_count = threads;
  context->err_recognition = AV_EF_IGNORE_ERR;
  int result = avcodec_open2(context, codec, NULL);
  if (result < 0) {
    logError("avcodec_open2 (video)", result);
    releaseContext(context);
    return 0L;
  }
  VideoContext* videoContext = new VideoContext{context, nullptr, nullptr, nullptr, av_packet_alloc()};
  return (jlong)videoContext;
}

VIDEO_DECODER_FUNC(jint, ffmpegVideoDecode, jlong jContext, jobject inputData,
                   jint inputSize, jlong inputTimeUs, jobject outputBuffer) {
  VideoContext* videoContext = (VideoContext*)jContext;
  if (!videoContext || !inputData || !outputBuffer) {
    LOGE("Video decode: context and buffers must be non-NULL.");
    return VIDEO_DECODER_ERROR_OTHER;
  }
  if (inputSize < 0) {
    LOGE("Video decode: invalid input size %d.", inputSize);
    return VIDEO_DECODER_ERROR_OTHER;
  }
  AVCodecContext* context = videoContext->codecContext;
  uint8_t* inputBuffer = (uint8_t*)env->GetDirectBufferAddress(inputData);
  AVPacket* packet = videoContext->decodePacket;
  if (!packet) {
    packet = av_packet_alloc();
    videoContext->decodePacket = packet;
  }
  av_packet_unref(packet);
  packet->data = inputBuffer;
  packet->size = inputSize;
  // Microsecond timestamps ride through the decoder opaquely; reordered
  // output timestamps come back on the frame.
  packet->pts = inputTimeUs;

  int sendResult = avcodec_send_packet(context, packet);
  AVFrame* frame = av_frame_alloc();
  if (!frame) {
    LOGE("Video decode: failed to allocate frame.");
    return VIDEO_DECODER_ERROR_OTHER;
  }
  int receiveResult = avcodec_receive_frame(context, frame);
  if (sendResult == AVERROR(EAGAIN) && receiveResult == 0) {
    // Decoder was full; a frame has been drained, so the packet fits now.
    sendResult = avcodec_send_packet(context, packet);
  }
  packet->data = nullptr;
  packet->size = 0;

  if (sendResult < 0 && sendResult != AVERROR(EAGAIN)) {
    av_frame_free(&frame);
    logError("avcodec_send_packet (video)", sendResult);
    return sendResult == AVERROR_INVALIDDATA ? VIDEO_DECODER_ERROR_INVALID_DATA
                                             : VIDEO_DECODER_ERROR_OTHER;
  }
  if (receiveResult == AVERROR(EAGAIN)) {
    // Packet consumed, no frame ready yet (decoder delay).
    av_frame_free(&frame);
    return VIDEO_DECODER_SUCCESS;
  }
  if (receiveResult < 0) {
    av_frame_free(&frame);
    logError("avcodec_receive_frame (video)", receiveResult);
    return receiveResult == AVERROR_INVALIDDATA
               ? VIDEO_DECODER_ERROR_INVALID_DATA
               : VIDEO_DECODER_ERROR_OTHER;
  }

  jlong frameTimeUs = (frame->best_effort_timestamp != AV_NOPTS_VALUE)
                          ? (jlong)frame->best_effort_timestamp
                          : inputTimeUs;
  env->SetLongField(outputBuffer, timeUsField, frameTimeUs);
  env->SetLongField(outputBuffer, decoderPrivateField, (jlong)frame);
  env->CallVoidMethod(outputBuffer, initForPrivateFrameMethod, frame->width,
                      frame->height);
  if (env->ExceptionCheck()) {
    LOGE("initForPrivateFrame() failed");
    env->ExceptionDescribe();
    env->SetLongField(outputBuffer, decoderPrivateField, (jlong)0);
    av_frame_free(&frame);
    return VIDEO_DECODER_ERROR_OTHER;
  }
  return VIDEO_DECODER_SUCCESS;
}

VIDEO_DECODER_FUNC(jint, ffmpegVideoRenderFrame, jlong jContext,
                   jobject surface, jlong frameHandle) {
  VideoContext* videoContext = (VideoContext*)jContext;
  AVFrame* frame = (AVFrame*)frameHandle;
  if (!videoContext || !frame || !surface) {
    LOGE("Video render: context, frame and surface must be non-NULL.");
    return VIDEO_DECODER_ERROR_OTHER;
  }

  ANativeWindow* window = nullptr;
  if (videoContext->cachedSurface && env->IsSameObject(videoContext->cachedSurface, surface) && videoContext->cachedWindow) {
    window = videoContext->cachedWindow;
  } else {
    if (videoContext->cachedWindow) {
      ANativeWindow_release(videoContext->cachedWindow);
      videoContext->cachedWindow = nullptr;
    }
    if (videoContext->cachedSurface) {
      env->DeleteGlobalRef(videoContext->cachedSurface);
      videoContext->cachedSurface = nullptr;
    }
    window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
      LOGE("Video render: failed to acquire native window.");
      return VIDEO_DECODER_ERROR_OTHER;
    }
    videoContext->cachedWindow = window;
    videoContext->cachedSurface = env->NewGlobalRef(surface);
  }

  ANativeWindow_setBuffersGeometry(window, frame->width, frame->height,
                                   WINDOW_FORMAT_RGBA_8888);
  ANativeWindow_Buffer windowBuffer;
  if (ANativeWindow_lock(window, &windowBuffer, nullptr) < 0) {
    LOGE("Video render: failed to lock native window.");
    return VIDEO_DECODER_ERROR_OTHER;
  }
  videoContext->swsContext = sws_getCachedContext(
      videoContext->swsContext, frame->width, frame->height,
      (AVPixelFormat)frame->format, windowBuffer.width, windowBuffer.height,
      AV_PIX_FMT_RGBA, SWS_BILINEAR, nullptr, nullptr, nullptr);
  if (!videoContext->swsContext) {
    LOGE("Video render: failed to create swscale context for pix_fmt %d.",
         frame->format);
    ANativeWindow_unlockAndPost(window);
    return VIDEO_DECODER_ERROR_OTHER;
  }
  // Pick YUV→RGB coefficients from the frame's signaled colorspace; HD pro
  // codec files are BT.709, SD falls back to BT.601.
  const int* coefficients =
      sws_getCoefficients(frame->colorspace == AVCOL_SPC_BT709
                              ? SWS_CS_ITU709
                              : (frame->colorspace == AVCOL_SPC_BT2020_NCL
                                     ? SWS_CS_BT2020
                                     : SWS_CS_ITU601));
  int srcRange = frame->color_range == AVCOL_RANGE_JPEG ? 1 : 0;
  sws_setColorspaceDetails(videoContext->swsContext, coefficients, srcRange,
                           sws_getCoefficients(SWS_CS_DEFAULT),
                           /* dstRange= */ 1, /* brightness= */ 0,
                           /* contrast= */ 1 << 16, /* saturation= */ 1 << 16);
  uint8_t* dstPlanes[4] = {(uint8_t*)windowBuffer.bits, nullptr, nullptr,
                           nullptr};
  int dstStrides[4] = {windowBuffer.stride * 4, 0, 0, 0};
  sws_scale(videoContext->swsContext, frame->data, frame->linesize, 0,
            frame->height, dstPlanes, dstStrides);
  ANativeWindow_unlockAndPost(window);
  return VIDEO_DECODER_SUCCESS;
}

VIDEO_DECODER_FUNC(void, ffmpegVideoReleaseFrame, jlong frameHandle) {
  AVFrame* frame = (AVFrame*)frameHandle;
  if (frame) {
    av_frame_free(&frame);
  }
}

VIDEO_DECODER_FUNC(void, ffmpegVideoFlush, jlong jContext) {
  VideoContext* videoContext = (VideoContext*)jContext;
  if (videoContext && videoContext->codecContext) {
    avcodec_flush_buffers(videoContext->codecContext);
  }
}

VIDEO_DECODER_FUNC(void, ffmpegVideoRelease, jlong jContext) {
  VideoContext* videoContext = (VideoContext*)jContext;
  if (!videoContext) {
    return;
  }
  if (videoContext->cachedWindow) {
    ANativeWindow_release(videoContext->cachedWindow);
    videoContext->cachedWindow = nullptr;
  }
  if (videoContext->cachedSurface) {
    env->DeleteGlobalRef(videoContext->cachedSurface);
    videoContext->cachedSurface = nullptr;
  }
  if (videoContext->decodePacket) {
    av_packet_free(&videoContext->decodePacket);
  }
  if (videoContext->swsContext) {
    sws_freeContext(videoContext->swsContext);
    videoContext->swsContext = nullptr;
  }
  if (videoContext->codecContext) {
    avcodec_free_context(&videoContext->codecContext);
  }
  delete videoContext;
}

// ─── Shared helpers ─────────────────────────────────────────────────────────

const AVCodec* getCodecByName(JNIEnv* env, jstring codecName) {
  if (!codecName) {
    return NULL;
  }
  const char* codecNameChars = env->GetStringUTFChars(codecName, NULL);
  const AVCodec* codec = avcodec_find_decoder_by_name(codecNameChars);
  env->ReleaseStringUTFChars(codecName, codecNameChars);
  return codec;
}

AVCodecContext* createContext(JNIEnv* env, const AVCodec* codec,
                              jbyteArray extraData, jboolean outputFloat,
                              jint rawSampleRate, jint rawChannelCount) {
  AVCodecContext* context = avcodec_alloc_context3(codec);
  if (!context) {
    LOGE("Failed to allocate context.");
    return NULL;
  }
  context->request_sample_fmt =
      outputFloat ? OUTPUT_FORMAT_PCM_FLOAT : OUTPUT_FORMAT_PCM_16BIT;
  if (extraData) {
    jsize size = env->GetArrayLength(extraData);
    context->extradata_size = size;
    // av_mallocz: FFmpeg requires the padding region after extradata to be zeroed.
    context->extradata =
        (uint8_t*)av_mallocz(size + AV_INPUT_BUFFER_PADDING_SIZE);
    if (!context->extradata) {
      LOGE("Failed to allocate extradata.");
      releaseContext(context);
      return NULL;
    }
    env->GetByteArrayRegion(extraData, 0, size, (jbyte*)context->extradata);
  }
  if (context->codec_id == AV_CODEC_ID_PCM_MULAW ||
      context->codec_id == AV_CODEC_ID_PCM_ALAW) {
    context->sample_rate = rawSampleRate;
    av_channel_layout_default(&context->ch_layout, rawChannelCount);
  }
  context->err_recognition = AV_EF_IGNORE_ERR;
  int result = avcodec_open2(context, codec, NULL);
  if (result < 0) {
    logError("avcodec_open2", result);
    releaseContext(context);
    return NULL;
  }
  return context;
}

int decodePacket(AVCodecContext* context, AVPacket* packet,
                 uint8_t* outputBuffer, int outputSize,
                 GrowOutputBufferCallback growBuffer) {
  int result = 0;
  // Queue input data.
  result = avcodec_send_packet(context, packet);
  if (result) {
    logError("avcodec_send_packet", result);
    return transformError(result);
  }

  // Dequeue output data until it runs out.
  int outSize = 0;
  while (true) {
    AVFrame* frame = av_frame_alloc();
    if (!frame) {
      LOGE("Failed to allocate output frame.");
      return AUDIO_DECODER_ERROR_INVALID_DATA;
    }
    result = avcodec_receive_frame(context, frame);
    if (result) {
      av_frame_free(&frame);
      if (result == AVERROR(EAGAIN)) {
        break;
      }
      logError("avcodec_receive_frame", result);
      return transformError(result);
    }

    // Resample output.
    AVSampleFormat sampleFormat = context->sample_fmt;
    int channelCount = context->ch_layout.nb_channels;
    int sampleRate = context->sample_rate;
    int sampleCount = frame->nb_samples;
    int dataSize = av_samples_get_buffer_size(NULL, channelCount, sampleCount,
                                              sampleFormat, 1);
    SwrContext* resampleContext = static_cast<SwrContext*>(context->opaque);
    if (!resampleContext) {
      result =
          swr_alloc_set_opts2(&resampleContext,             // ps
                              &context->ch_layout,          // out_ch_layout
                              context->request_sample_fmt,  // out_sample_fmt
                              sampleRate,                   // out_sample_rate
                              &context->ch_layout,          // in_ch_layout
                              sampleFormat,                 // in_sample_fmt
                              sampleRate,                   // in_sample_rate
                              0,                            // log_offset
                              NULL                          // log_ctx
          );
      if (result < 0) {
        logError("swr_alloc_set_opts2", result);
        av_frame_free(&frame);
        return transformError(result);
      }
      result = swr_init(resampleContext);
      if (result < 0) {
        logError("swr_init", result);
        av_frame_free(&frame);
        return transformError(result);
      }
      context->opaque = resampleContext;
    }

    int outSampleSize = av_get_bytes_per_sample(context->request_sample_fmt);
    int outSamples = swr_get_out_samples(resampleContext, sampleCount);
    int bufferOutSize = outSampleSize * channelCount * outSamples;
    if (outSize + bufferOutSize > outputSize) {
      LOGD(
          "Output buffer size (%d) too small for output data (%d), "
          "reallocating buffer.",
          outputSize, outSize + bufferOutSize);
      outputSize = outSize + bufferOutSize;
      outputBuffer = growBuffer(outputSize);
      if (!outputBuffer) {
        LOGE("Failed to reallocate output buffer.");
        av_frame_free(&frame);
        return AUDIO_DECODER_ERROR_OTHER;
      }
    }
    result = swr_convert(resampleContext, &outputBuffer, bufferOutSize,
                         (const uint8_t**)frame->data, frame->nb_samples);
    av_frame_free(&frame);
    if (result < 0) {
      logError("swr_convert", result);
      return AUDIO_DECODER_ERROR_INVALID_DATA;
    }
    int available = swr_get_out_samples(resampleContext, 0);
    if (available != 0) {
      LOGE("Expected no samples remaining after resampling, but found %d.",
           available);
      return AUDIO_DECODER_ERROR_INVALID_DATA;
    }
    outputBuffer += bufferOutSize;
    outSize += bufferOutSize;
  }
  return outSize;
}

int transformError(int errorNumber) {
  return errorNumber == AVERROR_INVALIDDATA ? AUDIO_DECODER_ERROR_INVALID_DATA
                                            : AUDIO_DECODER_ERROR_OTHER;
}

void logError(const char* functionName, int errorNumber) {
  char* buffer = (char*)malloc(ERROR_STRING_BUFFER_LENGTH * sizeof(char));
  av_strerror(errorNumber, buffer, ERROR_STRING_BUFFER_LENGTH);
  LOGE("Error in %s: %s", functionName, buffer);
  free(buffer);
}

void releaseContext(AVCodecContext* context) {
  if (!context) {
    return;
  }
  SwrContext* swrContext;
  if ((swrContext = (SwrContext*)context->opaque)) {
    swr_free(&swrContext);
    context->opaque = NULL;
  }
  avcodec_free_context(&context);
}
