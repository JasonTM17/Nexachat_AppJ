package com.nexachat.feature.chat.voice;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import java.io.File;
import java.io.IOException;

/**
 * Handles recording audio messages via {@link MediaRecorder}.
 * Audio is recorded in AAC LC format inside an MPEG-4 container (.m4a),
 * compatible with Android Media3 ExoPlayer and standard web/mobile clients.
 */
public class VoiceRecorder {

    public interface RecorderFactory {
        @NonNull
        MediaRecorder create(@NonNull Context context);
    }

    private static final int SAMPLE_RATE_HZ = 44100;
    private static final int BIT_RATE_BPS = 64000;
    private static final String ATTACHMENTS_DIR = "attachments";
    private static final String FILE_PREFIX = "voice_";
    private static final String FILE_EXT = ".m4a";

    private final Context context;
    private final RecorderFactory recorderFactory;

    @Nullable
    private MediaRecorder mediaRecorder;

    @Nullable
    private File outputFile;

    private boolean recording;
    private long startTimeMs;
    private long lastDurationMs;

    public VoiceRecorder(@NonNull Context context) {
        this(context, defaultRecorderFactory());
    }

    @VisibleForTesting
    public VoiceRecorder(@NonNull Context context, @NonNull RecorderFactory recorderFactory) {
        this.context = context.getApplicationContext();
        this.recorderFactory = recorderFactory;
    }

    private static RecorderFactory defaultRecorderFactory() {
        return ctx -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return new MediaRecorder(ctx);
            } else {
                //noinspection deprecation
                return new MediaRecorder();
            }
        };
    }

    /**
     * Starts recording audio to a new file in the app's cache directory.
     *
     * @return true if recording successfully started, false otherwise.
     */
    public synchronized boolean start() {
        if (recording) {
            return false;
        }

        File cacheDir = new File(context.getCacheDir(), ATTACHMENTS_DIR);
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            return false;
        }

        outputFile = new File(cacheDir, FILE_PREFIX + System.currentTimeMillis() + FILE_EXT);

        try {
            MediaRecorder recorder = recorderFactory.create(context);
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(BIT_RATE_BPS);
            recorder.setAudioSamplingRate(SAMPLE_RATE_HZ);
            recorder.setOutputFile(outputFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();

            mediaRecorder = recorder;
            recording = true;
            startTimeMs = System.currentTimeMillis();
            lastDurationMs = 0;
            return true;
        } catch (Throwable e) {
            cleanupFile();
            releaseRecorder();
            return false;
        }
    }

    /**
     * Stops the active recording and returns the recorded audio file.
     *
     * @return the recorded audio file, or null if recording failed or was not active.
     */
    @Nullable
    public synchronized File stop() {
        if (!recording) {
            return null;
        }

        lastDurationMs = System.currentTimeMillis() - startTimeMs;
        recording = false;
        File result = outputFile;

        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
            }
        } catch (RuntimeException e) {
            // Can happen if stop() is called immediately after start() before audio frames arrived
            cleanupFile();
            result = null;
        } finally {
            releaseRecorder();
        }

        return result;
    }

    /**
     * Cancels and deletes the in-progress recording.
     */
    public synchronized void cancel() {
        if (!recording && mediaRecorder == null && outputFile == null) {
            return;
        }

        recording = false;
        lastDurationMs = 0;

        try {
            if (mediaRecorder != null) {
                mediaRecorder.stop();
            }
        } catch (RuntimeException ignored) {
            // Ignore failure on cancel
        } finally {
            cleanupFile();
            releaseRecorder();
        }
    }

    public synchronized boolean isRecording() {
        return recording;
    }

    public synchronized long getDurationMs() {
        if (recording) {
            return System.currentTimeMillis() - startTimeMs;
        }
        return lastDurationMs;
    }

    @Nullable
    public synchronized File getOutputFile() {
        return outputFile;
    }

    private void cleanupFile() {
        if (outputFile != null && outputFile.exists()) {
            //noinspection ResultOfMethodCallIgnored
            outputFile.delete();
        }
        outputFile = null;
    }

    private void releaseRecorder() {
        if (mediaRecorder != null) {
            try {
                mediaRecorder.reset();
                mediaRecorder.release();
            } catch (Throwable ignored) {
            }
            mediaRecorder = null;
        }
    }
}
