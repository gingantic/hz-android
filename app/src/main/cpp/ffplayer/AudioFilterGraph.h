#pragma once
#include "PlayerCommon.h"

#include <cstdio>
#include <cmath>

// ─── Audio Filtergraph (Tempo / Pitch Scaling) ──────────────────────────────

struct AudioFilterGraph {
    AVFilterGraph* graph = nullptr;
    AVFilterContext* srcCtx = nullptr;
    AVFilterContext* sinkCtx = nullptr;
    float currentSpeed = 1.0f;
    int sampleRate = 0;
    int channels = 0;
    AVSampleFormat sampleFmt = AV_SAMPLE_FMT_NONE;

    void release() {
        if (graph) {
            avfilter_graph_free(&graph);
            graph = nullptr;
            srcCtx = nullptr;
            sinkCtx = nullptr;
        }
    }

    bool init(int inSampleRate, int inChannels, const AVChannelLayout* inLayout, AVSampleFormat inSampleFmt, float speed) {
        release();

        if (speed <= 0.0f) speed = 1.0f;
        currentSpeed = speed;
        sampleRate = inSampleRate;
        channels = inChannels;
        sampleFmt = inSampleFmt;

        if (std::abs(speed - 1.0f) < 0.005f) {
            return true;
        }

        graph = avfilter_graph_alloc();
        if (!graph) return false;

        const AVFilter* abuffer = avfilter_get_by_name("abuffer");
        const AVFilter* abuffersink = avfilter_get_by_name("abuffersink");
        if (!abuffer || !abuffersink) {
            release();
            return false;
        }

        char chLayoutStr[64] = {0};
        if (inLayout) {
            av_channel_layout_describe(inLayout, chLayoutStr, sizeof(chLayoutStr));
        }
        if (chLayoutStr[0] == '\0') {
            if (inChannels == 1) snprintf(chLayoutStr, sizeof(chLayoutStr), "mono");
            else if (inChannels == 6) snprintf(chLayoutStr, sizeof(chLayoutStr), "5.1");
            else if (inChannels == 8) snprintf(chLayoutStr, sizeof(chLayoutStr), "7.1");
            else snprintf(chLayoutStr, sizeof(chLayoutStr), "stereo");
        }

        char args[256];
        snprintf(args, sizeof(args),
                 "time_base=1/%d:sample_rate=%d:sample_fmt=%s:channel_layout=%s",
                 inSampleRate, inSampleRate, av_get_sample_fmt_name(inSampleFmt), chLayoutStr);

        int ret = avfilter_graph_create_filter(&srcCtx, abuffer, "in", args, nullptr, graph);
        if (ret < 0) {
            release();
            return false;
        }

        ret = avfilter_graph_create_filter(&sinkCtx, abuffersink, "out", nullptr, nullptr, graph);
        if (ret < 0) {
            release();
            return false;
        }

        // Build chained atempo filter (atempo accepts [0.5, 2.0] per stage)
        std::string filterStr = "";
        float tempSpeed = speed;
        while (tempSpeed > 2.0f) {
            filterStr += "atempo=2.0,";
            tempSpeed /= 2.0f;
        }
        while (tempSpeed < 0.5f) {
            filterStr += "atempo=0.5,";
            tempSpeed /= 0.5f;
        }
        char tempoBuf[32];
        snprintf(tempoBuf, sizeof(tempoBuf), "atempo=%.4f", tempSpeed);
        filterStr += tempoBuf;

        AVFilterInOut* outputs = avfilter_inout_alloc();
        AVFilterInOut* inputs  = avfilter_inout_alloc();
        if (!outputs || !inputs) {
            if (outputs) avfilter_inout_free(&outputs);
            if (inputs) avfilter_inout_free(&inputs);
            release();
            return false;
        }

        outputs->name       = av_strdup("in");
        outputs->filter_ctx = srcCtx;
        outputs->pad_idx    = 0;
        outputs->next       = nullptr;

        inputs->name        = av_strdup("out");
        inputs->filter_ctx  = sinkCtx;
        inputs->pad_idx     = 0;
        inputs->next        = nullptr;

        ret = avfilter_graph_parse_ptr(graph, filterStr.c_str(), &inputs, &outputs, nullptr);
        avfilter_inout_free(&inputs);
        avfilter_inout_free(&outputs);

        if (ret < 0) {
            release();
            return false;
        }

        ret = avfilter_graph_config(graph, nullptr);
        if (ret < 0) {
            release();
            return false;
        }

        LOGI("AudioFilterGraph: Initialized for speed %.2f (%s)", speed, filterStr.c_str());
        return true;
    }
};
