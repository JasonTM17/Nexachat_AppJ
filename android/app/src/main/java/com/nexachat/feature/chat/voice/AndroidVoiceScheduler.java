package com.nexachat.feature.chat.voice;

import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Main-thread callbacks and a single bounded decode worker for one chat screen. */
final class AndroidVoiceScheduler implements VoicePlaybackController.Scheduler {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService decodeExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "voice-waveform");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public void postMain(@NonNull Runnable task) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            mainHandler.post(task);
        }
    }

    @Override
    public void executeBackground(@NonNull Runnable task) {
        decodeExecutor.execute(task);
    }

    @Override
    public void postDelayed(@NonNull Runnable task, long delayMs) {
        mainHandler.postDelayed(task, delayMs);
    }

    @Override
    public void removeCallbacks(@NonNull Runnable task) {
        mainHandler.removeCallbacks(task);
    }

    @Override
    public void close() {
        mainHandler.removeCallbacksAndMessages(null);
        decodeExecutor.shutdownNow();
    }
}
