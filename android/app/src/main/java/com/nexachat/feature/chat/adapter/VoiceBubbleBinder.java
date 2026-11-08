package com.nexachat.feature.chat.adapter;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.lifecycle.ViewTreeLifecycleOwner;
import androidx.lifecycle.ViewTreeViewModelStoreOwner;
import com.google.android.material.color.MaterialColors;
import com.nexachat.R;
import com.nexachat.core.network.dto.AttachmentDto;
import com.nexachat.core.ui.MessageUiRules;
import com.nexachat.data.ChatMessage;
import com.nexachat.feature.chat.voice.VoicePlaybackController;
import com.nexachat.feature.chat.voice.VoicePlaybackState;
import com.nexachat.feature.chat.voice.VoicePlaybackViewModel;
import com.nexachat.feature.chat.voice.WaveformView;
import java.text.NumberFormat;
import java.util.Locale;

/** Owns only the inline audio branch; player state belongs to the chat ViewModel. */
public final class VoiceBubbleBinder {

    private VoiceBubbleBinder() {
    }

    /** Returns true when this row is fully handled by the inline voice player. */
    public static boolean bindIfVoice(@NonNull View row, @NonNull ChatMessage message) {
        return bindIfVoice(row, message, null, null);
    }

    static boolean bindIfVoice(
            @NonNull View row,
            @NonNull ChatMessage message,
            @Nullable LifecycleOwner suppliedLifecycleOwner,
            @Nullable ViewModelStoreOwner suppliedStoreOwner) {
        View playerRoot = row.findViewById(R.id.voicePlayerRoot);
        if (playerRoot == null) {
            return false;
        }
        if (MessageViewTypeMapper.resolveContentKind(message) != MessageContentKind.AUDIO
                || MessageUiRules.isTombstone(message.isDeleted(), message.getDeletedAtEpochMs())) {
            playerRoot.setVisibility(View.GONE);
            disposeBinding(row);
            return false;
        }
        if (message.getAttachments().isEmpty()) {
            playerRoot.setVisibility(View.GONE);
            disposeBinding(row);
            return false;
        }

        AttachmentDto attachment = message.getAttachments().get(0);
        LifecycleOwner lifecycleOwner = suppliedLifecycleOwner != null
                ? suppliedLifecycleOwner : ViewTreeLifecycleOwner.get(row);
        ViewModelStoreOwner storeOwner = suppliedStoreOwner != null
                ? suppliedStoreOwner : ViewTreeViewModelStoreOwner.get(row);
        if (lifecycleOwner == null || storeOwner == null) {
            playerRoot.setVisibility(View.VISIBLE);
            if (!row.isAttachedToWindow()) {
                deferUntilAttached(row, message, suppliedLifecycleOwner, suppliedStoreOwner);
            }
            return true;
        }

        VoicePlaybackViewModel viewModel = new ViewModelProvider(storeOwner)
                .get(VoicePlaybackViewModel.class);
        viewModel.observeViewLifecycle(lifecycleOwner.getLifecycle());
        VoicePlaybackController controller = viewModel.controller(row.getContext());
        disposeBinding(row);

        ImageButton playButton = playerRoot.findViewById(R.id.voicePlayButton);
        WaveformView waveform = playerRoot.findViewById(R.id.voiceWaveform);
        TextView duration = playerRoot.findViewById(R.id.voiceDuration);
        if (playButton == null || waveform == null || duration == null) {
            playerRoot.setVisibility(View.GONE);
            return false;
        }

        playerRoot.setVisibility(View.VISIBLE);
        Binding binding = new Binding(row, lifecycleOwner.getLifecycle(), controller, attachment,
                message.getMine(), playButton, waveform, duration);
        row.setTag(R.id.voice_binder_binding, binding);
        row.addOnAttachStateChangeListener(binding);
        lifecycleOwner.getLifecycle().addObserver(binding);
        binding.render(controller.snapshotFor(attachment));
        if (row.isAttachedToWindow()) {
            binding.startObserving();
        }
        playButton.setOnClickListener(view -> controller.toggle(attachment));
        waveform.setOnSeekListener(fraction -> controller.seek(attachment, fraction));
        return true;
    }

