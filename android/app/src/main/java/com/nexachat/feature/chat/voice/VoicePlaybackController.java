package com.nexachat.feature.chat.voice;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.nexachat.core.network.dto.AttachmentDto;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One-player state owner for a chat screen; item rows only observe snapshots. */
public final class VoicePlaybackController {

    public static final long TICK_INTERVAL_MS = 50L;

    public interface StateListener {
        void onStateChanged(@NonNull VoicePlaybackState state);
    }

    public interface DownloadCallback {
        void onLoaded(@NonNull File file);
        void onFailure(@NonNull Throwable failure);
    }

    public interface AttachmentSource {
        void download(@NonNull AttachmentDto attachment, @NonNull DownloadCallback callback);
        void cancel(@NonNull String attachmentId);
    }

    public interface WaveformComputer {
        @NonNull byte[] extract(@NonNull File file);
    }

    public interface WaveformStore {
        @Nullable byte[] get(@Nullable String attachmentId);
        void put(@NonNull String attachmentId, @NonNull byte[] bars);
    }

    public interface Scheduler {
        void postMain(@NonNull Runnable task);
        void executeBackground(@NonNull Runnable task);
        void postDelayed(@NonNull Runnable task, long delayMs);
        void removeCallbacks(@NonNull Runnable task);
        void close();
    }

    public interface PlayerFactory {
        @NonNull Player create();
    }

    public interface Player {
        interface Listener {
            void onReady(long durationMs);
            void onPlayingChanged(boolean playing);
            void onEnded();
            void onFailure(@NonNull Throwable failure);
        }

        void setListener(@NonNull Listener listener);
        void prepareAndPlay(@NonNull File file);
        void play();
        void pause();
        void seekTo(long positionMs);
        long positionMs();
        long durationMs();
        void stop();
        void release();
    }

    public interface AudioFocus {
        boolean request(@NonNull Runnable onLoss);
        void abandon();
    }

