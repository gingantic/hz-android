#include <jni.h>
#include <android/log.h>

extern "C" {
#include <archive.h>
#include <archive_entry.h>
}

#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include <mutex>

#define LOG_TAG "HzPlayer/Archive"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Bridges a Kotlin RandomAccessMediaSource into libarchive read/seek/skip callbacks
// so remote archives (SMB, SFTP, FTP, WebDAV) can be listed and streamed in-place.
struct BridgeReader {
    JavaVM* jvm = nullptr;
    jobject sourceGlobal = nullptr;
    jmethodID midReadAt = nullptr;
    jmethodID midGetSize = nullptr;
    jbyteArray jbufGlobal = nullptr;
    jsize bufCap = 131072;
    std::vector<uint8_t> scratch;
    la_int64_t pos = 0;
    la_int64_t totalSize = -1;

    JNIEnv* getEnv() const {
        if (!jvm) return nullptr;
        JNIEnv* env = nullptr;
        if (jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            if (jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
        }
        return env;
    }

    bool init(JNIEnv* env, jobject jSource) {
        if (!env || !jSource) return false;
        env->GetJavaVM(&jvm);
        jclass cls = env->GetObjectClass(jSource);
        if (!cls) return false;
        midReadAt = env->GetMethodID(cls, "readAt", "(J[BI)I");
        midGetSize = env->GetMethodID(cls, "getSize", "()J");
        env->DeleteLocalRef(cls);
        if (!midReadAt || !midGetSize) return false;

        sourceGlobal = env->NewGlobalRef(jSource);
        jbyteArray localBuf = env->NewByteArray(bufCap);
        if (!localBuf) return false;
        jbufGlobal = reinterpret_cast<jbyteArray>(env->NewGlobalRef(localBuf));
        env->DeleteLocalRef(localBuf);
        if (!sourceGlobal || !jbufGlobal) return false;

        scratch.resize(static_cast<size_t>(bufCap));
        totalSize = env->CallLongMethod(sourceGlobal, midGetSize);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
        pos = 0;
        return true;
    }

    ~BridgeReader() {
        JNIEnv* env = getEnv();
        if (env) {
            if (sourceGlobal) env->DeleteGlobalRef(sourceGlobal);
            if (jbufGlobal) env->DeleteGlobalRef(jbufGlobal);
        }
    }
};

static la_ssize_t bridgeReadCb(struct archive* a, void* clientData, const void** buffer) {
    BridgeReader* br = static_cast<BridgeReader*>(clientData);
    if (!br) {
        *buffer = nullptr;
        return ARCHIVE_FATAL;
    }
    if (br->totalSize >= 0 && br->pos >= br->totalSize) {
        *buffer = nullptr;
        return 0;
    }
    JNIEnv* env = br->getEnv();
    if (!env) {
        archive_set_error(a, EIO, "JNI env unavailable in bridgeReadCb");
        return ARCHIVE_FATAL;
    }
    jint want = br->bufCap;
    if (br->totalSize >= 0 && br->pos + want > br->totalSize) {
        want = static_cast<jint>(br->totalSize - br->pos);
    }
    if (want <= 0) {
        *buffer = nullptr;
        return 0;
    }
    jint n = env->CallIntMethod(br->sourceGlobal, br->midReadAt,
                                static_cast<jlong>(br->pos), br->jbufGlobal, want);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        archive_set_error(a, EIO, "I/O error reading remote archive");
        return ARCHIVE_FATAL;
    }
    if (n <= 0) {
        *buffer = nullptr;
        return 0;
    }
    env->GetByteArrayRegion(br->jbufGlobal, 0, n,
                            reinterpret_cast<jbyte*>(br->scratch.data()));
    br->pos += n;
    *buffer = br->scratch.data();
    return static_cast<la_ssize_t>(n);
}

