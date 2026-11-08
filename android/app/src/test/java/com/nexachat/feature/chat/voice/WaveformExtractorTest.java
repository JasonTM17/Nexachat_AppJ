package com.nexachat.feature.chat.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public final class WaveformExtractorTest {

    private static final int SAMPLE_RATE = 4_800;
    private static final int SAMPLES_PER_BAR = SAMPLE_RATE / WaveformExtractor.BAR_COUNT;

    @Test
    public void silenceProducesFlatZeroBars() {
        byte[] bars = WaveformExtractor.extractPcm(
                new short[SAMPLE_RATE], SAMPLE_RATE, 1, 1_000_000L);

        assertEquals(WaveformExtractor.BAR_COUNT, bars.length);
        for (byte bar : bars) {
            assertEquals(0, bar & 0xff);
        }
    }

    @Test
    public void risingSineAmplitudeProducesMonotoneBars() {
        short[] pcm = new short[SAMPLES_PER_BAR * WaveformExtractor.BAR_COUNT];
        for (int bar = 0; bar < WaveformExtractor.BAR_COUNT; bar++) {
            double amplitude = (bar + 1.0d) / WaveformExtractor.BAR_COUNT;
            for (int sample = 0; sample < SAMPLES_PER_BAR; sample++) {
                double phase = 2.0d * Math.PI * sample / SAMPLES_PER_BAR;
                pcm[bar * SAMPLES_PER_BAR + sample] = (short) Math.round(
                        Math.sin(phase) * amplitude * Short.MAX_VALUE);
            }
        }

        byte[] bars = WaveformExtractor.extractPcm(
                pcm, SAMPLE_RATE, 1, 1_000_000L);

        for (int index = 1; index < bars.length; index++) {
            assertTrue((bars[index] & 0xff) >= (bars[index - 1] & 0xff));
        }
        assertEquals(255, bars[bars.length - 1] & 0xff);
    }

    @Test
    public void clippingNeverEscapesUnsignedByteRange() {
        short[] pcm = new short[SAMPLES_PER_BAR * WaveformExtractor.BAR_COUNT];
        Arrays.fill(pcm, Short.MIN_VALUE);

        byte[] bars = WaveformExtractor.extractPcm(
                pcm, SAMPLE_RATE, 1, 1_000_000L);

        for (byte bar : bars) {
            assertEquals(255, bar & 0xff);
        }
    }

    @Test
    public void emptyInputReturnsFlatFallback() {
        byte[] bars = WaveformExtractor.extractPcm(new short[0], SAMPLE_RATE, 1, 0L);

        assertEquals(WaveformExtractor.BAR_COUNT, bars.length);
        for (byte bar : bars) {
            assertEquals(8, bar & 0xff);
        }
    }

    @Test
    public void longInputIsCappedAtTenMinutesWithFixedReadBuffer() {
        CountingStream source = new CountingStream();

        byte[] bars = WaveformExtractor.extract(source);

        assertEquals(WaveformExtractor.BAR_COUNT, bars.length);
        assertEquals(600_000L, source.framesRead);
        assertTrue(source.largestRead <= WaveformExtractor.DECODE_BUFFER_SAMPLES);
        assertTrue(source.closed);
    }

    private static final class CountingStream implements WaveformExtractor.PcmStream {
        private long framesRead;
        private int largestRead;
        private boolean closed;

        @Override public long durationUs() { return 20L * 60L * 1_000_000L; }
        @Override public int sampleRateHz() { return 1_000; }
        @Override public int channelCount() { return 1; }

        @Override
        public int read(short[] target) {
            largestRead = Math.max(largestRead, target.length);
            int count = (int) Math.min(target.length, 600_000L - framesRead);
            Arrays.fill(target, 0, count, (short) 10_000);
            framesRead += count;
            return count == 0 ? -1 : count;
        }

        @Override public void close() { closed = true; }
    }
}
