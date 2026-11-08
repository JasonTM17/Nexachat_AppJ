package com.nexachat.feature.chat.voice;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import androidx.annotation.NonNull;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Opens an audio track and exposes decoder output incrementally as PCM16 samples. */
public final class MediaCodecPcmDecoder {

    private static final long CODEC_TIMEOUT_US = 10_000L;

    private MediaCodecPcmDecoder() {
    }

    @NonNull
    public static WaveformExtractor.PcmStream open(@NonNull File file) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int audioTrack = -1;
            MediaFormat inputFormat = null;
            for (int index = 0; index < extractor.getTrackCount(); index++) {
                MediaFormat candidate = extractor.getTrackFormat(index);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = index;
                    inputFormat = candidate;
                    break;
                }
            }
            if (audioTrack < 0 || inputFormat == null) {
                throw new IOException("Audio track not found");
            }
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            int sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            long durationUs = inputFormat.containsKey(MediaFormat.KEY_DURATION)
                    ? inputFormat.getLong(MediaFormat.KEY_DURATION)
                    : 0L;
            if (mime == null || sampleRate <= 0 || channels <= 0 || durationUs <= 0L) {
                throw new IOException("Audio format is incomplete");
            }

            extractor.selectTrack(audioTrack);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(inputFormat, null, null, 0);
            codec.start();
            return new DecoderStream(extractor, codec, durationUs, sampleRate, channels);
        } catch (Exception exception) {
            if (codec != null) {
                try {
                    codec.release();
                } catch (RuntimeException ignored) {
                    // Preserve the original media failure.
                }
            }
            extractor.release();
            if (exception instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Could not open audio decoder", exception);
        }
    }

    private static final class DecoderStream implements WaveformExtractor.PcmStream {
        private final MediaExtractor extractor;
        private final MediaCodec codec;
        private final long durationUs;
        private final int sampleRateHz;
        private final int channelCount;
        private final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        private boolean inputDone;
        private boolean outputDone;
        private boolean currentOutputEos;
        private int eosWaitCount;
        private int currentOutputIndex = -1;
        private ByteBuffer currentOutput;

        DecoderStream(
                MediaExtractor extractor,
                MediaCodec codec,
                long durationUs,
                int sampleRateHz,
                int channelCount) {
            this.extractor = extractor;
            this.codec = codec;
            this.durationUs = durationUs;
            this.sampleRateHz = sampleRateHz;
            this.channelCount = channelCount;
        }

        @Override public long durationUs() { return durationUs; }
        @Override public int sampleRateHz() { return sampleRateHz; }
        @Override public int channelCount() { return channelCount; }

        @Override
        public int read(@NonNull short[] target) throws IOException {
            int written = 0;
            while (written < target.length) {
                if (currentOutput == null && !acquireOutput()) {
                    break;
                }
                while (currentOutput != null && currentOutput.remaining() >= Short.BYTES
                        && written < target.length) {
                    target[written++] = currentOutput.getShort();
                }
                if (currentOutput != null && !currentOutput.hasRemaining()) {
                    releaseCurrentOutput();
                }
            }
            return written == 0 && outputDone ? -1 : written;
        }

        private boolean acquireOutput() throws IOException {
            while (!outputDone) {
                if (!inputDone) {
                    feedInput();
                }
                int index;
                try {
                    index = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US);
                } catch (IllegalStateException exception) {
                    outputDone = true;
                    return false;
                }
                if (index >= 0) {
                    ByteBuffer output = codec.getOutputBuffer(index);
                    if (output == null) {
                        codec.releaseOutputBuffer(index, false);
                        continue;
                    }
                    output.position(bufferInfo.offset);
                    output.limit(bufferInfo.offset + bufferInfo.size);
                    currentOutputIndex = index;
                    currentOutputEos = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    eosWaitCount = 0;
                    currentOutput = output.slice().order(ByteOrder.nativeOrder());
                    if (!currentOutput.hasRemaining()) {
                        releaseCurrentOutput();
                    } else {
                        return true;
                    }
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // The decoder has accepted the configured PCM output format.
                } else if (index == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    if (++eosWaitCount > 500) {
                        throw new IOException("Audio decoder did not finish");
                    }
                }
            }
            return false;
        }

        private void feedInput() {
            int inputIndex;
            try {
                inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
            } catch (IllegalStateException exception) {
                inputDone = true;
                return;
            }
            if (inputIndex < 0) {
                return;
            }
            ByteBuffer input = codec.getInputBuffer(inputIndex);
            if (input == null) {
                codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                inputDone = true;
                return;
            }
            input.clear();
            long sampleTimeUs = extractor.getSampleTime();
            if (sampleTimeUs < 0L || sampleTimeUs >= WaveformExtractor.MAX_DURATION_US) {
                codec.queueInputBuffer(inputIndex, 0, 0, Math.max(sampleTimeUs, 0L),
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                inputDone = true;
                return;
            }
            int size = extractor.readSampleData(input, 0);
            if (size < 0) {
                codec.queueInputBuffer(inputIndex, 0, 0, Math.max(sampleTimeUs, 0L),
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                inputDone = true;
                return;
            }
            codec.queueInputBuffer(inputIndex, 0, size, sampleTimeUs, 0);
            extractor.advance();
        }

        private void releaseCurrentOutput() {
            if (currentOutputIndex >= 0) {
                codec.releaseOutputBuffer(currentOutputIndex, false);
            }
            currentOutputIndex = -1;
            currentOutput = null;
            if (currentOutputEos) {
                outputDone = true;
            }
            currentOutputEos = false;
        }

        @Override
        public void close() {
            if (currentOutputIndex >= 0) {
                try {
                    codec.releaseOutputBuffer(currentOutputIndex, false);
                } catch (RuntimeException ignored) {
                    // The codec may already have entered its terminal state.
                }
            }
            try {
                codec.stop();
            } catch (RuntimeException ignored) {
                // A failed decode still releases both native resources below.
            } finally {
                try {
                    codec.release();
                } finally {
                    extractor.release();
                }
            }
        }
    }
}
