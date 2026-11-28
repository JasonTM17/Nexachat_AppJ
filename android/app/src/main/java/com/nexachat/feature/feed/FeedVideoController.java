package com.nexachat.feature.feed;

import android.content.Context;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import com.google.android.material.button.MaterialButton;
import com.nexachat.R;
import okhttp3.OkHttpClient;

/**
 * Single-instance Media3 ExoPlayer controller shared across feed video cards.
 * Ensures at most one active video streams at a time, uses authenticated HTTP 206 Range
 * requests via {@link OkHttpMediaDataSource}, and releases cleanly on pause/destroy.
 */
@OptIn(markerClass = UnstableApi.class)
public final class FeedVideoController {

    private static final class ActiveSlot {
        final Object holderKey;
        final PlayerView playerView;
        final ImageButton playPause;
        final ImageButton mute;
        final ProgressBar buffering;
        final View errorOverlay;

        ActiveSlot(
                Object holderKey,
                PlayerView playerView,
                ImageButton playPause,
                ImageButton mute,
                ProgressBar buffering,
                View errorOverlay) {
            this.holderKey = holderKey;
            this.playerView = playerView;
            this.playPause = playPause;
            this.mute = mute;
            this.buffering = buffering;
            this.errorOverlay = errorOverlay;
        }
    }

    interface PlayerGateway {
        @Nullable Player asMedia3Player();
        void setMediaUrl(@NonNull String url);
        void setVolume(float volume);
        void setPlayWhenReady(boolean playWhenReady);
        void prepare();
        void play();
        void pause();
        void stop();
        void clearMediaItems();
        void seekTo(long positionMs);
        boolean isPlaying();
        int getPlaybackState();
        void release();
    }

    interface PlayerFactory {
        @NonNull PlayerGateway create(@NonNull Player.Listener listener);
    }

    private final Context appContext;
    private final PlayerFactory playerFactory;
    @Nullable private PlayerGateway player;
    @Nullable private String activePostId;
    @Nullable private ActiveSlot activeSlot;
    private boolean muted;
    private boolean hasError;

    public FeedVideoController(@NonNull Context context, @NonNull OkHttpClient okHttpClient) {
        this(context.getApplicationContext(), createExoPlayerFactory(context.getApplicationContext(), okHttpClient));
    }

    @VisibleForTesting
    FeedVideoController(@NonNull Context context, @NonNull PlayerFactory playerFactory) {
        Context resolved = context.getApplicationContext();
        this.appContext = resolved != null ? resolved : context;
        this.playerFactory = playerFactory;
    }

