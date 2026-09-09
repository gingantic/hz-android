#pragma once
#include "PlayerCommon.h"

// ─── Hardware Video Decoder (AMediaCodec Zero-Copy) ─────────────────────────

struct HwVideoDecoder {
    AMediaCodec* codec = nullptr;
    AVBSFContext* bsfCtx = nullptr;
    std::atomic<bool> isConfigured{false};
    std::string codecName;
    int width = 0;
    int height = 0;

    bool init(AVCodecParameters* par, ANativeWindow* window, bool forceSdr = false, int rotationDegrees = 0) {
        release();
        if (!par || !window) return false;

        const char* mime = nullptr;
        const char* bsfName = nullptr;
        if (par->codec_id == AV_CODEC_ID_H264) {
            mime = "video/avc";
            bsfName = "h264_mp4toannexb";
        } else if (par->codec_id == AV_CODEC_ID_HEVC) {
            mime = "video/hevc";
            bsfName = "hevc_mp4toannexb";
        } else if (par->codec_id == AV_CODEC_ID_VP9) {
            mime = "video/x-vnd.on2.vp9";
        } else if (par->codec_id == AV_CODEC_ID_VP8) {
            mime = "video/x-vnd.on2.vp8";
        } else if (par->codec_id == AV_CODEC_ID_AV1) {
            mime = "video/av01";
            bsfName = nullptr; // Demuxer emits complete OBU temporal units; raw frames fed directly to AMediaCodec
        }

        if (!mime) {
            LOGI("HwVideoDecoder: Codec ID %d has no hardware MediaCodec mapping", par->codec_id);
            return false;
        }

        width = par->width;
        height = par->height;
        if (width <= 0 || height <= 0) {
            LOGW("HwVideoDecoder: Invalid dimensions %dx%d", width, height);
            return false;
        }

        codec = AMediaCodec_createDecoderByType(mime);
        if (!codec) {
            LOGW("HwVideoDecoder: Failed to create AMediaCodec for %s", mime);
            return false;
        }

        if (bsfName) {
            const AVBitStreamFilter* bsf = av_bsf_get_by_name(bsfName);
            if (bsf) {
                if (av_bsf_alloc(bsf, &bsfCtx) == 0) {
                    avcodec_parameters_copy(bsfCtx->par_in, par);
                    if (av_bsf_init(bsfCtx) < 0) {
                        av_bsf_free(&bsfCtx);
                        bsfCtx = nullptr;
                    }
                }
            }
        }

        AMediaFormat* format = AMediaFormat_new();
        AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, mime);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, width);
        AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, height);
        if (rotationDegrees != 0) {
            AMediaFormat_setInt32(format, "rotation-degrees", rotationDegrees);
        }

        // Codec Specific Data (CSD)
        if (bsfCtx && bsfCtx->par_out && bsfCtx->par_out->extradata && bsfCtx->par_out->extradata_size > 0) {
            AMediaFormat_setBuffer(format, "csd-0", bsfCtx->par_out->extradata, bsfCtx->par_out->extradata_size);
        } else if (par->extradata && par->extradata_size > 0) {
            AMediaFormat_setBuffer(format, "csd-0", par->extradata, par->extradata_size);
        }

        if (forceSdr) {
            // Force SDR Tone-Mapping (BT.709 with standard gamma transfer curve)
            AMediaFormat_setInt32(format, "color-standard", 1); // COLOR_STANDARD_BT709
            AMediaFormat_setInt32(format, "color-transfer", 3); // COLOR_TRANSFER_SDR_VIDEO
            AMediaFormat_setInt32(format, "color-range", 2);    // COLOR_RANGE_LIMITED
            AMediaFormat_setInt32(format, "hdr-to-sdr-tonemapping", 1);
            AMediaFormat_setInt32(format, "enable-hdr-to-sdr-tonemapping", 1);
            LOGI("HwVideoDecoder: Enabled hardware HDR-to-SDR Tone-Mapping (BT.709)");
        } else {
            // Color & HDR Metadata (only explicitly set when defined to let decoder parse in-band metadata)
            if (par->color_primaries == AVCOL_PRI_BT2020) {
                AMediaFormat_setInt32(format, "color-standard", 6); // BT.2020
            } else if (par->color_primaries == AVCOL_PRI_BT709) {
                AMediaFormat_setInt32(format, "color-standard", 1); // BT.709
            }

            if (par->color_trc == AVCOL_TRC_SMPTE2084) {
                AMediaFormat_setInt32(format, "color-transfer", 6); // ST.2084 (HDR10)
            } else if (par->color_trc == AVCOL_TRC_ARIB_STD_B67) {
                AMediaFormat_setInt32(format, "color-transfer", 7); // HLG
            } else if (par->color_trc == AVCOL_TRC_BT709) {
                AMediaFormat_setInt32(format, "color-transfer", 3); // SDR
            }

            if (par->color_range == AVCOL_RANGE_JPEG) {
                AMediaFormat_setInt32(format, "color-range", 1); // Full
            } else if (par->color_range == AVCOL_RANGE_MPEG) {
                AMediaFormat_setInt32(format, "color-range", 2); // Limited
            }
        }

        media_status_t status = AMediaCodec_configure(codec, format, window, nullptr, 0);
        AMediaFormat_delete(format);

        if (status != AMEDIA_OK) {
            LOGW("HwVideoDecoder: AMediaCodec_configure failed (%d)", status);
            release();
            return false;
        }

        if (AMediaCodec_start(codec) != AMEDIA_OK) {
            LOGW("HwVideoDecoder: AMediaCodec_start failed");
            release();
            return false;
        }

        codecName = std::string("MediaCodec (") + mime + ")";
        isConfigured.store(true);
        LOGI("HwVideoDecoder: Initialized %s for %dx%d (Zero-Copy Surface)", mime, width, height);
        return true;
    }

    bool setOutputSurface(ANativeWindow* window) {
        if (!codec || !isConfigured.load() || !window) return false;
        media_status_t status = AMediaCodec_setOutputSurface(codec, window);
        if (status == AMEDIA_OK) {
            LOGI("HwVideoDecoder: AMediaCodec_setOutputSurface succeeded");
            return true;
        }
        LOGW("HwVideoDecoder: AMediaCodec_setOutputSurface returned %d", status);
        return false;
    }

    void flush() {
        if (codec && isConfigured.load()) {
            AMediaCodec_flush(codec);
        }
        if (bsfCtx) {
            av_bsf_flush(bsfCtx);
        }
    }

    void release() {
        isConfigured.store(false);
        if (codec) {
            AMediaCodec_stop(codec);
            AMediaCodec_delete(codec);
            codec = nullptr;
        }
        if (bsfCtx) {
            av_bsf_free(&bsfCtx);
            bsfCtx = nullptr;
        }
    }
};
