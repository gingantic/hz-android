#pragma once
#include "PlayerCommon.h"

#include <cmath>
#include <cstdint>

// ─── HDR Tone-Mapping Processor (Fallback CPU blit path) ─────────────────────

class HdrToneMapper {
private:
    float pqToLinear[1024];
    float hlgToLinear[1024];
    uint8_t linearToSdr[4096];
    bool initialized = false;

    // Persistent worker thread for parallel row processing without per-frame thread spawning
    std::thread workerThread;
    std::mutex workerMtx;
    std::condition_variable workerCv;
    std::condition_variable doneCv;
    bool workerRunning = false;
    bool workerTaskReady = false;
    bool workerTaskDone = true;

    // Task parameters for worker thread
    const uint16_t* taskSrc = nullptr;
    uint8_t* taskDst = nullptr;
    int taskWidth = 0;
    int taskStartY = 0;
    int taskEndY = 0;
    int taskSrcStrideBytes = 0;
    int taskDstStrideBytes = 0;
    bool taskIsHlg = false;

    static inline float hableCurve(float x) {
        const float A = 0.15f;
        const float B = 0.50f;
        const float C = 0.10f;
        const float D = 0.20f;
        const float E = 0.02f;
        const float F = 0.30f;
        return ((x * (A * x + C * B) + D * E) / (x * (A * x + B) + D * F)) - (E / F);
    }

    void processRowRange(const uint16_t* src, uint8_t* dst, int width, int startY, int endY,
                         int srcStrideBytes, int dstStrideBytes, bool isHlg) {
        const float* eotf = isHlg ? hlgToLinear : pqToLinear;

        // BT.2020 -> BT.709 Gamut Transformation Matrix
        const float c00 =  1.6605f, c01 = -0.5876f, c02 = -0.0728f;
        const float c10 = -0.1246f, c11 =  1.1329f, c12 = -0.0083f;
        const float c20 = -0.0182f, c21 = -0.1006f, c22 =  1.1187f;

        const float exposure = isHlg ? 3.8f : 14.0f;

        for (int y = startY; y < endY; y++) {
            const uint16_t* rowSrc = reinterpret_cast<const uint16_t*>(reinterpret_cast<const uint8_t*>(src) + y * srcStrideBytes);
            uint8_t* rowDst = dst + y * dstStrideBytes;

            for (int x = 0; x < width; x++) {
                int rIdx = std::clamp(static_cast<int>(rowSrc[x * 4 + 0] >> 6), 0, 1023);
                int gIdx = std::clamp(static_cast<int>(rowSrc[x * 4 + 1] >> 6), 0, 1023);
                int bIdx = std::clamp(static_cast<int>(rowSrc[x * 4 + 2] >> 6), 0, 1023);

                float rLin = eotf[rIdx];
                float gLin = eotf[gIdx];
                float bLin = eotf[bIdx];

                // BT.2020 -> BT.709 with exposure scaling
                float r709 = std::max(0.0f, c00 * rLin + c01 * gLin + c02 * bLin) * exposure;
                float g709 = std::max(0.0f, c10 * rLin + c11 * gLin + c12 * bLin) * exposure;
                float b709 = std::max(0.0f, c20 * rLin + c21 * gLin + c22 * bLin) * exposure;

                int rLutIdx = std::clamp(static_cast<int>((r709 * 0.25f) * 4095.0f), 0, 4095);
                int gLutIdx = std::clamp(static_cast<int>((g709 * 0.25f) * 4095.0f), 0, 4095);
                int bLutIdx = std::clamp(static_cast<int>((b709 * 0.25f) * 4095.0f), 0, 4095);

                rowDst[x * 4 + 0] = linearToSdr[rLutIdx];
                rowDst[x * 4 + 1] = linearToSdr[gLutIdx];
                rowDst[x * 4 + 2] = linearToSdr[bLutIdx];
                rowDst[x * 4 + 3] = 255;
            }
        }
    }

