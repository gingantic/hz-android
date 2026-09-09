#pragma once
#include "PlayerCommon.h"

// ─── RandomAccessFile / JniFile for custom AVIO ──────────────────────────────

class RandomAccessFile {
public:
    virtual ~RandomAccessFile() = default;
    virtual int64_t read(uint8_t* buf, int64_t size) = 0;
    virtual int64_t seek(int64_t offset, int whence) = 0;
    virtual int64_t size() = 0;
    virtual bool ok() const = 0;
};

class JniFile : public RandomAccessFile {
private:
    jobject bridge_ = nullptr;
    jmethodID midReadAt_ = nullptr;
    jmethodID midGetSize_ = nullptr;
    jmethodID midAbortRead_ = nullptr;
    jbyteArray bufferArray_ = nullptr;
    int bufferCap_ = 0;
    int64_t pos_ = 0;
    mutable int64_t cachedSize_ = -1;
    bool ok_ = false;

public:
    JniFile(JNIEnv* env, jobject bridge) {
        if (!bridge) return;
        bridge_ = env->NewGlobalRef(bridge);
        jclass cls = env->GetObjectClass(bridge);
        if (env->ExceptionCheck()) { env->ExceptionClear(); return; }

        midReadAt_ = env->GetMethodID(cls, "readAt", "(J[BI)I");
        if (env->ExceptionCheck()) env->ExceptionClear();

        midGetSize_ = env->GetMethodID(cls, "getSize", "()J");
        if (env->ExceptionCheck()) env->ExceptionClear();

        // Optional: older/custom bridges may not implement cancellation.
        midAbortRead_ = env->GetMethodID(cls, "abortRead", "()V");
        if (env->ExceptionCheck()) env->ExceptionClear();

        env->DeleteLocalRef(cls);

        if (midReadAt_ && midGetSize_) {
            ok_ = true;
        } else {
            LOGE("JniFile: missing readAt/getSize methods on bridge object");
        }
    }

    ~JniFile() override {
        bool attached = false;
        JNIEnv* env = getJniEnv(&attached);
        if (env) {
            if (bufferArray_) env->DeleteGlobalRef(bufferArray_);
            if (bridge_) {
                jclass cls = env->GetObjectClass(bridge_);
                if (!env->ExceptionCheck() && cls) {
                    jmethodID midClose = env->GetMethodID(cls, "close", "()V");
                    if (env->ExceptionCheck()) {
                        env->ExceptionClear();
                    } else if (midClose) {
                        env->CallVoidMethod(bridge_, midClose);
                        if (env->ExceptionCheck()) env->ExceptionClear();
                    }
                    env->DeleteLocalRef(cls);
                } else {
                    env->ExceptionClear();
                }
                env->DeleteGlobalRef(bridge_);
            }
        }
        if (attached && g_jvm) {
            g_jvm->DetachCurrentThread();
        }
    }

    void abortRead() {
        if (!bridge_ || !midAbortRead_) return;

        bool attached = false;
        JNIEnv* env = getJniEnv(&attached);
        if (env) {
            env->CallVoidMethod(bridge_, midAbortRead_);
            if (env->ExceptionCheck()) env->ExceptionClear();
        }
        if (attached && g_jvm) {
            g_jvm->DetachCurrentThread();
        }
    }

    int64_t read(uint8_t* buf, int64_t size) override {
        if (!ok_ || size <= 0) return 0;
        JNIEnv* env = getJniEnv();
        if (!env) return AVERROR_EXIT;

        int chunkSize = static_cast<int>(std::min<int64_t>(size, 256 * 1024));
        if (!bufferArray_ || bufferCap_ < chunkSize) {
            if (bufferArray_) env->DeleteGlobalRef(bufferArray_);
            jbyteArray localArr = env->NewByteArray(chunkSize);
            if (!localArr) return AVERROR_EXIT;
            bufferArray_ = static_cast<jbyteArray>(env->NewGlobalRef(localArr));
            env->DeleteLocalRef(localArr);
            bufferCap_ = chunkSize;
        }

        jint readBytes = env->CallIntMethod(bridge_, midReadAt_, static_cast<jlong>(pos_), bufferArray_, chunkSize);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return AVERROR_EXIT;
        }
        if (readBytes < 0) return AVERROR_EOF;
        if (readBytes > 0) {
            env->GetByteArrayRegion(bufferArray_, 0, readBytes, reinterpret_cast<jbyte*>(buf));
            pos_ += readBytes;
        }
        return readBytes;
    }

    int64_t seek(int64_t offset, int whence) override {
        if (!ok_) return AVERROR_EXIT;
        switch (whence) {
            case SEEK_SET: pos_ = offset; break;
            case SEEK_CUR: pos_ += offset; break;
            case SEEK_END: {
                int64_t s = size();
                if (s >= 0) pos_ = s + offset;
                else return AVERROR_EXIT;
                break;
            }
            case AVSEEK_SIZE:
                return size();
            default:
                return AVERROR_EXIT;
        }
        return pos_;
    }

    int64_t size() override {
        if (!ok_) return -1;
        if (cachedSize_ >= 0) return cachedSize_;
        JNIEnv* env = getJniEnv();
        if (!env) return -1;
        jlong s = env->CallLongMethod(bridge_, midGetSize_);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return -1;
        }
        if (s >= 0) {
            cachedSize_ = static_cast<int64_t>(s);
        }
        return cachedSize_;
    }

    bool ok() const override { return ok_ && bridge_ != nullptr; }
};

struct PlayerIOBridge {
    RandomAccessFile* file = nullptr;
    std::atomic<bool> abortRequested{false};
};

// AVIO callbacks (definitions in JniFileIO.cpp)
int player_io_read(void* opaque, uint8_t* buf, int bufSize);
int64_t player_io_seek(void* opaque, int64_t offset, int whence);
