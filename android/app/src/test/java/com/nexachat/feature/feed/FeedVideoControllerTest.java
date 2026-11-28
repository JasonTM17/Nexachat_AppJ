package com.nexachat.feature.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.ui.PlayerView;
import com.google.android.material.button.MaterialButton;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public final class FeedVideoControllerTest {

    private Context context;
    private FakePlayerGateway fakePlayer;
    private Player.Listener playerListener;
    private FeedVideoController controller;

    @Before
    public void setUp() {
        context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getString(anyInt())).thenReturn("cd");
        fakePlayer = new FakePlayerGateway();
        controller = new FeedVideoController(context, listener -> {
            playerListener = listener;
            return fakePlayer;
        });
    }

    @Test
    public void rebindingActiveHolderToDifferentPostPausesAndIsolatesCallbacks() {
        SlotViews holder1 = new SlotViews();
        bindSlot(holder1, "holder-1", "post-1");

        // Click play on post-1
        holder1.playClick.get().onClick(holder1.playPause);
        assertEquals("post-1", controller.getActivePostId());
        assertEquals("https://api.example.test/v1.mp4", fakePlayer.mediaUrl);
        fakePlayer.playing = true;
        fakePlayer.playbackState = Player.STATE_READY;
        playerListener.onIsPlayingChanged(true);

        clearInvocations(holder1.playPause, holder1.buffering, holder1.errorOverlay, holder1.mute, holder1.playerView);

        // Rebind the SAME holderKey to post-2 without calling onRecycled first
        bindSlot(holder1, "holder-1", "post-2");

        assertEquals(1, fakePlayer.pauseCount);
        verify(holder1.playerView).setPlayer(null);
        verify(holder1.errorOverlay).setVisibility(View.GONE);
        verify(holder1.buffering).setVisibility(View.GONE);
        verify(holder1.playPause).setVisibility(View.VISIBLE);

        // Subsequent player callbacks for post-1 must NOT update holder1's newly bound post-2 views
        clearInvocations(holder1.playPause, holder1.buffering, holder1.errorOverlay, holder1.mute);
        playerListener.onPlayerError(mock(PlaybackException.class));
        playerListener.onIsPlayingChanged(false);
        playerListener.onPlaybackStateChanged(Player.STATE_BUFFERING);

        verify(holder1.errorOverlay, never()).setVisibility(View.VISIBLE);
        verify(holder1.buffering, never()).setVisibility(View.VISIBLE);
        verify(holder1.playPause, never()).setVisibility(View.GONE);
    }

    @Test
    public void onRecycledPausesActiveSlotAndIgnoresOtherHolders() {
        SlotViews holder1 = new SlotViews();
        SlotViews holder2 = new SlotViews();
        bindSlot(holder1, "holder-1", "post-1");
        bindSlot(holder2, "holder-2", "post-2");

        holder1.playClick.get().onClick(holder1.playPause);
        fakePlayer.playing = true;

        // Recycling inactive holder2 must not pause post-1
        clearInvocations(holder1.playerView, holder2.playerView);
        controller.onRecycled("holder-2", holder2.playerView);
        verify(holder2.playerView).setPlayer(null);
        assertEquals(0, fakePlayer.pauseCount);

        // Recycling active holder1 pauses player and detaches callbacks
        controller.onRecycled("holder-1", holder1.playerView);
        verify(holder1.playerView).setPlayer(null);
        assertEquals(1, fakePlayer.pauseCount);

        clearInvocations(holder1.errorOverlay);
        playerListener.onPlayerError(mock(PlaybackException.class));
        verify(holder1.errorOverlay, never()).setVisibility(View.VISIBLE);
    }

    @Test
    public void stopPostStopsMatchingActivePostAndIgnoresOthers() {
        SlotViews holder1 = new SlotViews();
        bindSlot(holder1, "holder-1", "post-1");
        holder1.playClick.get().onClick(holder1.playPause);
        assertEquals("post-1", controller.getActivePostId());

        // Stopping an unrelated post does nothing
        controller.stopPost("post-999");
        assertEquals("post-1", controller.getActivePostId());
        assertEquals(0, fakePlayer.stopCount);

        // Stopping active post clears state, stops player, and resets UI to idle
        clearInvocations(holder1.playerView, holder1.errorOverlay, holder1.buffering, holder1.playPause);
        controller.stopPost("post-1");
        assertNull(controller.getActivePostId());
        assertEquals(1, fakePlayer.stopCount);
        assertEquals(1, fakePlayer.clearMediaItemsCount);
        verify(holder1.playerView).setPlayer(null);
        verify(holder1.errorOverlay, atLeastOnce()).setVisibility(View.GONE);
        verify(holder1.buffering, atLeastOnce()).setVisibility(View.GONE);
        verify(holder1.playPause, atLeastOnce()).setVisibility(View.VISIBLE);
    }

    @Test
    public void releaseClearsPlayerAndActiveState() {
        SlotViews holder1 = new SlotViews();
        bindSlot(holder1, "holder-1", "post-1");
        holder1.playClick.get().onClick(holder1.playPause);

        clearInvocations(holder1.playerView);
        controller.release();
        assertNull(controller.getActivePostId());
        assertEquals(1, fakePlayer.releaseCount);
        verify(holder1.playerView).setPlayer(null);
    }

    private void bindSlot(SlotViews views, Object holderKey, String postId) {
        controller.bind(
                holderKey,
                postId,
                "https://api.example.test/v1.mp4",
                views.container,
                views.playerView,
                views.playPause,
                views.mute,
                views.buffering,
                views.errorOverlay,
                views.retry);
        ArgumentCaptor<View.OnClickListener> captor = ArgumentCaptor.forClass(View.OnClickListener.class);
        verify(views.playPause, atLeastOnce()).setOnClickListener(captor.capture());
        views.playClick.set(captor.getValue());
    }

    private static final class SlotViews {
        final View container = mock(View.class);
        final PlayerView playerView = mock(PlayerView.class);
        final ImageButton playPause = mock(ImageButton.class);
        final ImageButton mute = mock(ImageButton.class);
        final ProgressBar buffering = mock(ProgressBar.class);
        final View errorOverlay = mock(View.class);
        final MaterialButton retry = mock(MaterialButton.class);
        final AtomicReference<View.OnClickListener> playClick = new AtomicReference<>();
    }

    private static final class FakePlayerGateway implements FeedVideoController.PlayerGateway {
        final Player media3Player = mock(Player.class);
        String mediaUrl;
        boolean playing;
        int playbackState = Player.STATE_IDLE;
        int pauseCount;
        int stopCount;
        int clearMediaItemsCount;
        int releaseCount;

        @Override public Player asMedia3Player() { return media3Player; }
        @Override public void setMediaUrl(String url) { this.mediaUrl = url; }
        @Override public void setVolume(float volume) { }
        @Override public void setPlayWhenReady(boolean playWhenReady) { }
        @Override public void prepare() { }
        @Override public void play() { playing = true; }
        @Override public void pause() { playing = false; pauseCount++; }
        @Override public void stop() { playing = false; stopCount++; }
        @Override public void clearMediaItems() { clearMediaItemsCount++; }
        @Override public void seekTo(long positionMs) { }
        @Override public boolean isPlaying() { return playing; }
        @Override public int getPlaybackState() { return playbackState; }
        @Override public void release() { releaseCount++; }
    }
}