    static void disposeBinding(@NonNull View row) {
        Object tag = row.getTag(R.id.voice_binder_binding);
        if (tag instanceof Binding binding) {
            binding.dispose();
            row.setTag(R.id.voice_binder_binding, null);
        } else if (tag instanceof PendingBinding pending) {
            pending.dispose();
        } else {
            row.setTag(R.id.voice_binder_binding, null);
        }
    }

    private static void deferUntilAttached(
            @NonNull View row,
            @NonNull ChatMessage message,
            @Nullable LifecycleOwner lifecycleOwner,
            @Nullable ViewModelStoreOwner storeOwner) {
        Object tag = row.getTag(R.id.voice_binder_binding);
        if (tag instanceof PendingBinding pending) {
            pending.update(message, lifecycleOwner, storeOwner);
            return;
        }
        disposeBinding(row);
        PendingBinding pending = new PendingBinding(row, message, lifecycleOwner, storeOwner);
        row.setTag(R.id.voice_binder_binding, pending);
        row.addOnAttachStateChangeListener(pending);
    }

    static String formatDuration(long durationMs) {
        long totalSeconds = Math.max(0L, durationMs / 1_000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        NumberFormat format = NumberFormat.getIntegerInstance(Locale.getDefault());
        format.setGroupingUsed(false);
        return format.format(minutes) + ":" + String.format(Locale.getDefault(), "%02d", seconds);
    }

    static long durationMsOf(@Nullable AttachmentDto attachment) {
        if (attachment == null || attachment.getDurationMs() == null) {
            return 0L;
        }
        return Math.max(0L, attachment.getDurationMs());
    }

    private static final class PendingBinding implements View.OnAttachStateChangeListener {
        private final View row;
        private ChatMessage message;
        @Nullable private LifecycleOwner lifecycleOwner;
        @Nullable private ViewModelStoreOwner storeOwner;
        private boolean disposed;

        PendingBinding(
                View row,
                ChatMessage message,
                @Nullable LifecycleOwner lifecycleOwner,
                @Nullable ViewModelStoreOwner storeOwner) {
            this.row = row;
            this.message = message;
            this.lifecycleOwner = lifecycleOwner;
            this.storeOwner = storeOwner;
        }

        void update(
                ChatMessage nextMessage,
                @Nullable LifecycleOwner nextLifecycleOwner,
                @Nullable ViewModelStoreOwner nextStoreOwner) {
            message = nextMessage;
            lifecycleOwner = nextLifecycleOwner;
            storeOwner = nextStoreOwner;
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            if (disposed) {
                return;
            }
            ChatMessage nextMessage = message;
            LifecycleOwner nextLifecycleOwner = lifecycleOwner;
            ViewModelStoreOwner nextStoreOwner = storeOwner;
            dispose();
            bindIfVoice(row, nextMessage, nextLifecycleOwner, nextStoreOwner);
        }

        @Override
        public void onViewDetachedFromWindow(View view) { }

        void dispose() {
            if (disposed) {
                return;
            }
            disposed = true;
            row.removeOnAttachStateChangeListener(this);
            if (row.getTag(R.id.voice_binder_binding) == this) {
                row.setTag(R.id.voice_binder_binding, null);
            }
        }
    }

    private static final class Binding implements View.OnAttachStateChangeListener,
            LifecycleEventObserver {
        private final View row;
        private final Lifecycle lifecycle;
        private final VoicePlaybackController controller;
        private final AttachmentDto attachment;
        private final boolean mine;
        private final ImageButton playButton;
        private final WaveformView waveform;
        private final TextView duration;
        private final VoicePlaybackController.StateListener stateListener;
        private boolean observing;
        private boolean disposed;

        Binding(
                View row,
                Lifecycle lifecycle,
                VoicePlaybackController controller,
                AttachmentDto attachment,
                boolean mine,
                ImageButton playButton,
                WaveformView waveform,
                TextView duration) {
            this.row = row;
            this.lifecycle = lifecycle;
            this.controller = controller;
            this.attachment = attachment;
            this.mine = mine;
            this.playButton = playButton;
            this.waveform = waveform;
            this.duration = duration;
            this.stateListener = ignored -> render(this.controller.snapshotFor(this.attachment));
        }

        void startObserving() {
            if (!observing && !disposed) {
                observing = true;
                controller.addListener(stateListener);
            }
        }

        void stopObserving() {
            if (observing) {
                observing = false;
                controller.removeListener(stateListener);
            }
        }

        void render(@NonNull VoicePlaybackState state) {
            if (disposed) {
                return;
            }
            VoicePlaybackState.Status status = state.getStatus();
            long total = Math.max(state.getDurationMs(), durationMsOf(attachment));
            long position = Math.min(state.getPositionMs(), total);
            boolean playing = status == VoicePlaybackState.Status.PLAYING;
            playButton.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play_arrow);
            int contentColor = MaterialColors.getColor(waveform,
                    mine ? com.google.android.material.R.attr.colorOnPrimaryContainer
                            : com.google.android.material.R.attr.colorPrimary,
                    ContextCompat.getColor(waveform.getContext(), R.color.brand_indigo));
            int unplayedBase = MaterialColors.getColor(waveform,
                    mine ? com.google.android.material.R.attr.colorOnPrimaryContainer
                            : com.google.android.material.R.attr.colorOnSurfaceVariant,
                    ContextCompat.getColor(waveform.getContext(), R.color.colorOnSurfaceVariant));
            waveform.setColors(contentColor, ColorUtils.setAlphaComponent(
                    unplayedBase, Math.round(Color.alpha(unplayedBase) * 0.4f)));
            playButton.setImageTintList(ColorStateList.valueOf(contentColor));
            byte[] bars = state.getWaveform();
            waveform.setBars(bars);
            waveform.setProgress(total <= 0L ? 0f : (float) position / total);

            String totalText = formatDuration(total);
            duration.setText(status == VoicePlaybackState.Status.PLAYING
                    || status == VoicePlaybackState.Status.PAUSED
                    ? row.getContext().getString(R.string.voice_duration_progress,
                            formatDuration(position), totalText)
                    : totalText);
            int statusText = statusText(status);
            String description = row.getContext().getString(
                    R.string.voice_message_accessibility,
                    formatDuration(position), totalText, row.getContext().getString(statusText));
            waveform.setContentDescription(description);
            playButton.setContentDescription(row.getContext().getString(
                    status == VoicePlaybackState.Status.LOADING
                            ? R.string.voice_loading
                            : playing ? R.string.voice_pause : R.string.voice_play));
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            startObserving();
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            stopObserving();
        }

        @Override
        public void onStateChanged(@NonNull LifecycleOwner source, @NonNull Lifecycle.Event event) {
            if (event == Lifecycle.Event.ON_DESTROY) {
                dispose();
            }
        }

        void dispose() {
            if (disposed) {
                return;
            }
            disposed = true;
            stopObserving();
            row.removeOnAttachStateChangeListener(this);
            lifecycle.removeObserver(this);
            playButton.setOnClickListener(null);
            waveform.setOnSeekListener(null);
        }

        private static int statusText(VoicePlaybackState.Status status) {
            switch (status) {
                case LOADING: return R.string.voice_status_loading;
                case PLAYING: return R.string.voice_status_playing;
                case PAUSED: return R.string.voice_status_paused;
                case ERROR: return R.string.voice_status_error;
                case IDLE:
                default: return R.string.voice_status_ready;
            }
        }

    }
}
