/*
 * Copyright 2021 The Android Open Source Project
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

package androidx.media3.transformer;

import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.common.util.Assertions.checkState;
import static androidx.media3.decoder.DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED;
import static java.lang.Math.min;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.AudioProcessor.AudioFormat;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import java.nio.ByteBuffer;
import org.checkerframework.dataflow.qual.Pure;

/** Processes, encodes and muxes raw audio samples. */
/* package */ final class AudioSampleExporter extends SampleExporter {

  private static final int DEFAULT_ENCODER_BITRATE = 128 * 1024;

  private final Codec encoder;
  private final AudioFormat encoderInputAudioFormat;
  private final DecoderInputBuffer encoderInputBuffer;
  private final DecoderInputBuffer encoderOutputBuffer;
  private final AudioGraph audioGraph;

  private long encoderTotalInputBytes;

  public AudioSampleExporter(
      Format firstAssetLoaderInputFormat,
      Format firstExporterInputFormat,
      TransformationRequest transformationRequest,
      EditedMediaItem firstEditedMediaItem,
      Codec.EncoderFactory encoderFactory,
      MuxerWrapper muxerWrapper,
      FallbackListener fallbackListener)
      throws ExportException {
    super(firstAssetLoaderInputFormat, muxerWrapper);
    checkArgument(firstExporterInputFormat.pcmEncoding != Format.NO_VALUE);

    try {
      audioGraph = new AudioGraph(firstExporterInputFormat, firstEditedMediaItem);
    } catch (AudioProcessor.UnhandledAudioFormatException e) {
      throw ExportException.createForAudioProcessing(e, "AudioGraph initialization");
    }
    encoderInputAudioFormat = audioGraph.getOutputAudioFormat();
    checkState(!encoderInputAudioFormat.equals(AudioFormat.NOT_SET));

    Format requestedEncoderFormat =
        new Format.Builder()
            .setSampleMimeType(
                transformationRequest.audioMimeType != null
                    ? transformationRequest.audioMimeType
                    : checkNotNull(firstAssetLoaderInputFormat.sampleMimeType))
            .setSampleRate(encoderInputAudioFormat.sampleRate)
            .setChannelCount(encoderInputAudioFormat.channelCount)
            .setPcmEncoding(encoderInputAudioFormat.encoding)
            .setAverageBitrate(DEFAULT_ENCODER_BITRATE)
            .build();

    encoder =
        encoderFactory.createForAudioEncoding(
            requestedEncoderFormat
                .buildUpon()
                .setSampleMimeType(
                    findSupportedMimeTypeForEncoderAndMuxer(
                        requestedEncoderFormat,
                        muxerWrapper.getSupportedSampleMimeTypes(C.TRACK_TYPE_AUDIO)))
                .build());
    encoderInputBuffer = new DecoderInputBuffer(BUFFER_REPLACEMENT_MODE_DISABLED);
    encoderOutputBuffer = new DecoderInputBuffer(BUFFER_REPLACEMENT_MODE_DISABLED);

    fallbackListener.onTransformationRequestFinalized(
        createFallbackTransformationRequest(
            transformationRequest,
            requestedEncoderFormat,
            /* actualFormat= */ encoder.getConfigurationFormat()));
  }

  @Override
  public GraphInput getInput() {
    return audioGraph;
  }

  @Override
  public void release() {
    audioGraph.release();
    encoder.release();
  }

  @Override
  protected boolean processDataUpToMuxer() throws ExportException {

    // Returns same buffer until consumed. getOutput internally progresses underlying input data.
    ByteBuffer audioGraphBuffer = audioGraph.getOutput();

    if (!encoder.maybeDequeueInputBuffer(encoderInputBuffer)) {
      return false;
    }

    if (audioGraph.isEnded()) {
      queueEndOfStreamToEncoder();
      return false;
    }

    if (!audioGraphBuffer.hasRemaining()) {
      return false;
    }

    feedEncoder(audioGraphBuffer);
    return true;
  }

  @Override
  @Nullable
  protected Format getMuxerInputFormat() throws ExportException {
    return encoder.getOutputFormat();
  }

  @Override
  @Nullable
  protected DecoderInputBuffer getMuxerInputBuffer() throws ExportException {
    encoderOutputBuffer.data = encoder.getOutputBuffer();
    if (encoderOutputBuffer.data == null) {
      return null;
    }
    encoderOutputBuffer.timeUs = checkNotNull(encoder.getOutputBufferInfo()).presentationTimeUs;
    encoderOutputBuffer.setFlags(C.BUFFER_FLAG_KEY_FRAME);
    return encoderOutputBuffer;
  }

  @Override
  protected void releaseMuxerInputBuffer() throws ExportException {
    encoder.releaseOutputBuffer(/* render= */ false);
  }

  @Override
  protected boolean isMuxerInputEnded() {
    return encoder.isEnded();
  }

  /**
   * Feeds as much data as possible between the current position and limit of the specified {@link
   * ByteBuffer} to the encoder, and advances its position by the number of bytes fed.
   */
  private void feedEncoder(ByteBuffer inputBuffer) throws ExportException {
    ByteBuffer encoderInputBufferData = checkNotNull(encoderInputBuffer.data);
    int bufferLimit = inputBuffer.limit();
    inputBuffer.limit(min(bufferLimit, inputBuffer.position() + encoderInputBufferData.capacity()));
    encoderInputBufferData.put(inputBuffer);
    encoderInputBuffer.timeUs = getOutputAudioDurationUs();
    encoderTotalInputBytes += encoderInputBufferData.position();
    encoderInputBuffer.setFlags(0);
    encoderInputBuffer.flip();
    inputBuffer.limit(bufferLimit);
    encoder.queueInputBuffer(encoderInputBuffer);
  }

  private void queueEndOfStreamToEncoder() throws ExportException {
    checkState(checkNotNull(encoderInputBuffer.data).position() == 0);
    encoderInputBuffer.timeUs = getOutputAudioDurationUs();
    encoderInputBuffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
    encoderInputBuffer.flip();
    // Queuing EOS should only occur with an empty buffer.
    encoder.queueInputBuffer(encoderInputBuffer);
  }

  @Pure
  private static TransformationRequest createFallbackTransformationRequest(
      TransformationRequest transformationRequest, Format requestedFormat, Format actualFormat) {
    // TODO(b/259570024): Consider including bitrate and other audio characteristics in the revised
    //  fallback design.
    if (Util.areEqual(requestedFormat.sampleMimeType, actualFormat.sampleMimeType)) {
      return transformationRequest;
    }
    return transformationRequest.buildUpon().setAudioMimeType(actualFormat.sampleMimeType).build();
  }

  private long getOutputAudioDurationUs() {
    long totalFramesWritten = encoderTotalInputBytes / encoderInputAudioFormat.bytesPerFrame;
    return (totalFramesWritten * C.MICROS_PER_SECOND) / encoderInputAudioFormat.sampleRate;
  }
}