    private final AttachmentSource attachmentSource;
    private final WaveformComputer waveformComputer;
    private final PlayerFactory playerFactory;
    private final AudioFocus audioFocus;
    private final Scheduler scheduler;
    private final WaveformStore waveformStore;
    private final Set<StateListener> listeners = new LinkedHashSet<>();
    private final LinkedHashMap<String, VoicePlaybackState> rememberedStates =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, VoicePlaybackState> eldest) {
                    return size() > VoiceWaveformCache.MAX_ENTRIES;
                }
            };
    private final Runnable ticker = this::tick;

    @Nullable private Player player;
    @Nullable private AttachmentDto activeAttachment;
    @Nullable private String activeAttachmentId;
    @Nullable private String pendingDownloadId;
    private VoicePlaybackState state = new VoicePlaybackState(
            "", VoicePlaybackState.Status.IDLE, 0L, 0L, WaveformExtractor.flatBars());
    private long pendingSeekMs = -1L;
    private boolean playerReady;

    public VoicePlaybackController(
            @NonNull AttachmentSource attachmentSource,
            @NonNull WaveformComputer waveformComputer,
            @NonNull PlayerFactory playerFactory,
            @NonNull AudioFocus audioFocus,
            @NonNull Scheduler scheduler) {
        this(attachmentSource, waveformComputer, playerFactory, audioFocus, scheduler,
                VoiceWaveformCache.instance());
    }

    public VoicePlaybackController(
            @NonNull AttachmentSource attachmentSource,
            @NonNull WaveformComputer waveformComputer,
            @NonNull PlayerFactory playerFactory,
            @NonNull AudioFocus audioFocus,
            @NonNull Scheduler scheduler,
            @NonNull WaveformStore waveformStore) {
        this.attachmentSource = Objects.requireNonNull(attachmentSource, "attachmentSource");
        this.waveformComputer = Objects.requireNonNull(waveformComputer, "waveformComputer");
        this.playerFactory = Objects.requireNonNull(playerFactory, "playerFactory");
        this.audioFocus = Objects.requireNonNull(audioFocus, "audioFocus");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.waveformStore = Objects.requireNonNull(waveformStore, "waveformStore");
    }

    public void addListener(@NonNull StateListener listener) {
        listeners.add(listener);
        listener.onStateChanged(state);
    }

    public void removeListener(@NonNull StateListener listener) {
        listeners.remove(listener);
    }

    @NonNull
    public VoicePlaybackState snapshotFor(@NonNull AttachmentDto attachment) {
        String id = attachment.getId();
        if (id == null || id.trim().isEmpty()) {
            return new VoicePlaybackState("", VoicePlaybackState.Status.ERROR,
                    0L, safeDuration(attachment), WaveformExtractor.flatBars());
        }
        if (id.equals(state.getAttachmentId())) {
            return state;
        }
        VoicePlaybackState remembered = rememberedStates.get(id);
        if (remembered != null) {
            return remembered;
        }
        byte[] bars = waveformStore.get(id);
        return new VoicePlaybackState(id, VoicePlaybackState.Status.IDLE, 0L,
                safeDuration(attachment), bars == null ? WaveformExtractor.flatBars() : bars);
    }

    public void toggle(@NonNull AttachmentDto attachment) {
        String id = attachment.getId();
        if (id == null || id.trim().isEmpty()) {
            publish(new VoicePlaybackState("", VoicePlaybackState.Status.ERROR, 0L,
                    safeDuration(attachment), WaveformExtractor.flatBars()));
            return;
        }
        if (id.equals(activeAttachmentId)) {
            switch (state.getStatus()) {
                case PLAYING:
                    pause();
                    return;
                case PAUSED:
                    resume();
                    return;
                case LOADING:
                    stop();
                    return;
                default:
                    break;
            }
        }
        VoicePlaybackState remembered = rememberedStates.get(id);
        long startPosition = remembered != null
                && remembered.getStatus() == VoicePlaybackState.Status.PAUSED
                ? remembered.getPositionMs()
                : -1L;
        start(attachment, startPosition);
    }

    public void seek(@NonNull AttachmentDto attachment, float fraction) {
        String id = attachment.getId();
        if (id == null || id.trim().isEmpty()) {
            return;
        }
        float safeFraction = Float.isNaN(fraction) ? 0f : Math.max(0f, Math.min(1f, fraction));
        long durationMs = state.getAttachmentId().equals(id)
                ? Math.max(state.getDurationMs(), safeDuration(attachment))
                : safeDuration(attachment);
        long positionMs = Math.round(durationMs * safeFraction);
        if (!id.equals(activeAttachmentId)) {
            start(attachment, positionMs);
            return;
        }
        if (!playerReady) {
            pendingSeekMs = positionMs;
            return;
        }
        player.seekTo(positionMs);
        publish(state.with(state.getStatus(), positionMs, durationMs, state.getWaveform()));
        if (state.getStatus() == VoicePlaybackState.Status.PAUSED) {
            rememberPaused(id, positionMs, durationMs, state.getWaveform());
        }
    }

    public void stop() {
        cancelTicker();
        cancelPendingDownload();
        String oldId = activeAttachmentId;
        activeAttachmentId = null;
        activeAttachment = null;
        playerReady = false;
        pendingSeekMs = -1L;
        if (oldId != null) {
            rememberedStates.remove(oldId);
        }
        if (player != null) {
            player.stop();
        }
        audioFocus.abandon();
        publish(new VoicePlaybackState(
                oldId == null ? state.getAttachmentId() : oldId,
                VoicePlaybackState.Status.IDLE,
                0L,
                state.getDurationMs(),
                state.getWaveform()));
    }

    /** Stops audio and releases native player resources when the chat view is destroyed. */
    public void releaseForViewDestroy() {
        stop();
        if (player != null) {
            player.setListener(new NoOpPlayerListener());
            player.release();
            player = null;
        }
    }

    public void close() {
        releaseForViewDestroy();
        listeners.clear();
        scheduler.close();
    }

    private void start(@NonNull AttachmentDto attachment, long seekToMs) {
        stopCurrentForReplacement();
        String id = attachment.getId();
        if (id == null || id.trim().isEmpty()) {
            return;
        }
        activeAttachment = attachment;
        activeAttachmentId = id;
        pendingDownloadId = id;
        pendingSeekMs = seekToMs;
        playerReady = false;
        byte[] bars = waveformStore.get(id);
        publish(new VoicePlaybackState(id, VoicePlaybackState.Status.LOADING, 0L,
                safeDuration(attachment), bars == null ? WaveformExtractor.flatBars() : bars));
        try {
            attachmentSource.download(attachment, new DownloadCallback() {
                @Override
                public void onLoaded(@NonNull File file) {
                    scheduler.postMain(() -> onFileLoaded(id, file));
                }

                @Override
                public void onFailure(@NonNull Throwable failure) {
                    scheduler.postMain(() -> VoicePlaybackController.this.onFailure(id, failure));
                }
            });
        } catch (RuntimeException exception) {
            onFailure(id, exception);
        }
    }

    private void stopCurrentForReplacement() {
        cancelTicker();
        cancelPendingDownload();
        String previousId = activeAttachmentId;
        if (previousId != null) {
            if (state.getStatus() == VoicePlaybackState.Status.PLAYING
                    || state.getStatus() == VoicePlaybackState.Status.PAUSED) {
                long position = safePlayerPosition();
                long duration = safePlayerDuration();
                if (state.getStatus() == VoicePlaybackState.Status.PLAYING && player != null) {
                    player.pause();
                }
                rememberPaused(previousId, position, duration, state.getWaveform());
            } else {
                rememberedStates.remove(previousId);
            }
        }
        activeAttachmentId = null;
        activeAttachment = null;
        playerReady = false;
        pendingSeekMs = -1L;
        if (player != null) {
            player.stop();
        }
        audioFocus.abandon();
    }

    private void onFileLoaded(@NonNull String id, @NonNull File file) {
        if (!id.equals(activeAttachmentId)) {
            return;
        }
        pendingDownloadId = null;
        scheduler.executeBackground(() -> {
            byte[] bars = waveformStore.get(id);
            if (bars == null) {
                try {
                    bars = waveformComputer.extract(file);
                } catch (RuntimeException exception) {
                    bars = WaveformExtractor.flatBars();
                }
            waveformStore.put(id, bars);
            }
            byte[] result = bars;
            scheduler.postMain(() -> onWaveformReady(id, result));
        });

        if (!audioFocus.request(() -> scheduler.postMain(this::pauseForFocusLoss))) {
            onFailure(id, new IllegalStateException("Audio focus unavailable"));
            return;
        }
        try {
            Player current = ensurePlayer();
            current.prepareAndPlay(file);
        } catch (RuntimeException exception) {
            onFailure(id, exception);
        }
    }

    private void onWaveformReady(@NonNull String id, @NonNull byte[] bars) {
        waveformStore.put(id, bars);
        if (id.equals(state.getAttachmentId())) {
            publish(state.with(state.getStatus(), state.getPositionMs(),
                    state.getDurationMs(), bars));
            VoicePlaybackState remembered = rememberedStates.get(id);
            if (remembered != null) {
                rememberedStates.put(id, remembered.with(remembered.getStatus(),
                        remembered.getPositionMs(), remembered.getDurationMs(), bars));
            }
        }
    }

    private Player ensurePlayer() {
        if (player == null) {
            player = playerFactory.create();
            player.setListener(new Player.Listener() {
                @Override public void onReady(long durationMs) {
                    scheduler.postMain(() -> onReadyOnMain(durationMs));
                }
                @Override public void onPlayingChanged(boolean playing) {
                    scheduler.postMain(() -> onPlayingChangedOnMain(playing));
                }
                @Override public void onEnded() {
                    scheduler.postMain(VoicePlaybackController.this::onEndedOnMain);
                }
                @Override public void onFailure(@NonNull Throwable failure) {
                    scheduler.postMain(() -> {
                        String id = activeAttachmentId;
                        if (id != null) {
                            VoicePlaybackController.this.onFailure(id, failure);
                        }
                    });
                }
            });
        }
        return player;
    }

    private void onReadyOnMain(long durationMs) {
        if (activeAttachmentId == null || state.getStatus() == VoicePlaybackState.Status.ERROR) {
            return;
        }
        playerReady = true;
        long resolvedDuration = durationMs > 0L
                ? durationMs
                : activeAttachment == null ? state.getDurationMs() : safeDuration(activeAttachment);
        long position = pendingSeekMs >= 0L ? pendingSeekMs : state.getPositionMs();
        pendingSeekMs = -1L;
        if (player != null && position > 0L) {
            player.seekTo(Math.min(position, resolvedDuration));
        }
        publish(state.with(state.getStatus(), position, resolvedDuration, state.getWaveform()));
    }

    private void onPlayingChangedOnMain(boolean playing) {
        if (activeAttachmentId == null) {
            return;
        }
        if (playing) {
            rememberedStates.remove(activeAttachmentId);
            publish(state.with(VoicePlaybackState.Status.PLAYING,
                    safePlayerPosition(), safePlayerDuration(), state.getWaveform()));
            startTicker();
        } else if (state.getStatus() == VoicePlaybackState.Status.PLAYING) {
            cancelTicker();
            publish(state.with(VoicePlaybackState.Status.PAUSED,
                    safePlayerPosition(), safePlayerDuration(), state.getWaveform()));
            rememberPaused(state.getAttachmentId(), state.getPositionMs(),
                    state.getDurationMs(), state.getWaveform());
            audioFocus.abandon();
        }
    }

    private void onEndedOnMain() {
        if (activeAttachmentId == null) {
            return;
        }
        cancelTicker();
        audioFocus.abandon();
        String id = activeAttachmentId;
        activeAttachmentId = null;
        activeAttachment = null;
        playerReady = false;
        rememberedStates.remove(id);
        publish(new VoicePlaybackState(id, VoicePlaybackState.Status.IDLE, 0L,
                safePlayerDuration(), state.getWaveform()));
    }

    private void onFailure(@NonNull String id, @NonNull Throwable failure) {
        if (!id.equals(activeAttachmentId)) {
            return;
        }
        cancelTicker();
        cancelPendingDownload();
        if (player != null) {
            player.stop();
        }
        audioFocus.abandon();
        activeAttachmentId = null;
        activeAttachment = null;
        playerReady = false;
        rememberedStates.remove(id);
        publish(new VoicePlaybackState(id, VoicePlaybackState.Status.ERROR, 0L,
                state.getDurationMs(), state.getWaveform()));
    }

    private void pause() {
        cancelTicker();
        if (player != null) {
            player.pause();
        }
        audioFocus.abandon();
        publish(state.with(VoicePlaybackState.Status.PAUSED,
                safePlayerPosition(), safePlayerDuration(), state.getWaveform()));
        rememberPaused(state.getAttachmentId(), state.getPositionMs(),
                state.getDurationMs(), state.getWaveform());
    }

    private void resume() {
        if (!audioFocus.request(() -> scheduler.postMain(this::pauseForFocusLoss))) {
            onFailure(state.getAttachmentId(), new IllegalStateException("Audio focus unavailable"));
            return;
        }
        if (player != null) {
            player.play();
            publish(state.with(VoicePlaybackState.Status.PLAYING,
                    safePlayerPosition(), safePlayerDuration(), state.getWaveform()));
            startTicker();
        }
    }

    private void pauseForFocusLoss() {
        if (state.getStatus() != VoicePlaybackState.Status.PLAYING) {
            return;
        }
        cancelTicker();
        if (player != null) {
            player.pause();
        }
        audioFocus.abandon();
        publish(state.with(VoicePlaybackState.Status.PAUSED,
                safePlayerPosition(), safePlayerDuration(), state.getWaveform()));
        rememberPaused(state.getAttachmentId(), state.getPositionMs(),
                state.getDurationMs(), state.getWaveform());
    }

    private void startTicker() {
        cancelTicker();
        scheduler.postDelayed(ticker, TICK_INTERVAL_MS);
    }

    private void cancelTicker() {
        scheduler.removeCallbacks(ticker);
    }

    private void tick() {
        if (state.getStatus() != VoicePlaybackState.Status.PLAYING || player == null) {
            return;
        }
        publish(state.with(VoicePlaybackState.Status.PLAYING,
                safePlayerPosition(), safePlayerDuration(), state.getWaveform()));
        scheduler.postDelayed(ticker, TICK_INTERVAL_MS);
    }

    private long safePlayerPosition() {
        if (player == null) {
            return state.getPositionMs();
        }
        return Math.max(0L, player.positionMs());
    }

    private long safePlayerDuration() {
        if (player == null || player.durationMs() <= 0L) {
            return state.getDurationMs();
        }
        return player.durationMs();
    }

    private void cancelPendingDownload() {
        String pending = pendingDownloadId;
        pendingDownloadId = null;
        if (pending != null) {
            attachmentSource.cancel(pending);
        }
    }

    private void publish(@NonNull VoicePlaybackState next) {
        state = next;
        List<StateListener> copy = new ArrayList<>(listeners);
        for (StateListener listener : copy) {
            listener.onStateChanged(next);
        }
    }

    private void rememberPaused(
            @NonNull String id,
            long positionMs,
            long durationMs,
            @NonNull byte[] waveform) {
        rememberedStates.put(id, new VoicePlaybackState(id, VoicePlaybackState.Status.PAUSED,
                positionMs, durationMs, waveform));
    }

    private static long safeDuration(@NonNull AttachmentDto attachment) {
        Long duration = attachment.getDurationMs();
        return duration == null ? 0L : Math.max(0L, duration);
    }

    private static final class NoOpPlayerListener implements Player.Listener {
        @Override public void onReady(long durationMs) { }
        @Override public void onPlayingChanged(boolean playing) { }
        @Override public void onEnded() { }
        @Override public void onFailure(@NonNull Throwable failure) { }
    }
}
