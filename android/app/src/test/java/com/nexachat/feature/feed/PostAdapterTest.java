package com.nexachat.feature.feed;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.ui.PlayerView;
import com.google.android.material.button.MaterialButton;
import com.nexachat.R;
import com.nexachat.core.ui.components.AvatarView;
import java.util.List;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public final class PostAdapterTest {

    @Test
    public void rebindingVideoHolderToNonVideoPostReleasesVideoControllerSlot() {
        Context context = mock(Context.class);
        when(context.getApplicationContext()).thenReturn(context);
        when(context.getString(anyInt())).thenReturn("cd");

        FakePlayerGateway fakePlayer = new FakePlayerGateway();
        Player.Listener[] capturedListener = new Player.Listener[1];
        FeedVideoController controller = new FeedVideoController(context, listener -> {
            capturedListener[0] = listener;
            return fakePlayer;
        });

        FrameLayout videoContainer = mock(FrameLayout.class);
        PlayerView videoPlayer = mock(PlayerView.class);
        ProgressBar videoBuffering = mock(ProgressBar.class);
        ImageButton videoPlayPause = mock(ImageButton.class);
        ImageButton videoMute = mock(ImageButton.class);
        View videoError = mock(View.class);
        MaterialButton videoRetry = mock(MaterialButton.class);

        View itemView = mock(View.class);
        when(itemView.findViewById(R.id.postAuthorHeader)).thenReturn(mock(View.class));
        when(itemView.findViewById(R.id.postAvatar)).thenReturn(mock(AvatarView.class));
        when(itemView.findViewById(R.id.postAuthor)).thenReturn(mock(TextView.class));
        when(itemView.findViewById(R.id.postTime)).thenReturn(mock(TextView.class));
        when(itemView.findViewById(R.id.postEdited)).thenReturn(mock(TextView.class));
        when(itemView.findViewById(R.id.postBody)).thenReturn(mock(TextView.class));
        when(itemView.findViewById(R.id.postImage)).thenReturn(mock(ImageView.class));
        when(itemView.findViewById(R.id.postVideoContainer)).thenReturn(videoContainer);
        when(itemView.findViewById(R.id.postVideoPlayer)).thenReturn(videoPlayer);
        when(itemView.findViewById(R.id.postVideoBuffering)).thenReturn(videoBuffering);
        when(itemView.findViewById(R.id.postVideoPlayPause)).thenReturn(videoPlayPause);
        when(itemView.findViewById(R.id.postVideoMute)).thenReturn(videoMute);
        when(itemView.findViewById(R.id.postVideoError)).thenReturn(videoError);
        when(itemView.findViewById(R.id.postVideoRetry)).thenReturn(videoRetry);
        when(itemView.findViewById(R.id.postReactions)).thenReturn(mock(LinearLayout.class));
        when(itemView.findViewById(R.id.postReact)).thenReturn(mock(MaterialButton.class));
        when(itemView.findViewById(R.id.postComments)).thenReturn(mock(MaterialButton.class));
        when(itemView.findViewById(R.id.postMenu)).thenReturn(mock(ImageButton.class));

        PostAdapter.Holder holder = new PostAdapter.Holder(itemView);

        FeedRepository.Post videoPost = new FeedRepository.Post(
                "post-video",
                new FeedRepository.Author("author-1", "An", null),
                "video caption",
                null,
                new FeedRepository.Video("vid-1", "/api/v1/feed/videos/vid-1"),
                "2026-02-25T05:33:20Z",
                null,
                0,
                List.of(),
                true);

        PostAdapter.bindVideo(controller, holder, videoPost);
        ArgumentCaptor<View.OnClickListener> playCaptor =
                ArgumentCaptor.forClass(View.OnClickListener.class);
        verify(videoPlayPause, atLeastOnce()).setOnClickListener(playCaptor.capture());
        playCaptor.getValue().onClick(videoPlayPause);
        fakePlayer.playing = true;
        assertEquals("post-video", controller.getActivePostId());

        clearInvocations(videoContainer, videoPlayer, videoError);
        FeedRepository.Post textPost = new FeedRepository.Post(
                "post-text",
                new FeedRepository.Author("author-1", "An", null),
                "plain text",
                null,
                null,
                "2026-02-25T05:34:20Z",
                null,
                0,
                List.of(),
                true);

        PostAdapter.bindVideo(controller, holder, textPost);

        assertEquals(1, fakePlayer.pauseCount);
        verify(videoPlayer, atLeastOnce()).setPlayer(null);
        verify(videoContainer).setVisibility(View.GONE);
        capturedListener[0].onPlayerError(mock(PlaybackException.class));
        verify(videoError, never()).setVisibility(View.VISIBLE);
    }

    private static final class FakePlayerGateway implements FeedVideoController.PlayerGateway {
        final Player media3Player = mock(Player.class);
        boolean playing;
        int pauseCount;

        @Override public Player asMedia3Player() { return media3Player; }
        @Override public void setMediaUrl(String url) { }
        @Override public void setVolume(float volume) { }
        @Override public void setPlayWhenReady(boolean playWhenReady) { }
        @Override public void prepare() { }
        @Override public void play() { playing = true; }
        @Override public void pause() { playing = false; pauseCount++; }
        @Override public void stop() { playing = false; }
        @Override public void clearMediaItems() { }
        @Override public void seekTo(long positionMs) { }
        @Override public boolean isPlaying() { return playing; }
        @Override public int getPlaybackState() { return Player.STATE_READY; }
        @Override public void release() { }
    }
}
