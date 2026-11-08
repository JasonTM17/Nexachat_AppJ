package com.nexachat.feature.chat.voice;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.exoplayer.ExoPlayer;
import java.io.File;
import java.util.Objects;

/** Media3 adapter; audio focus is requested explicitly by the chat controller. */
final class Media3VoicePlayer implements VoicePlaybackController.Player {

    private final ExoPlayer exoPlayer;
    @Nullable private Listener listener;

    private final androidx.media3.common.Player.Listener mediaListener =
            new androidx.media3.common.Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int playbackState) {
                    Listener current = listener;
                    if (current == null) {
                        return;
                    }
                    if (playbackState == androidx.media3.common.Player.STATE_READY) {
                        current.onReady(durationMs());
                    } else if (playbackState == androidx.media3.common.Player.STATE_ENDED) {
                        current.onEnded();
                    }
                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    Listener current = listener;
                    if (current != null) {
                        current.onPlayingChanged(isPlaying);
                    }
                }

                @Override
                public void onPlayerError(@NonNull PlaybackException error) {
                    Listener current = listener;
                    if (current != null) {
                        current.onFailure(error);
                    }
                }
            };

    Media3VoicePlayer(@NonNull Context context) {
        exoPlayer = new ExoPlayer.Builder(context.getApplicationContext()).build();
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build();
        exoPlayer.setAudioAttributes(attributes, false);
        exoPlayer.addListener(mediaListener);
    }

    @Override
    public void setListener(@NonNull Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public void prepareAndPlay(@NonNull File file) {
        exoPlayer.stop();
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));
        exoPlayer.prepare();
        exoPlayer.play();
    }

    @Override public void play() { exoPlayer.play(); }
    @Override public void pause() { exoPlayer.pause(); }
    @Override public void seekTo(long positionMs) { exoPlayer.seekTo(Math.max(0L, positionMs)); }
    @Override public long positionMs() { return Math.max(0L, exoPlayer.getCurrentPosition()); }

    @Override
    public long durationMs() {
        long duration = exoPlayer.getDuration();
        return duration == C.TIME_UNSET || duration < 0L ? 0L : duration;
    }

    @Override public void stop() { exoPlayer.stop(); }

    @Override
    public void release() {
        listener = null;
        exoPlayer.removeListener(mediaListener);
        exoPlayer.release();
    }
}
