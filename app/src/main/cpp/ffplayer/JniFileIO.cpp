#include "JniFileIO.h"

int player_io_read(void* opaque, uint8_t* buf, int bufSize) {
    auto* io = static_cast<PlayerIOBridge*>(opaque);
    if (!io || io->abortRequested.load()) {
        return AVERROR_EXIT;
    }
    int64_t n = io->file->read(buf, bufSize);
    return static_cast<int>(n);
}

int64_t player_io_seek(void* opaque, int64_t offset, int whence) {
    auto* io = static_cast<PlayerIOBridge*>(opaque);
    if (!io || io->abortRequested.load()) {
        return AVERROR_EXIT;
    }
    if (whence == AVSEEK_SIZE) {
        return io->file->size();
    }
    return io->file->seek(offset, whence);
}