static la_int64_t bridgeSeekCb(struct archive* a, void* clientData, la_int64_t offset, int whence) {
    BridgeReader* br = static_cast<BridgeReader*>(clientData);
    if (!br || br->totalSize < 0) return ARCHIVE_FATAL;
    la_int64_t target = 0;
    switch (whence) {
        case SEEK_SET: target = offset; break;
        case SEEK_CUR: target = br->pos + offset; break;
        case SEEK_END: target = br->totalSize + offset; break;
        default: return ARCHIVE_FATAL;
    }
    if (target < 0 || target > br->totalSize) {
        archive_set_error(a, EINVAL, "Bridge seek out of bounds");
        return ARCHIVE_FATAL;
    }
    br->pos = target;
    return br->pos;
}

static la_int64_t bridgeSkipCb(struct archive*, void* clientData, la_int64_t request) {
    BridgeReader* br = static_cast<BridgeReader*>(clientData);
    if (!br || request <= 0 || br->totalSize < 0) return 0;
    la_int64_t avail = br->totalSize - br->pos;
    if (avail <= 0) return 0;
    la_int64_t skipped = std::min(request, avail);
    br->pos += skipped;
    return skipped;
}

static int openArchiveContainer(struct archive* a, const std::string& path, BridgeReader* br) {
    if (br != nullptr) {
        br->pos = 0;
        archive_read_set_read_callback(a, bridgeReadCb);
        archive_read_set_seek_callback(a, bridgeSeekCb);
        archive_read_set_skip_callback(a, bridgeSkipCb);
        archive_read_set_callback_data(a, br);
        return archive_read_open1(a);
    }
    return archive_read_open_filename(a, path.c_str(), 65536);
}

// libarchive read session. No per-entry random access in libarchive;
// backward seek = close+reopen+locate+skip (see docs/ARCHIVE_SUPPORT.md §3).
// firstBlock: libarchive's next_header + first data_block already consumes the
// entry's first data chunk, so we stash it here and replay it on the first read.
struct Session {
    struct archive* a = nullptr;
    BridgeReader* reader = nullptr;
    std::string path;
    std::string entry;
    std::string password;
    la_int64_t totalSize = 0;
    la_int64_t pos = 0;
    bool ok = false;
    std::vector<uint8_t> readBuf;
    std::mutex mtx;
};

static std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return std::string();
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r = c ? c : "";
    env->ReleaseStringUTFChars(s, c);
    return r;
}

// Open the archive, locate [entry], and leave the read cursor at its first
// data block. Returns the open archive (caller owns) or nullptr on any failure.
static Session* openPositioned(const std::string& path,
                                const std::string& entry,
                                const std::string& password,
                                la_int64_t* outSize,
                                BridgeReader* br = nullptr) {
    struct archive* a = archive_read_new();
    if (!a) { LOGE("openPositioned: archive_read_new() returned NULL"); return nullptr; }
    archive_read_support_filter_all(a);
    archive_read_support_format_all(a);
    if (!password.empty()) {
        archive_read_add_passphrase(a, password.c_str());
    }
    if (openArchiveContainer(a, path, br) != ARCHIVE_OK) {
        LOGE("openPositioned: open failed: %s", archive_error_string(a));
        archive_read_free(a);
        return nullptr;
    }
    struct archive_entry* e;
    bool found = false;
    int idx = 0;
    la_int64_t size = 0;
    for (;;) {
        int hr = archive_read_next_header(a, &e);
        if (hr == ARCHIVE_EOF) break;
        if (hr < ARCHIVE_OK) {
            LOGE("openPositioned: next_header %d: %s", idx,
                 archive_error_string(a));
            break;
        }
        const char* name = archive_entry_pathname(e);
        if (name && entry == name) {
            size = archive_entry_size(e);
            if (outSize) *outSize = size;
            found = true;
            LOGD("openPositioned: MATCH idx=%d name=%s size=%lld", idx, name,
                 static_cast<long long>(size));
            break;      // cursor now sits on this entry's data; do NOT skip it
        }
        archive_read_data_skip(a);
        idx++;
    }
    if (!found) {
        LOGE("openPositioned: entry not found: %s", entry.c_str());
        archive_read_free(a);
        return nullptr;
    }
    LOGD("openPositioned: building Session for %s", entry.c_str());
    Session* s = new Session();
    s->a = a;
    s->reader = br;
    s->path = path;
    s->entry = entry;
    s->password = password;
    s->totalSize = size;
    s->pos = 0;
    s->ok = true;
    LOGD("openPositioned: Session built ok");
    return s;
}

