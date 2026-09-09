#pragma once
#include "PlayerCommon.h"

#include <cmath>

// ─── Native 10-Band Graphic Equalizer DSP (RBJ Biquad Filter Chain) ──────────

struct NativeEqualizer {
    static constexpr int BAND_COUNT = 10;
    static constexpr float CENTER_FREQUENCIES[BAND_COUNT] = {
        31.25f, 62.5f, 125.0f, 250.0f, 500.0f, 1000.0f, 2000.0f, 4000.0f, 8000.0f, 16000.0f
    };
    static constexpr float Q = 1.41421356f; // 1-octave bandwidth

    struct Coeffs {
        float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;
        bool isFlat = true;
    };

    struct FilterState {
        float x1 = 0.0f, x2 = 0.0f, y1 = 0.0f, y2 = 0.0f;
    };

    std::atomic<bool> enabled{false};
    std::mutex mutex;
    int currentSampleRate = 48000;
    int currentChannels = 2;
    int gainsMb[BAND_COUNT] = {0};
    Coeffs coeffs[BAND_COUNT];
    std::vector<std::vector<FilterState>> channelStates; // [channel][band]

    NativeEqualizer() {
        channelStates.resize(2, std::vector<FilterState>(BAND_COUNT));
    }

    void init(int sampleRate, int channels) {
        std::lock_guard<std::mutex> lock(mutex);
        currentSampleRate = (sampleRate > 0) ? sampleRate : 48000;
        currentChannels = (channels > 0) ? channels : 2;
        channelStates.resize(currentChannels, std::vector<FilterState>(BAND_COUNT));
        for (auto& ch : channelStates) {
            for (auto& st : ch) {
                st = FilterState{};
            }
        }
        recalculateCoefficientsLocked();
    }

    void setGains(bool isEnabled, const int* inGainsMb, int count) {
        std::lock_guard<std::mutex> lock(mutex);
        enabled.store(isEnabled);
        if (inGainsMb && count > 0) {
            for (int i = 0; i < BAND_COUNT && i < count; i++) {
                gainsMb[i] = std::clamp(inGainsMb[i], -1500, 1500); // ±15 dB
            }
        }
        recalculateCoefficientsLocked();
    }

    void recalculateCoefficientsLocked() {
        float Fs = static_cast<float>(currentSampleRate);
        for (int i = 0; i < BAND_COUNT; i++) {
            int gainMb = gainsMb[i];
            if (gainMb == 0) {
                coeffs[i] = Coeffs{};
                continue;
            }

            float f0 = CENTER_FREQUENCIES[i];
            if (f0 >= Fs * 0.49f) {
                coeffs[i] = Coeffs{};
                continue;
            }

            float A = std::pow(10.0f, static_cast<float>(gainMb) / 4000.0f);
            float w0 = 2.0f * 3.14159265358979323846f * f0 / Fs;
            float cosW = std::cos(w0);
            float sinW = std::sin(w0);
            float alpha = sinW / (2.0f * Q);

            float b0 = 1.0f + alpha * A;
            float b1 = -2.0f * cosW;
            float b2 = 1.0f - alpha * A;
            float a0 = 1.0f + alpha / A;
            float a1 = -2.0f * cosW;
            float a2 = 1.0f - alpha / A;

            float invA0 = 1.0f / a0;
            coeffs[i].b0 = b0 * invA0;
            coeffs[i].b1 = b1 * invA0;
            coeffs[i].b2 = b2 * invA0;
            coeffs[i].a1 = a1 * invA0;
            coeffs[i].a2 = a2 * invA0;
            coeffs[i].isFlat = false;
        }
    }

    void process(float* pcm, int numFrames, int channels) {
        if (!enabled.load() || !pcm || numFrames <= 0 || channels <= 0) return;
        std::lock_guard<std::mutex> lock(mutex);

        if (channelStates.size() < static_cast<size_t>(channels)) {
            channelStates.resize(channels, std::vector<FilterState>(BAND_COUNT));
        }

        bool hasActiveBand = false;
        for (int i = 0; i < BAND_COUNT; i++) {
            if (!coeffs[i].isFlat) {
                hasActiveBand = true;
                break;
            }
        }
        if (!hasActiveBand) return;

        for (int f = 0; f < numFrames; f++) {
            for (int ch = 0; ch < channels; ch++) {
                int idx = f * channels + ch;
                float sample = pcm[idx];

                auto& states = channelStates[ch];
                for (int b = 0; b < BAND_COUNT; b++) {
                    const auto& c = coeffs[b];
                    if (c.isFlat) continue;

                    auto& st = states[b];
                    float y = c.b0 * sample + c.b1 * st.x1 + c.b2 * st.x2 - c.a1 * st.y1 - c.a2 * st.y2;
                    st.x2 = st.x1;
                    st.x1 = sample;
                    st.y2 = st.y1;
                    st.y1 = y;
                    sample = y;
                }

                pcm[idx] = sample;
            }
        }
    }

    void process(int16_t* pcm, int numFrames, int channels) {
        if (!enabled.load() || !pcm || numFrames <= 0 || channels <= 0) return;
        std::lock_guard<std::mutex> lock(mutex);

        if (channelStates.size() < static_cast<size_t>(channels)) {
            channelStates.resize(channels, std::vector<FilterState>(BAND_COUNT));
        }

        bool hasActiveBand = false;
        for (int i = 0; i < BAND_COUNT; i++) {
            if (!coeffs[i].isFlat) {
                hasActiveBand = true;
                break;
            }
        }
        if (!hasActiveBand) return;

        for (int f = 0; f < numFrames; f++) {
            for (int ch = 0; ch < channels; ch++) {
                int idx = f * channels + ch;
                float sample = static_cast<float>(pcm[idx]);

                auto& states = channelStates[ch];
                for (int b = 0; b < BAND_COUNT; b++) {
                    const auto& c = coeffs[b];
                    if (c.isFlat) continue;

                    auto& st = states[b];
                    float y = c.b0 * sample + c.b1 * st.x1 + c.b2 * st.x2 - c.a1 * st.y1 - c.a2 * st.y2;
                    st.x2 = st.x1;
                    st.x1 = sample;
                    st.y2 = st.y1;
                    st.y1 = y;
                    sample = y;
                }

                if (sample > 32767.0f) sample = 32767.0f;
                else if (sample < -32768.0f) sample = -32768.0f;
                pcm[idx] = static_cast<int16_t>(sample);
            }
        }
    }

    void reset() {
        std::lock_guard<std::mutex> lock(mutex);
        for (auto& ch : channelStates) {
            for (auto& st : ch) {
                st = FilterState{};
            }
        }
    }
};