    void workerLoop() {
        while (true) {
            std::unique_lock<std::mutex> lk(workerMtx);
            workerCv.wait(lk, [this] { return !workerRunning || workerTaskReady; });
            if (!workerRunning) break;

            processRowRange(taskSrc, taskDst, taskWidth, taskStartY, taskEndY,
                            taskSrcStrideBytes, taskDstStrideBytes, taskIsHlg);

            workerTaskReady = false;
            workerTaskDone = true;
            doneCv.notify_one();
        }
    }

public:
    HdrToneMapper() = default;

    ~HdrToneMapper() {
        {
            std::lock_guard<std::mutex> lk(workerMtx);
            workerRunning = false;
            workerCv.notify_all();
        }
        if (workerThread.joinable()) {
            workerThread.join();
        }
    }

    void init() {
        if (initialized) return;

        // 1. PQ (SMPTE ST 2084) EOTF LUT (maps 10-bit non-linear code [0..1023] to normalized scene luminance)
        const double m1 = 2610.0 / 16384.0;
        const double m2 = (2523.0 / 4096.0) * 128.0;
        const double c1 = 3424.0 / 4096.0;
        const double c2 = (2413.0 / 4096.0) * 32.0;
        const double c3 = (2392.0 / 4096.0) * 32.0;

        for (int i = 0; i < 1024; i++) {
            double N = static_cast<double>(i) / 1023.0;
            double N_inv_m2 = std::pow(N, 1.0 / m2);
            double num = std::max(N_inv_m2 - c1, 0.0);
            double den = c2 - c3 * N_inv_m2;
            double L = (den > 0.0 && num > 0.0) ? std::pow(num / den, 1.0 / m1) : 0.0;
            pqToLinear[i] = static_cast<float>(L);
        }

        // 2. HLG (ARIB STD-B67) EOTF LUT
        for (int i = 0; i < 1024; i++) {
            double N = static_cast<double>(i) / 1023.0;
            double L;
            if (N <= 0.5) {
                L = (N * N) / 3.0;
            } else {
                L = (std::exp((N - 0.55991073) / 0.17883277) + 0.28466892) / 12.0;
            }
            hlgToLinear[i] = static_cast<float>(L);
        }

        // 3. Linear-to-SDR LUT (Hable Filmic Tone-Curve + sRGB Gamma ~2.2)
        const float whitePoint = hableCurve(11.2f);
        for (int i = 0; i < 4096; i++) {
            float lin = (static_cast<float>(i) / 4095.0f) * 4.0f;
            float mapped = hableCurve(lin * 2.2f) / whitePoint;
            mapped = std::clamp(mapped, 0.0f, 1.0f);
            float sdr = std::pow(mapped, 1.0f / 2.2f);
            int val = static_cast<int>(std::round(sdr * 255.0f));
            linearToSdr[i] = static_cast<uint8_t>(std::clamp(val, 0, 255));
        }

        workerRunning = true;
        workerThread = std::thread(&HdrToneMapper::workerLoop, this);
        initialized = true;
    }

    void toneMapRgba64ToRgba8(const uint16_t* src, uint8_t* dst, int width, int height, int srcStrideBytes, int dstStrideBytes, bool isHlg) {
        init();

        if (height >= 480 && workerRunning) {
            int mid = height / 2;
            {
                std::lock_guard<std::mutex> lk(workerMtx);
                taskSrc = src;
                taskDst = dst;
                taskWidth = width;
                taskStartY = 0;
                taskEndY = mid;
                taskSrcStrideBytes = srcStrideBytes;
                taskDstStrideBytes = dstStrideBytes;
                taskIsHlg = isHlg;
                workerTaskDone = false;
                workerTaskReady = true;
                workerCv.notify_one();
            }

            // Process lower half on calling thread
            processRowRange(src, dst, width, mid, height, srcStrideBytes, dstStrideBytes, isHlg);

            // Wait for worker thread to complete upper half
            std::unique_lock<std::mutex> lk(workerMtx);
            doneCv.wait(lk, [this] { return workerTaskDone; });
        } else {
            processRowRange(src, dst, width, 0, height, srcStrideBytes, dstStrideBytes, isHlg);
        }
    }
};