static void throwException(JNIEnv* env, const char* name, const char* msg) {
    jclass cls = env->FindClass(name);
    if (cls != nullptr) {
        env->ThrowNew(cls, msg);
    }
}

static bool isPassphraseError(struct archive* a, int hr, const std::string& path, const std::string& password) {
    if (hr >= ARCHIVE_OK) return false;
    const char* errStr = archive_error_string(a);
    if (!errStr) {
        if (!password.empty()) return false;
        std::string lowercasePath = path;
        for (auto& c : lowercasePath) c = tolower(c);
        return (lowercasePath.rfind(".rar") != std::string::npos ||
                lowercasePath.rfind(".7z") != std::string::npos ||
                lowercasePath.rfind(".zip") != std::string::npos);
    }
    std::string s(errStr);
    for (auto& c : s) c = tolower(c);
    return (s.find("passphrase") != std::string::npos ||
            s.find("password") != std::string::npos ||
            s.find("decrypt") != std::string::npos ||
            s.find("crypt") != std::string::npos);
}

static jobjectArray listArchiveImpl(JNIEnv* env,
                                    const std::string& path,
                                    const std::string& password,
                                    BridgeReader* br) {
    struct archive* a = archive_read_new();
    if (!a) { LOGE("nativeList: archive_read_new() returned NULL"); throwException(env, "java/io/IOException", "archive_read_new failed"); return nullptr; }
    archive_read_support_filter_all(a);
    archive_read_support_format_all(a);
    if (!password.empty()) {
        archive_read_add_passphrase(a, password.c_str());
    }
    int open_hr = openArchiveContainer(a, path, br);
    if (open_hr < ARCHIVE_OK) {
        LOGE("nativeList: open warning/error %d: %s", open_hr, archive_error_string(a));
        if (open_hr < ARCHIVE_WARN || isPassphraseError(a, open_hr, path, password)) {
            std::string errMsg = isPassphraseError(a, open_hr, path, password) ? "Passphrase required" : (archive_error_string(a) ? archive_error_string(a) : "Open failed");
            throwException(env, "java/io/IOException", errMsg.c_str());
            archive_read_free(a);
            return nullptr;
        }
    }

    std::vector<std::string> entries;
    struct archive_entry* e;
    int hr;
    bool hasEncrypted = false;
    while ((hr = archive_read_next_header(a, &e)) == ARCHIVE_OK || hr == ARCHIVE_WARN) {
        const char* name = archive_entry_pathname(e);
        if (name) {
            int isDir = (archive_entry_filetype(e) == AE_IFDIR) ? 1 : 0;
            if (archive_entry_is_encrypted(e)) {
                hasEncrypted = true;
            }
            entries.push_back(std::string(name) + "\t" +
                              std::to_string(archive_entry_size(e)) + "\t" +
                              std::to_string(isDir));
        }
        archive_read_data_skip(a);
    }

    if (archive_read_has_encrypted_entries(a) > 0) {
        hasEncrypted = true;
    }

    if (hr < ARCHIVE_OK && hr != ARCHIVE_EOF) {
        LOGE("nativeList: next_header warning/error %d: %s", hr, archive_error_string(a));
        if (hr < ARCHIVE_WARN || isPassphraseError(a, hr, path, password)) {
            std::string errMsg = isPassphraseError(a, hr, path, password) ? "Passphrase required" : (archive_error_string(a) ? archive_error_string(a) : "Read failed");
            throwException(env, "java/io/IOException", errMsg.c_str());
            archive_read_free(a);
            return nullptr;
        }
    }

    if (hasEncrypted && password.empty()) {
        LOGD("nativeList: archive contains encrypted entries but no password was provided");
        throwException(env, "java/io/IOException", "Passphrase required");
        archive_read_free(a);
        return nullptr;
    }

    LOGD("nativeList: %s -> %zu entries", path.c_str(), entries.size());
    archive_read_free(a);

    jclass strCls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(entries.size()),
                                           strCls, nullptr);
    env->DeleteLocalRef(strCls);
    for (size_t i = 0; i < entries.size(); i++) {
        jstring js = env->NewStringUTF(entries[i].c_str());
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), js);
        env->DeleteLocalRef(js);
    }
    return arr;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_rhnxdev_hzplayer_data_datasource_archive_ArchiveNative_nativeList(
    JNIEnv* env, jclass, jstring jPath, jstring jPassword) {

    std::string path = jstr(env, jPath);
    std::string password = jstr(env, jPassword);
    return listArchiveImpl(env, path, password, nullptr);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_rhnxdev_hzplayer_data_datasource_archive_ArchiveNative_nativeListBridge(
    JNIEnv* env, jclass, jobject jSource, jstring jPathHint, jstring jPassword) {

    std::string path = jstr(env, jPathHint);
    std::string password = jstr(env, jPassword);
    BridgeReader br;
    if (!br.init(env, jSource)) {
        throwException(env, "java/io/IOException", "Failed to initialize remote archive reader");
        return nullptr;
    }
    return listArchiveImpl(env, path, password, &br);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_rhnxdev_hzplayer_data_datasource_archive_ArchiveNative_nativeOpen(
    JNIEnv* env, jclass, jstring jPath, jstring jEntry, jstring jPassword) {

    std::string path = jstr(env, jPath);
    std::string entry = jstr(env, jEntry);
    std::string password = jstr(env, jPassword);

    Session* s = reinterpret_cast<Session*>(
        openPositioned(path, entry, password, nullptr, nullptr));
    if (s) {
        LOGD("nativeOpen: entry=%s total=%lld", entry.c_str(),
             static_cast<long long>(s->totalSize));
    }
    return s ? reinterpret_cast<jlong>(s) : 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_rhnxdev_hzplayer_data_datasource_archive_ArchiveNative_nativeOpenBridge(
    JNIEnv* env, jclass, jobject jSource, jstring jPathHint, jstring jEntry, jstring jPassword) {

    std::string path = jstr(env, jPathHint);
    std::string entry = jstr(env, jEntry);
    std::string password = jstr(env, jPassword);

    BridgeReader* br = new BridgeReader();
    if (!br->init(env, jSource)) {
        delete br;
        return 0;
    }

    Session* s = reinterpret_cast<Session*>(
        openPositioned(path, entry, password, nullptr, br));
    if (!s) {
        delete br;
        return 0;
    }
    LOGD("nativeOpenBridge: entry=%s total=%lld", entry.c_str(),
         static_cast<long long>(s->totalSize));
    return reinterpret_cast<jlong>(s);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_rhnxdev_hzplayer_data_datasource_archive_ArchiveNative_nativeLength(
    JNIEnv*, jclass, jlong handle) {
    Session* s = reinterpret_cast<Session*>(handle);
    if (!s) return -1;
    std::lock_guard<std::mutex> lk(s->mtx);
    return s->totalSize;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rhnxdev_hzplayer_data_datasource_archive_ArchiveNative_nativeRead(
    JNIEnv* env, jclass, jlong handle, jbyteArray jbuf, jint off, jint len) {

    Session* s = reinterpret_cast<Session*>(handle);
    if (!s || !s->a) return -1;
    if (len <= 0) return 0;

    std::lock_guard<std::mutex> lk(s->mtx);
    if (!s->a) return -1;

    if (static_cast<size_t>(len) > s->readBuf.size()) {
        s->readBuf.resize(static_cast<size_t>(len));
    }
    uint8_t* out = s->readBuf.data();

    int got = 0;
    while (got < len) {
        la_ssize_t rr = archive_read_data(
            s->a, out + got, static_cast<size_t>(len - got));
        if (rr == 0) break;                 // EOF
        if (rr < 0) {
            LOGE("nativeRead: %s", archive_error_string(s->a));
            return -1;
        }
        got += static_cast<int>(rr);
        s->pos += rr;
    }
    if (got > 0) {
        env->SetByteArrayRegion(jbuf, off, got, reinterpret_cast<const jbyte*>(out));
    }
    return got;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rhnxdev_hzplayer_data_datasource_archive_ArchiveNative_nativeSeek(
    JNIEnv*, jclass, jlong handle, jlong target) {

    Session* s = reinterpret_cast<Session*>(handle);
    if (!s) return JNI_FALSE;
    std::lock_guard<std::mutex> lk(s->mtx);
    if (target == s->pos) return JNI_TRUE;
    if (target < 0) return JNI_FALSE;

    // 1. O(1) native entry seek when supported by the format/compression
    // (e.g. stored/uncompressed entries in ZIP/RAR/7z, TAR, ISO9660).
    if (s->a != nullptr) {
        la_int64_t sought = archive_seek_data(s->a, target, SEEK_SET);
        if (sought >= 0) {
            s->pos = sought;
            return (sought == target) ? JNI_TRUE : JNI_FALSE;
        }
    }

    // 2. Fast path: forward seek when archive session is still valid.
    // Discard the delta in-between without closing and re-opening from byte 0.
    if (target > s->pos && s->a != nullptr) {
        la_int64_t remaining = target - s->pos;
        std::vector<uint8_t> dump(256 * 1024);
        bool skipOk = true;
        while (remaining > 0) {
            size_t chunk = static_cast<size_t>(std::min<la_int64_t>(remaining, dump.size()));
            la_ssize_t rr = archive_read_data(s->a, dump.data(), chunk);
            if (rr == 0) {
                s->pos = target - remaining;
                return (remaining == 0) ? JNI_TRUE : JNI_FALSE;
            }
            if (rr < 0) {
                LOGE("nativeSeek: forward skip error %s", archive_error_string(s->a));
                skipOk = false;
                break;
            }
            remaining -= rr;
            s->pos += rr;
        }
        if (skipOk && remaining == 0) {
            return JNI_TRUE;
        }
    }

    // Fallback path (backward seek or recovery): re-open from beginning + skip to target.
    LOGD("nativeSeek: reopen+skip %s#%s from=%lld to=%lld",
         s->path.c_str(), s->entry.c_str(),
         static_cast<long long>(s->pos), static_cast<long long>(target));

    if (s->a) {
        archive_read_free(s->a);
        s->a = nullptr;
    }
    la_int64_t size = 0;
    std::string path = s->path, entry = s->entry, password = s->password;
    Session* ns = reinterpret_cast<Session*>(
        openPositioned(path, entry, password, &size, s->reader));
    if (!ns) {
        s->ok = false;
        s->totalSize = -1;
        return JNI_FALSE;
    }
    // Adopt the freshly opened session into the existing handle.
    s->a = ns->a;
    s->totalSize = size;
    s->pos = ns->pos;
    ns->a = nullptr;        // ownership moved; don't double-free
    ns->reader = nullptr;   // s keeps ownership of s->reader
    delete ns;
    s->pos = 0;

    if (target == 0) {
        return JNI_TRUE;
    }

    la_int64_t remaining = target;
    std::vector<uint8_t> dump(256 * 1024);
    while (remaining > 0) {
        size_t chunk = static_cast<size_t>(std::min<la_int64_t>(remaining, dump.size()));
        la_ssize_t rr = archive_read_data(s->a, dump.data(), chunk);
        if (rr == 0) break;                 // EOF
        if (rr < 0) {
            archive_read_free(s->a);
            s->a = nullptr;
            s->ok = false;
            return JNI_FALSE;
        }
        remaining -= rr;
    }
    s->pos = target - remaining;
    if (remaining > 0) {
        LOGE("nativeSeek: could not reach target %lld, stopped at %lld",
             static_cast<long long>(target), static_cast<long long>(s->pos));
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rhnxdev_hzplayer_data_datasource_archive_ArchiveNative_nativeClose(
    JNIEnv*, jclass, jlong handle) {
    Session* s = reinterpret_cast<Session*>(handle);
    if (!s) return;
    {
        std::lock_guard<std::mutex> lk(s->mtx);
        if (s->a) archive_read_free(s->a);
        s->a = nullptr;
        delete s->reader;
        s->reader = nullptr;
        s->ok = false;
    }
    delete s;
}
