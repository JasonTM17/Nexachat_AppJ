package com.nexachat.feature.chat.voice;

import androidx.annotation.NonNull;
import java.util.Arrays;

/** Converts a streamed PCM source into a bounded, normalised 48-bar waveform. */
public final class WaveformExtractor {

    public static final int BAR_COUNT = 48;
    public static final long MAX_DURATION_US = 10L * 60L * 1_000_000L;
    static final int DECODE_BUFFER_SAMPLES = 8192;
    private static final int FALLBACK_BAR = 8;

    /** A decoded PCM stream. Samples are interleaved and reads are frame-aligned. */
    public interface PcmStream extends AutoCloseable {
        long durationUs();
        int sampleRateHz();
        int channelCount();
        int read(@NonNull short[] target) throws Exception;
        @Override void close() throws Exception;
    }

    private WaveformExtractor() {
    }

    /** Failure is represented by a low, flat waveform so corrupt media never breaks binding. */
    @NonNull
    public static byte[] extract(@NonNull PcmStream stream) {
        try (PcmStream source = stream) {
            return extractDecoded(source);
        } catch (Exception exception) {
            return flatBars();
        }
    }

    @NonNull
    static byte[] extractDecoded(@NonNull PcmStream source) throws Exception {
        int sampleRate = source.sampleRateHz();
        int channels = source.channelCount();
        long durationUs = source.durationUs();
        if (sampleRate <= 0 || channels <= 0 || durationUs <= 0) {
            return flatBars();
        }

        long cappedDurationUs = Math.min(durationUs, MAX_DURATION_US);
        long expectedFrames = Math.max(1L, cappedDurationUs * sampleRate / 1_000_000L);
        double[] sums = new double[BAR_COUNT];
        long[] counts = new long[BAR_COUNT];
        short[] samples = new short[DECODE_BUFFER_SAMPLES];
        long framesRead = 0L;

        while (framesRead < expectedFrames) {
            int sampleCount = source.read(samples);
            if (sampleCount <= 0) {
                break;
            }
            int boundedSampleCount = Math.min(sampleCount, samples.length);
            int completeSamples = boundedSampleCount - boundedSampleCount % channels;
            int frameCount = completeSamples / channels;
            for (int frame = 0; frame < frameCount && framesRead < expectedFrames; frame++) {
                int bar = (int) Math.min(BAR_COUNT - 1L,
                        framesRead * BAR_COUNT / expectedFrames);
                int sampleOffset = frame * channels;
                for (int channel = 0; channel < channels; channel++) {
                    double sample = samples[sampleOffset + channel] / 32768.0d;
                    sums[bar] += sample * sample;
                    counts[bar]++;
                }
                framesRead++;
            }
        }

        double maxRms = 0.0d;
        double[] rms = new double[BAR_COUNT];
        for (int index = 0; index < BAR_COUNT; index++) {
            rms[index] = counts[index] == 0L ? 0.0d : Math.sqrt(sums[index] / counts[index]);
            maxRms = Math.max(maxRms, rms[index]);
        }
        byte[] bars = new byte[BAR_COUNT];
        if (maxRms == 0.0d) {
            return bars;
        }
        for (int index = 0; index < BAR_COUNT; index++) {
            int value = (int) Math.round(255.0d * rms[index] / maxRms);
            bars[index] = (byte) Math.max(0, Math.min(255, value));
        }
        return bars;
    }

    /** Extracts from an in-memory fixture; production decoding uses a streaming MediaCodec source. */
    @NonNull
    static byte[] extractPcm(
            @NonNull short[] pcm,
            int sampleRateHz,
            int channelCount,
            long durationUs) {
        return extract(new PcmStream() {
            private int offset;

            @Override public long durationUs() { return durationUs; }
            @Override public int sampleRateHz() { return sampleRateHz; }
            @Override public int channelCount() { return channelCount; }
            @Override public int read(short[] target) {
                int count = Math.min(target.length, pcm.length - offset);
                if (count <= 0) {
                    return -1;
                }
                System.arraycopy(pcm, offset, target, 0, count);
                offset += count;
                return count;
            }
            @Override public void close() { }
        });
    }

    @NonNull
    public static byte[] flatBars() {
        byte[] bars = new byte[BAR_COUNT];
        Arrays.fill(bars, (byte) FALLBACK_BAR);
        return bars;
    }
}
