#pragma once
#include "PlayerCommon.h"

// ─── Dynamic Multi-Dimensional Packet Queue ───────────────────────────────────

struct PacketQueue {
    struct Item {
        AVPacket* pkt = nullptr;
        int64_t ptsUs = -1;
        bool isFlush = false;
        bool isEof = false;
    };

    std::deque<Item> items;
    std::mutex mtx;
    std::condition_variable notEmpty;
    std::condition_variable notFull;
    std::atomic<bool> aborted{false};

    // Multi-dimensional queue limits
    size_t minPackets = 15;
    size_t maxPackets = 150;
    size_t maxBytes = 32 * 1024 * 1024; // 32 MB default
    int64_t maxDurationUs = 2000000LL;   // 2.0 seconds default

    // Running metrics
    size_t totalBytes = 0;
    int64_t durationUs = 0;
    int64_t firstPtsUs = -1;
    int64_t lastPtsUs = -1;

    void setLimits(size_t minPkts, size_t maxPkts, size_t maxB, int64_t maxDurUs) {
        std::lock_guard<std::mutex> lk(mtx);
        minPackets = minPkts;
        maxPackets = maxPkts;
        maxBytes = maxB;
        maxDurationUs = maxDurUs;
    }

    bool isFullLocked() const {
        if (items.size() < minPackets) return false;
        if (items.size() >= maxPackets) return true;
        if (totalBytes >= maxBytes) return true;
        if (maxDurationUs > 0 && durationUs >= maxDurationUs) return true;
        return false;
    }

    void recalculateStatsLocked() {
        totalBytes = 0;
        firstPtsUs = -1;
        lastPtsUs = -1;
        durationUs = 0;
        for (const auto& it : items) {
            if (it.pkt) {
                totalBytes += static_cast<size_t>(it.pkt->size);
            }
            if (it.ptsUs >= 0) {
                if (firstPtsUs < 0) firstPtsUs = it.ptsUs;
                lastPtsUs = it.ptsUs;
            }
        }
        if (firstPtsUs >= 0 && lastPtsUs >= firstPtsUs) {
            durationUs = lastPtsUs - firstPtsUs;
        }
    }

    void push(AVPacket* pkt, AVRational timeBase = {1, 1000}) {
        if (!pkt) return;
        int64_t ptsUs = (pkt->pts != AV_NOPTS_VALUE)
            ? av_rescale_q(pkt->pts, timeBase, AV_TIME_BASE_Q)
            : ((pkt->dts != AV_NOPTS_VALUE)
                ? av_rescale_q(pkt->dts, timeBase, AV_TIME_BASE_Q)
                : -1);

        std::unique_lock<std::mutex> lk(mtx);
        notFull.wait_for(lk, std::chrono::milliseconds(20), [&] {
            return aborted.load() || !isFullLocked();
        });
        if (aborted.load()) {
            av_packet_free(&pkt);
            return;
        }

        totalBytes += static_cast<size_t>(pkt->size);
        if (ptsUs >= 0) {
            if (firstPtsUs < 0) firstPtsUs = ptsUs;
            lastPtsUs = ptsUs;
            if (lastPtsUs >= firstPtsUs) {
                durationUs = lastPtsUs - firstPtsUs;
            }
        }

        items.push_back({pkt, ptsUs, false, false});
        notEmpty.notify_one();
    }

    void pushFlush() {
        std::lock_guard<std::mutex> lk(mtx);
        items.push_back({nullptr, -1, true, false});
        notEmpty.notify_one();
    }

    void pushEof() {
        std::lock_guard<std::mutex> lk(mtx);
        items.push_back({nullptr, -1, false, true});
        notEmpty.notify_one();
    }

    bool empty() {
        std::lock_guard<std::mutex> lk(mtx);
        return items.empty();
    }

    size_t size() {
        std::lock_guard<std::mutex> lk(mtx);
        return items.size();
    }

    size_t getBytes() {
        std::lock_guard<std::mutex> lk(mtx);
        return totalBytes;
    }

    int64_t getDurationUs() {
        std::lock_guard<std::mutex> lk(mtx);
        return durationUs;
    }

    bool pop(Item& out, int timeoutMs = 50) {
        std::unique_lock<std::mutex> lk(mtx);
        if (!notEmpty.wait_for(lk, std::chrono::milliseconds(timeoutMs), [&] {
            return aborted.load() || !items.empty();
        })) {
            return false;
        }
        if (items.empty()) return false;
        out = items.front();
        items.pop_front();
        if (out.pkt) {
            if (totalBytes >= static_cast<size_t>(out.pkt->size)) {
                totalBytes -= static_cast<size_t>(out.pkt->size);
            } else {
                totalBytes = 0;
            }
        }
        // Incremental stats update — avoids O(N) full queue scan on every dequeue.
        // totalBytes is already decremented above; just advance firstPtsUs from the new front.
        if (!items.empty()) {
            firstPtsUs = -1;
            for (const auto& it : items) {
                if (it.ptsUs >= 0) { firstPtsUs = it.ptsUs; break; }
            }
            if (firstPtsUs >= 0 && lastPtsUs >= firstPtsUs) {
                durationUs = lastPtsUs - firstPtsUs;
            } else {
                durationUs = 0;
            }
        } else {
            firstPtsUs = -1; lastPtsUs = -1; durationUs = 0;
        }
        notFull.notify_one();
        return true;
    }

    void clear() {
        std::lock_guard<std::mutex> lk(mtx);
        for (auto& it : items) {
            if (it.pkt) av_packet_free(&it.pkt);
        }
        items.clear();
        totalBytes = 0;
        durationUs = 0;
        firstPtsUs = -1;
        lastPtsUs = -1;
        notFull.notify_all();
        notEmpty.notify_all();
    }

    void abort() {
        aborted.store(true);
        notEmpty.notify_all();
        notFull.notify_all();
    }

    void reset() {
        clear();
        aborted.store(false);
    }
};
