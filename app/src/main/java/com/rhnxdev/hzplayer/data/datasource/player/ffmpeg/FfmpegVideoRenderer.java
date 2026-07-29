/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.rhnxdev.hzplayer.data.datasource.player.ffmpeg;

import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_INITIALIZATION_DATA_CHANGED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.DISCARD_REASON_MIME_TYPE_CHANGED;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_NO;
import static androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION;

import android.os.Handler;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.TraceUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.video.DecoderVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import java.util.Objects;

/**
 * Software video fallback renderer decoding through the app's FFmpeg build.
 *
 * <p>Placed after the MediaCodec renderers by {@code HzRenderersFactory}, so hardware decoding
 * always wins when a device decoder exists; this renderer only claims formats FFmpeg can decode
 * (ProRes, DNxHD, MJPEG, CineForm, MPEG-2, Xvid/DivX, VC-1, … see {@link
 * FfmpegLibrary#getCodecName}).
 */
@UnstableApi
public final class FfmpegVideoRenderer extends DecoderVideoRenderer {

  private static final String TAG = "FfmpegVideoRenderer";

  private static final int DEFAULT_NUM_INPUT_BUFFERS = 4;
  private static final int DEFAULT_NUM_OUTPUT_BUFFERS = 4;
  /** Let FFmpeg pick the thread count (frame/slice threading per codec). */
  private static final int DECODER_THREADS_AUTO = 0;

  /** Default input buffer size, sized for 1080p intra-frame codecs (ProRes ≈ 0.9 MB/frame). */
  private static final int DEFAULT_INPUT_BUFFER_SIZE =
      Util.ceilDivide(1920, 64) * Util.ceilDivide(1080, 64) * (64 * 64 * 3 / 2) / 2;

  @Nullable private FfmpegVideoDecoder decoder;

  /**
   * Creates a new instance.
   *
   * @param allowedJoiningTimeMs The maximum duration in milliseconds for which this video renderer
   *     can attempt to seamlessly join an ongoing playback.
   * @param eventHandler A handler to use when delivering events to {@code eventListener}. May be
   *     null if delivery of events is not required.
   * @param eventListener A listener of events. May be null if delivery of events is not required.
   * @param maxDroppedFramesToNotify The maximum number of frames that can be dropped between
   *     invocations of {@link VideoRendererEventListener#onDroppedFrames(int, long)}.
   */
  public FfmpegVideoRenderer(
      long allowedJoiningTimeMs,
      @Nullable Handler eventHandler,
      @Nullable VideoRendererEventListener eventListener,
      int maxDroppedFramesToNotify) {
    super(allowedJoiningTimeMs, eventHandler, eventListener, maxDroppedFramesToNotify);
  }

  @Override
  public String getName() {
    return TAG;
  }

  @Override
  public final @RendererCapabilities.Capabilities int supportsFormat(Format format) {
    @Nullable String mimeType = format.sampleMimeType;
    if (mimeType == null || !MimeTypes.isVideo(mimeType) || !FfmpegLibrary.isAvailable()) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
    }
    if (!FfmpegLibrary.supportsFormat(mimeType)) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE);
    }
    if (format.cryptoType != C.CRYPTO_TYPE_NONE) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM);
    }
    return RendererCapabilities.create(
        C.FORMAT_HANDLED, ADAPTIVE_NOT_SEAMLESS, TUNNELING_NOT_SUPPORTED);
  }

  @Override
  protected FfmpegVideoDecoder createDecoder(Format format, @Nullable CryptoConfig cryptoConfig)
      throws FfmpegDecoderException {
    TraceUtil.beginSection("createFfmpegVideoDecoder");
    int initialInputBufferSize =
        format.maxInputSize != Format.NO_VALUE ? format.maxInputSize : DEFAULT_INPUT_BUFFER_SIZE;
    FfmpegVideoDecoder decoder =
        new FfmpegVideoDecoder(
            format,
            DEFAULT_NUM_INPUT_BUFFERS,
            DEFAULT_NUM_OUTPUT_BUFFERS,
            initialInputBufferSize,
            DECODER_THREADS_AUTO);
    this.decoder = decoder;
    TraceUtil.endSection();
    return decoder;
  }

  @Override
  protected void renderOutputBufferToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
      throws FfmpegDecoderException {
    FfmpegVideoDecoder decoder = this.decoder;
    if (decoder == null) {
      throw new FfmpegDecoderException(
          "Failed to render output buffer to surface: decoder is not initialized.");
    }
    try {
      decoder.renderToSurface(outputBuffer, surface);
    } finally {
      outputBuffer.release();
    }
  }

  @Override
  protected void setDecoderOutputMode(@C.VideoOutputMode int outputMode) {
    if (decoder != null) {
      decoder.setOutputMode(outputMode);
    }
  }

  @Override
  protected DecoderReuseEvaluation canReuseDecoder(
      String decoderName, Format oldFormat, Format newFormat) {
    boolean sameMimeType = Objects.equals(oldFormat.sampleMimeType, newFormat.sampleMimeType);
    // The codec context is opened with the format's extra data, so it can only be kept when that
    // data is unchanged too.
    boolean sameInitData = oldFormat.initializationDataEquals(newFormat);
    boolean canReuse = sameMimeType && sameInitData;
    return new DecoderReuseEvaluation(
        decoderName,
        oldFormat,
        newFormat,
        canReuse ? REUSE_RESULT_YES_WITHOUT_RECONFIGURATION : REUSE_RESULT_NO,
        canReuse
            ? 0
            : (sameMimeType
                ? DISCARD_REASON_INITIALIZATION_DATA_CHANGED
                : DISCARD_REASON_MIME_TYPE_CHANGED));
  }
}
