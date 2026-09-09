#include "FfmpegPlayerContext.h"

int player_interrupt_callback(void* opaque) {
    auto* ctx = static_cast<FfmpegPlayerContext*>(opaque);
    if (!ctx) return 0;
    if (ctx->ioBridge.abortRequested.load() || ctx->isStopped.load()) {
        return 1;
    }
    int64_t timeout = ctx->ioTimeoutMs.load();
    if (timeout > 0) {
        int64_t lastIo = ctx->lastIoTimeMs.load();
        if (lastIo > 0) {
            int64_t now = getMonotonicTimeMs();
            if ((now - lastIo) > timeout) {
                LOGW("player_interrupt_callback: IO stalled for %" PRId64 " ms > %" PRId64 " ms timeout. Interrupting demuxer.",
                     (now - lastIo), timeout);
                return 1;
            }
        }
    }
    return 0;
}
