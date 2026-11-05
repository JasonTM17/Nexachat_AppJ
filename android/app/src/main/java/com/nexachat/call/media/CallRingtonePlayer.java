package com.nexachat.call.media;

import android.content.Context;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/** Plays the user's default ringtone and a repeating vibration while an incoming call rings. */
public final class CallRingtonePlayer {
    private static final long[] VIBRATION_PATTERN = {0L, 700L, 500L, 700L, 1800L};
    private final Context appContext;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable replayRingtone = this::playRingtone;
    private Ringtone ringtone;
    private Vibrator vibrator;
    private boolean ringing;

    public CallRingtonePlayer(Context context) {
        appContext = context.getApplicationContext();
    }

    public synchronized void start() {
        if (ringing) return;
        ringing = true;
        AudioManager audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null || audioManager.getRingerMode() == AudioManager.RINGER_MODE_SILENT) return;

        Uri ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
        ringtone = ringtoneUri == null ? null : RingtoneManager.getRingtone(appContext, ringtoneUri);
        if (ringtone != null && audioManager.getRingerMode() == AudioManager.RINGER_MODE_NORMAL) {
            ringtone.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ringtone.setLooping(true);
            playRingtone();
        }

        if (audioManager.getRingerMode() != AudioManager.RINGER_MODE_SILENT) {
            vibrator = getVibrator();
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, 0));
                } else {
                    //noinspection deprecation
                    vibrator.vibrate(VIBRATION_PATTERN, 0);
                }
            }
        }
    }

    public synchronized void stop() {
        ringing = false;
        handler.removeCallbacks(replayRingtone);
        if (ringtone != null) {
            ringtone.stop();
            ringtone = null;
        }
        if (vibrator != null) {
            vibrator.cancel();
            vibrator = null;
        }
    }

    private synchronized void playRingtone() {
        if (!ringing || ringtone == null) return;
        try {
            if (!ringtone.isPlaying()) ringtone.play();
        } catch (RuntimeException ignored) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            handler.postDelayed(replayRingtone, 6000L);
        }
    }

    private Vibrator getVibrator() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = (VibratorManager) appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            return manager == null ? null : manager.getDefaultVibrator();
        }
        //noinspection deprecation
        return (Vibrator) appContext.getSystemService(Context.VIBRATOR_SERVICE);
    }
}
