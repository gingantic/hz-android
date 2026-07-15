#include <jni.h>
#include <android/log.h>

extern "C" {
#include <archive.h>
#include <archive_entry.h>
}

#include <cstring>
#include <string>
#include <vector>

#define LOG_TAG "HzPlayer/Archive"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ponytail: libarchive read session. No per-entry random access in libarchive;
// backward seek = close+reopen+locate+skip (see docs/ARCHIVE_SUPPORT.md §3).
// firstBlock: libarchive's next_header + first data_block already consumes the
// entry's first data chunk, so we stash it here and replay it on the first read.
struct Session {
    struct archive* a = nullptr;
    std::string path;
    std::string entry;
    std::string password;
    la_int64_t totalSize = 0;
    la_int64_t pos = 0;
    bool ok = false;
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
                                la_int64_t* outSize) {
    struct archive* a = archive_read_new();
    archive_read_support_filter_all(a);
    archive_read_support_format_all(a);
    if (!password.empty()) {
        archive_read_add_passphrase(a, password.c_str());
    }
    if (archive_read_open_filename(a, path.c_str(), 10240) != ARCHIVE_OK) {
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

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_rhnxdev_hzplayer_data_datasource_archive_ArchiveNative_nativeList(
    JNIEnv* env, jclass, jstring jPath, jstring jPassword) {

    std::string path = jstr(env, jPath);
    std::string password = jstr(env, jPassword);

    struct archive* a = archive_read_new();
    archive_read_support_filter_all(a);
    archive_read_support_format_all(a);
    if (!password.empty()) {
        archive_read_add_passphrase(a, password.c_str());
    }
    int open_hr = archive_read_open_filename(a, path.c_str(), 10240);
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
    for (size_t i = 0; i < entries.size(); i++) {
        env->SetObjectArrayElement(arr, static_cast<jsize>(i),
                                   env->NewStringUTF(entries[i].c_str()));
    }
    return arr;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_rhnxdev_hzplayer_data_datasource_archive_ArchiveNative_nativeOpen(
    JNIEnv* env, jclass, jstring jPath, jstring jEntry, jstring jPassword) {

    std::string path = jstr(env, jPath);
    std::string entry = jstr(env, jEntry);
    std::string password = jstr(env, jPassword);

    Session* s = reinterpret_cast<Session*>(
        openPositioned(path, entry, password, nullptr));
    if (s) {
        LOGD("nativeOpen: entry=%s total=%lld", entry.c_str(),
             static_cast<long long>(s->totalSize));
    }
    return s ? reinterpret_cast<jlong>(s) : 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_rhnxdev_hzplayer_data_datasource_archive_ArchiveNative_nativeLength(
    JNIEnv*, jclass, jlong handle) {
    Session* s = reinterpret_cast<Session*>(handle);
    return s ? s->totalSize : -1;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rhnxdev_hzplayer_data_datasource_archive_ArchiveNative_nativeRead(
    JNIEnv* env, jclass, jlong handle, jbyteArray jbuf, jint off, jint len) {

    Session* s = reinterpret_cast<Session*>(handle);
    if (!s || !s->a) return -1;
    if (len <= 0) return 0;

    jbyte* arr = env->GetByteArrayElements(jbuf, nullptr);
    if (!arr) return -1;
    uint8_t* out = reinterpret_cast<uint8_t*>(arr) + off;

    int got = 0;
    while (got < len) {
        la_ssize_t rr = archive_read_data(
            s->a, out + got, static_cast<size_t>(len - got));
        if (rr == 0) break;                 // EOF
        if (rr < 0) {
            LOGE("nativeRead: %s", archive_error_string(s->a));
            env->ReleaseByteArrayElements(jbuf, arr, JNI_ABORT);
            return -1;
        }
        got += static_cast<int>(rr);
        s->pos += rr;
    }
    env->ReleaseByteArrayElements(jbuf, arr, 0);
    return got;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rhnxdev_hzplayer_data_datasource_archive_ArchiveNative_nativeSeek(
    JNIEnv*, jclass, jlong handle, jlong target) {

    Session* s = reinterpret_cast<Session*>(handle);
    if (!s) return JNI_FALSE;
    if (target == s->pos) return JNI_TRUE;
    if (target < 0) return JNI_FALSE;

    // Expensive path: libarchive has no backward seek, so re-open + skip to
    // target. Solid archives re-decompress preceding entries (see §3).
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
        openPositioned(path, entry, password, &size));
    if (!ns) {
        s->ok = false;
        return JNI_FALSE;
    }
    // Adopt the freshly opened session into the existing handle.
    s->a = ns->a;
    s->totalSize = size;
    s->pos = ns->pos;
    ns->a = nullptr;        // ownership moved; don't double-free
    delete ns;
    s->pos = 0;

    la_int64_t remaining = target;
    uint8_t dump[65536];
    while (remaining > 0) {
        size_t chunk = static_cast<size_t>(remaining);
        if (chunk > sizeof(dump)) chunk = sizeof(dump);
        la_ssize_t rr = archive_read_data(s->a, dump, chunk);
        if (rr == 0) break;                 // EOF
        if (rr < 0) {
            archive_read_free(s->a);
            s->a = nullptr;
            s->ok = false;
            return JNI_FALSE;
        }
        remaining -= rr;
    }
    s->pos = target;
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rhnxdev_hzplayer_data_datasource_archive_ArchiveNative_nativeClose(
    JNIEnv*, jclass, jlong handle) {
    Session* s = reinterpret_cast<Session*>(handle);
    if (!s) return;
    if (s->a) archive_read_free(s->a);
    delete s;
}