    private static PlayerFactory createExoPlayerFactory(
            @NonNull Context appContext, @NonNull OkHttpClient okHttpClient) {
        return listener -> {
            OkHttpMediaDataSource.Factory dataSourceFactory =
                    new OkHttpMediaDataSource.Factory(okHttpClient);
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build();
            ExoPlayer exoPlayer = new ExoPlayer.Builder(appContext)
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory))
                    .setAudioAttributes(audioAttributes, true)
                    .build();
            exoPlayer.addListener(listener);
            return new ExoPlayerGateway(exoPlayer);
        };
    }

    private static final class ExoPlayerGateway implements PlayerGateway {
        private final ExoPlayer exoPlayer;

        ExoPlayerGateway(ExoPlayer exoPlayer) {
            this.exoPlayer = exoPlayer;
        }

        @Override public Player asMedia3Player() { return exoPlayer; }
        @Override public void setMediaUrl(@NonNull String url) { exoPlayer.setMediaItem(MediaItem.fromUri(url)); }
        @Override public void setVolume(float volume) { exoPlayer.setVolume(volume); }
        @Override public void setPlayWhenReady(boolean playWhenReady) { exoPlayer.setPlayWhenReady(playWhenReady); }
        @Override public void prepare() { exoPlayer.prepare(); }
        @Override public void play() { exoPlayer.play(); }
        @Override public void pause() { exoPlayer.pause(); }
        @Override public void stop() { exoPlayer.stop(); }
        @Override public void clearMediaItems() { exoPlayer.clearMediaItems(); }
        @Override public void seekTo(long positionMs) { exoPlayer.seekTo(positionMs); }
        @Override public boolean isPlaying() { return exoPlayer.isPlaying(); }
        @Override public int getPlaybackState() { return exoPlayer.getPlaybackState(); }
        @Override public void release() { exoPlayer.release(); }
    }

    public void bind(
            @NonNull Object holderKey,
            @NonNull String postId,
            @NonNull String absoluteUrl,
            @NonNull View container,
            @NonNull PlayerView playerView,
            @NonNull ImageButton playPause,
            @NonNull ImageButton mute,
            @NonNull ProgressBar buffering,
            @NonNull View errorOverlay,
            @NonNull MaterialButton retry) {
        container.setClipToOutline(true);
        boolean isActive = postId.equals(activePostId);
        if (isActive && player != null) {
            if (activeSlot != null && activeSlot.playerView != playerView) {
                activeSlot.playerView.setPlayer(null);
            }
            activeSlot = new ActiveSlot(holderKey, playerView, playPause, mute, buffering, errorOverlay);
            playerView.setPlayer(player.asMedia3Player());
            syncActiveUi();
        } else {
            if (activeSlot != null && activeSlot.holderKey == holderKey) {
                if (player != null) {
                    player.pause();
                }
                activeSlot = null;
            }
            playerView.setPlayer(null);
            renderIdleUi(playPause, mute, buffering, errorOverlay);
        }

        View.OnClickListener togglePlay = ignored -> onPlayPauseClicked(
                holderKey, postId, absoluteUrl, playerView, playPause, mute, buffering, errorOverlay);
        playPause.setOnClickListener(togglePlay);
        container.setOnClickListener(togglePlay);
        mute.setOnClickListener(ignored -> toggleMute(mute));
        retry.setOnClickListener(ignored -> startPlayback(
                holderKey, postId, absoluteUrl, playerView, playPause, mute, buffering, errorOverlay));
    }

    public void onRecycled(@NonNull Object holderKey, @NonNull PlayerView playerView) {
        playerView.setPlayer(null);
        if (activeSlot != null && activeSlot.holderKey == holderKey) {
            if (player != null) {
                player.pause();
            }
            activeSlot = null;
        }
    }

    @Nullable
    public String getActivePostId() {
        return activePostId;
    }

    public void stopPost(@NonNull String postId) {
        if (!postId.equals(activePostId)) {
            return;
        }
        if (activeSlot != null) {
            activeSlot.playerView.setPlayer(null);
            renderIdleUi(activeSlot.playPause, activeSlot.mute,
                    activeSlot.buffering, activeSlot.errorOverlay);
            activeSlot = null;
        }
        if (player != null) {
            player.stop();
            player.clearMediaItems();
        }
        activePostId = null;
        hasError = false;
    }

    public void pause() {
        if (player != null && player.isPlaying()) {
            player.pause();
        }
        syncActiveUi();
    }

    public void release() {
        if (activeSlot != null) {
            activeSlot.playerView.setPlayer(null);
            activeSlot = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        activePostId = null;
        hasError = false;
    }

    private void onPlayPauseClicked(
            Object holderKey,
            String postId,
            String absoluteUrl,
            PlayerView playerView,
            ImageButton playPause,
            ImageButton mute,
            ProgressBar buffering,
            View errorOverlay) {
        if (postId.equals(activePostId) && player != null && !hasError) {
            if (player.isPlaying()) {
                player.pause();
            } else {
                if (player.getPlaybackState() == Player.STATE_ENDED) {
                    player.seekTo(0L);
                }
                player.play();
            }
            syncActiveUi();
            return;
        }
        startPlayback(holderKey, postId, absoluteUrl, playerView, playPause, mute, buffering, errorOverlay);
    }

    private void startPlayback(
            Object holderKey,
            String postId,
            String absoluteUrl,
            PlayerView playerView,
            ImageButton playPause,
            ImageButton mute,
            ProgressBar buffering,
            View errorOverlay) {
        if (activeSlot != null && activeSlot.holderKey != holderKey) {
            activeSlot.playerView.setPlayer(null);
            renderIdleUi(activeSlot.playPause, activeSlot.mute,
                    activeSlot.buffering, activeSlot.errorOverlay);
        }
        PlayerGateway exo = ensurePlayer();
        activePostId = postId;
        hasError = false;
        activeSlot = new ActiveSlot(holderKey, playerView, playPause, mute, buffering, errorOverlay);
        playerView.setPlayer(exo.asMedia3Player());
        exo.setVolume(muted ? 0f : 1f);
        exo.setMediaUrl(absoluteUrl);
        exo.setPlayWhenReady(true);
        exo.prepare();
        syncActiveUi();
    }

    private void toggleMute(@NonNull ImageButton muteButton) {
        muted = !muted;
        if (player != null) {
            player.setVolume(muted ? 0f : 1f);
        }
        applyMuteIcon(muteButton);
    }

    private PlayerGateway ensurePlayer() {
        if (player != null) {
            return player;
        }
        PlayerGateway created = playerFactory.create(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING
                        || playbackState == Player.STATE_ENDED) {
                    hasError = false;
                }
                syncActiveUi();
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                syncActiveUi();
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                hasError = true;
                syncActiveUi();
            }
        });
        player = created;
        return created;
    }

    private void syncActiveUi() {
        if (activeSlot == null || player == null) {
            return;
        }
        int state = player.getPlaybackState();
        boolean playing = player.isPlaying();
        boolean showBuffering = !hasError && state == Player.STATE_BUFFERING;
        activeSlot.errorOverlay.setVisibility(hasError ? View.VISIBLE : View.GONE);
        activeSlot.buffering.setVisibility(showBuffering ? View.VISIBLE : View.GONE);
        activeSlot.playPause.setVisibility(hasError || showBuffering ? View.GONE : View.VISIBLE);
        activeSlot.playPause.setImageResource(
                playing ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
        activeSlot.playPause.setContentDescription(appContext.getString(
                playing ? R.string.feed_pause_video : R.string.cd_play_video));
        applyMuteIcon(activeSlot.mute);
    }

    private void renderIdleUi(
            ImageButton playPause, ImageButton mute, ProgressBar buffering, View errorOverlay) {
        errorOverlay.setVisibility(View.GONE);
        buffering.setVisibility(View.GONE);
        playPause.setVisibility(View.VISIBLE);
        playPause.setImageResource(R.drawable.ic_play_arrow);
        playPause.setContentDescription(appContext.getString(R.string.cd_play_video));
        applyMuteIcon(mute);
    }

    private void applyMuteIcon(@NonNull ImageButton muteButton) {
        muteButton.setImageResource(muted ? R.drawable.ic_volume_off : R.drawable.ic_volume_up);
        muteButton.setContentDescription(appContext.getString(
                muted ? R.string.feed_video_unmute : R.string.feed_video_mute));
    }
}
