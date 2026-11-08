package com.nexachat.feature.chat.voice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.nexachat.core.network.dto.AttachmentDto;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public final class VoicePlaybackControllerTest {

    private FakeAttachmentSource attachments;
    private FakePlayer player;
    private FakeFocus focus;
    private VoicePlaybackController controller;

    @Before
    public void setUp() {
        attachments = new FakeAttachmentSource();
        player = new FakePlayer();
        focus = new FakeFocus();
        controller = new VoicePlaybackController(
                attachments,
                file -> new byte[WaveformExtractor.BAR_COUNT],
                () -> player,
                focus,
                new ImmediateScheduler(),
                new MemoryWaveformStore());
    }

    @Test
    public void startingAnotherAttachmentReusesOnePlayerAndStopsPreviousTrack() {
        AttachmentDto first = attachment("first");
        AttachmentDto second = attachment("second");

        controller.toggle(first);
        controller.seek(first, 0.4f);
        controller.toggle(second);

        assertEquals("second", controller.snapshotFor(second).getAttachmentId());
        assertEquals(VoicePlaybackState.Status.PLAYING, controller.snapshotFor(second).getStatus());
        assertEquals(VoicePlaybackState.Status.PAUSED, controller.snapshotFor(first).getStatus());
        assertEquals(4_000L, controller.snapshotFor(first).getPositionMs());
        assertTrue(player.pauseCount >= 1);
        assertEquals(2, player.prepareCount);
        assertTrue(player.stopCount >= 1);

        controller.toggle(first);

        assertEquals(4_000L, controller.snapshotFor(first).getPositionMs());
        assertEquals(VoicePlaybackState.Status.PLAYING,
                controller.snapshotFor(first).getStatus());
    }

    @Test
    public void focusLossPausesPlayback() {
        AttachmentDto attachment = attachment("voice");
        controller.toggle(attachment);

        focus.loss.run();

        assertEquals(VoicePlaybackState.Status.PAUSED, controller.snapshotFor(attachment).getStatus());
        assertTrue(player.pauseCount >= 1);
    }

    @Test
    public void viewReleaseStopsPlaybackAndReleasesPlayer() {
        controller.toggle(attachment("voice"));

        controller.releaseForViewDestroy();

        assertEquals(1, player.releaseCount);
        assertEquals(VoicePlaybackState.Status.IDLE,
                controller.snapshotFor(attachment("voice")).getStatus());
    }

    @Test
    public void snapshotForReboundRowRestoresCurrentProgressAndStatus() {
        AttachmentDto attachment = attachment("voice");
        controller.toggle(attachment);
        controller.seek(attachment, 0.4f);

        VoicePlaybackState firstBinding = controller.snapshotFor(attachment);
        VoicePlaybackState reboundBinding = controller.snapshotFor(attachment);

        assertSame(firstBinding, reboundBinding);
        assertEquals(4_000L, reboundBinding.getPositionMs());
        assertEquals(VoicePlaybackState.Status.PLAYING, reboundBinding.getStatus());
    }

    @Test
    public void deniedAudioFocusLeavesAnErrorState() {
        focus.granted = false;

        controller.toggle(attachment("voice"));

        assertEquals(VoicePlaybackState.Status.ERROR,
                controller.snapshotFor(attachment("voice")).getStatus());
        assertEquals(0, player.prepareCount);
    }

    private static AttachmentDto attachment(String id) {
        return new AttachmentDto(id, null, id + ".m4a", "audio/mp4", null,
                null, null, 10_000L, null);
    }

    private static final class FakeAttachmentSource implements VoicePlaybackController.AttachmentSource {
        @Override
        public void download(AttachmentDto attachment, VoicePlaybackController.DownloadCallback callback) {
            callback.onLoaded(new File(attachment.getId() + ".m4a"));
        }

        @Override public void cancel(String attachmentId) { }
    }

    private static final class FakePlayer implements VoicePlaybackController.Player {
        private Listener listener;
        private long positionMs;
        private int prepareCount;
        private int pauseCount;
        private int stopCount;
        private int releaseCount;

        @Override public void setListener(Listener listener) { this.listener = listener; }

        @Override
        public void prepareAndPlay(File file) {
            prepareCount++;
            positionMs = 0L;
            listener.onReady(10_000L);
            listener.onPlayingChanged(true);
        }

        @Override public void play() { listener.onPlayingChanged(true); }
        @Override public void pause() { pauseCount++; listener.onPlayingChanged(false); }
        @Override public void seekTo(long positionMs) { this.positionMs = positionMs; }
        @Override public long positionMs() { return positionMs; }
        @Override public long durationMs() { return 10_000L; }
        @Override public void stop() { stopCount++; positionMs = 0L; }
        @Override public void release() { releaseCount++; }
    }

    private static final class FakeFocus implements VoicePlaybackController.AudioFocus {
        private boolean granted = true;
        private Runnable loss;
        private int abandonCount;

        @Override
        public boolean request(Runnable onLoss) {
            loss = onLoss;
            return granted;
        }

        @Override public void abandon() { abandonCount++; }
    }

    private static final class ImmediateScheduler implements VoicePlaybackController.Scheduler {
        @Override public void postMain(Runnable task) { task.run(); }
        @Override public void executeBackground(Runnable task) { task.run(); }
        @Override public void postDelayed(Runnable task, long delayMs) { }
        @Override public void removeCallbacks(Runnable task) { }
        @Override public void close() { }
    }

    private static final class MemoryWaveformStore implements VoicePlaybackController.WaveformStore {
        private final Map<String, byte[]> values = new HashMap<>();

        @Override
        public byte[] get(String attachmentId) {
            byte[] value = values.get(attachmentId);
            return value == null ? null : value.clone();
        }

        @Override public void put(String attachmentId, byte[] bars) { values.put(attachmentId, bars.clone()); }
    }
}
