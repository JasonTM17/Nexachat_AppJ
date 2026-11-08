package com.nexachat.feature.chat.voice;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Requests transient media focus and pauses instead of ducking on any focus loss. */
final class AndroidVoiceAudioFocus implements VoicePlaybackController.AudioFocus {

    private final AudioManager audioManager;
    @Nullable private AudioFocusRequest request;

    AndroidVoiceAudioFocus(@NonNull Context context) {
        audioManager = context.getApplicationContext().getSystemService(AudioManager.class);
    }

    @Override
    public boolean request(@NonNull Runnable onLoss) {
        if (audioManager == null) {
            return false;
        }
        abandon();
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(change -> {
                    if (change == AudioManager.AUDIOFOCUS_LOSS
                            || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                            || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                        onLoss.run();
                    }
                }, new Handler(Looper.getMainLooper()))
                .build();
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    @Override
    public void abandon() {
        if (audioManager != null && request != null) {
            audioManager.abandonAudioFocusRequest(request);
            request = null;
        }
    }
}
