/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static com.google.common.base.Preconditions.checkNotNull;

import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import java.nio.ByteBuffer;

/**
 * FFmpeg video decoder for codecs MediaCodec cannot handle (ProRes, DNxHD, MJPEG, CineForm, …).
 *
 * <p>Frames stay native-side: {@link VideoDecoderOutputBuffer#decoderPrivate} carries an
 * {@code AVFrame*} handle that {@link #renderToSurface} converts (swscale → RGBA) and blits into
 * the output {@link Surface} via {@code ANativeWindow}. Modeled on media3's libvpx/dav1d decoders.
 */
@UnstableApi
/* package */ final class FfmpegVideoDecoder
    extends SimpleDecoder<DecoderInputBuffer, VideoDecoderOutputBuffer, FfmpegDecoderException> {

  // LINT.IfChange(video_decode_result)
  private static final int VIDEO_DECODER_SUCCESS = 0;
  private static final int VIDEO_DECODER_ERROR_INVALID_DATA = -1;
  private static final int VIDEO_DECODER_ERROR_OTHER = -2;
  // LINT.ThenChange(../../../../../../../cpp/FfmpegDecoder.cpp)

  private final String codecName;
  private long nativeContext;
  @Nullable private final byte[] extraData;
  private volatile @C.VideoOutputMode int outputMode;
  /** Consecutive invalid-data results; guards against silent infinite buffering. */
  private int consecutiveFailures;
  private static final int MAX_CONSECUTIVE_FAILURES = 60;

  /**
   * Creates an FFmpeg video decoder.
   *
   * @param format The input {@link Format} (must have an FFmpeg-supported sample MIME type).
   * @param numInputBuffers Number of input buffers.
   * @param numOutputBuffers Number of output buffers.
   * @param initialInputBufferSize The initial size of each input buffer, in bytes.
   * @param threads Decoder thread count, or 0 to let FFmpeg decide.
   * @throws FfmpegDecoderException If the decoder could not be initialized.
   */
  public FfmpegVideoDecoder(
      Format format,
      int numInputBuffers,
      int numOutputBuffers,
      int initialInputBufferSize,
      int threads)
      throws FfmpegDecoderException {
    super(
        new DecoderInputBuffer[numInputBuffers], new VideoDecoderOutputBuffer[numOutputBuffers]);
    if (!FfmpegLibrary.isAvailable()) {
      throw new FfmpegDecoderException("Failed to load decoder native libraries.");
    }
    codecName = checkNotNull(FfmpegLibrary.getCodecName(checkNotNull(format.sampleMimeType)));
    extraData = buildExtraData(format.initializationData);
    nativeContext = ffmpegVideoInitialize(codecName, extraData, threads);
    if (nativeContext == 0) {
      throw new FfmpegDecoderException("Failed to initialize decoder: " + codecName);
    }
    setInitialInputBufferSize(initialInputBufferSize);
  }

  @Override
  public String getName() {
    return "ffmpeg" + FfmpegLibrary.getVersion() + "-" + codecName;
  }

  /**
   * Concatenates all initialization data into a single extradata blob. Media3 splits H.264
   * codec config into two entries (SPS in [0], PPS in [1], each Annex-B start-code prefixed,
   * mirroring MediaCodec's csd-0/csd-1) — passing only the first entry leaves FFmpeg without
   * the PPS and it never outputs a frame. FFmpeg parses start-code-prefixed extradata
   * natively, so simple concatenation is the correct form for every codec we whitelist.
   */
  @Nullable
  private static byte[] buildExtraData(java.util.List<byte[]> initializationData) {
    if (initializationData.isEmpty()) {
      return null;
    }
    if (initializationData.size() == 1) {
      return initializationData.get(0);
    }
    int totalLength = 0;
    for (int i = 0; i < initializationData.size(); i++) {
      totalLength += initializationData.get(i).length;
    }
    byte[] extraData = new byte[totalLength];
    int offset = 0;
    for (int i = 0; i < initializationData.size(); i++) {
      byte[] data = initializationData.get(i);
      System.arraycopy(data, 0, extraData, offset, data.length);
      offset += data.length;
    }
    return extraData;
  }

  /** Sets the output mode. Used by {@link FfmpegVideoRenderer#setDecoderOutputMode(int)}. */
  public void setOutputMode(@C.VideoOutputMode int outputMode) {
    this.outputMode = outputMode;
  }

  @Override
  protected DecoderInputBuffer createInputBuffer() {
    return new DecoderInputBuffer(
        DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT,
        FfmpegLibrary.getInputBufferPaddingSize());
  }

  @Override
  protected VideoDecoderOutputBuffer createOutputBuffer() {
    return new VideoDecoderOutputBuffer(this::releaseOutputBuffer);
  }

  @Override
  protected FfmpegDecoderException createUnexpectedDecodeException(Throwable error) {
    return new FfmpegDecoderException("Unexpected decode error", error);
  }

  @Override
  @Nullable
  protected FfmpegDecoderException decode(
      DecoderInputBuffer inputBuffer, VideoDecoderOutputBuffer outputBuffer, boolean reset) {
    if (reset) {
      ffmpegVideoFlush(nativeContext);
    }
    ByteBuffer inputData = Util.castNonNull(inputBuffer.data);
    int inputSize = inputData.limit();
    // timeUs is rewritten from the decoded frame's (reordered) timestamp on success.
    outputBuffer.init(inputBuffer.timeUs, outputMode, /* supplementalData= */ null);
    int result =
        ffmpegVideoDecode(nativeContext, inputData, inputSize, inputBuffer.timeUs, outputBuffer);
    if (result == VIDEO_DECODER_ERROR_OTHER) {
      return new FfmpegDecoderException("Error decoding (see logcat).");
    }
    if (result == VIDEO_DECODER_ERROR_INVALID_DATA) {
      // Non-fatal, matching MediaCodec behavior: no output for this buffer. But if the
      // decoder never produces anything (e.g. bad codec config), fail loudly instead of
      // letting the player sit in BUFFERING forever waiting for a first frame.
      if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
        return new FfmpegDecoderException(
            "Decoder " + codecName + " produced no output for "
                + MAX_CONSECUTIVE_FAILURES + " consecutive buffers.");
      }
      outputBuffer.shouldBeSkipped = true;
      return null;
    }
    consecutiveFailures = 0;
    if (result == VIDEO_DECODER_SUCCESS && outputBuffer.decoderPrivate == 0) {
      // Codec delay (e.g. B-frame reordering): packet consumed, no frame yet.
      outputBuffer.shouldBeSkipped = true;
    }
    return null;
  }

  @Override
  protected void releaseOutputBuffer(VideoDecoderOutputBuffer outputBuffer) {
    // Free the native AVFrame before the buffer is recycled or discarded.
    if (outputBuffer.decoderPrivate != 0) {
      ffmpegVideoReleaseFrame(outputBuffer.decoderPrivate);
      outputBuffer.decoderPrivate = 0;
    }
    super.releaseOutputBuffer(outputBuffer);
  }

  /** Renders the decoded frame carried by {@code outputBuffer} to {@code surface}. */
  public void renderToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
      throws FfmpegDecoderException {
    if (outputBuffer.decoderPrivate == 0) {
      throw new FfmpegDecoderException("Invalid native frame in output buffer.");
    }
    int result = ffmpegVideoRenderFrame(nativeContext, surface, outputBuffer.decoderPrivate);
    if (result == VIDEO_DECODER_ERROR_OTHER) {
      throw new FfmpegDecoderException("Error rendering frame to surface (see logcat).");
    }
  }

  @Override
  public void release() {
    super.release();
    ffmpegVideoRelease(nativeContext);
    nativeContext = 0;
  }

  private native long ffmpegVideoInitialize(
      String codecName, @Nullable byte[] extraData, int threads);

  /**
   * Decodes one packet. On frame output, sets {@code outputBuffer.decoderPrivate} to the native
   * frame handle, updates {@code timeUs} with the frame's reordered timestamp, and calls {@link
   * VideoDecoderOutputBuffer#initForPrivateFrame(int, int)}.
   */
  private native int ffmpegVideoDecode(
      long context,
      ByteBuffer inputData,
      int inputSize,
      long inputTimeUs,
      VideoDecoderOutputBuffer outputBuffer);

  private native int ffmpegVideoRenderFrame(long context, Surface surface, long frameHandle);

  private static native void ffmpegVideoReleaseFrame(long frameHandle);

  private native void ffmpegVideoFlush(long context);

  private native void ffmpegVideoRelease(long context);
}
