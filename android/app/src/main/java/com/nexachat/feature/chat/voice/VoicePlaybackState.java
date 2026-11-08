package com.nexachat.feature.chat.voice;

import androidx.annotation.NonNull;
import java.util.Arrays;
import java.util.Objects;

/** Immutable state consumed by every visible row for the current attachment. */
public final class VoicePlaybackState {

    public enum Status { IDLE, LOADING, PLAYING, PAUSED, ERROR }

    private final String attachmentId;
    private final Status status;
    private final long positionMs;
    private final long durationMs;
    private final byte[] waveform;

    public VoicePlaybackState(
            @NonNull String attachmentId,
            @NonNull Status status,
            long positionMs,
            long durationMs,
            @NonNull byte[] waveform) {
        this.attachmentId = attachmentId;
        this.status = status;
        this.durationMs = Math.max(0L, durationMs);
        this.positionMs = Math.max(0L, Math.min(positionMs, this.durationMs));
        this.waveform = waveform.clone();
    }

    @NonNull public String getAttachmentId() { return attachmentId; }
    @NonNull public Status getStatus() { return status; }
    public long getPositionMs() { return positionMs; }
    public long getDurationMs() { return durationMs; }
    @NonNull public byte[] getWaveform() { return waveform.clone(); }

    @NonNull
    public VoicePlaybackState with(
            @NonNull Status nextStatus,
            long nextPositionMs,
            long nextDurationMs,
            @NonNull byte[] nextWaveform) {
        return new VoicePlaybackState(
                attachmentId, nextStatus, nextPositionMs, nextDurationMs, nextWaveform);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof VoicePlaybackState that)) {
            return false;
        }
        return attachmentId.equals(that.attachmentId)
                && status == that.status
                && positionMs == that.positionMs
                && durationMs == that.durationMs
                && Arrays.equals(waveform, that.waveform);
    }

    @Override
    public int hashCode() {
        return Objects.hash(attachmentId, status, positionMs, durationMs, Arrays.hashCode(waveform));
    }
}
