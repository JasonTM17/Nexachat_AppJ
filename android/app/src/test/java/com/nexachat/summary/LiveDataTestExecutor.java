package com.nexachat.summary;

import androidx.arch.core.executor.ArchTaskExecutor;
import androidx.arch.core.executor.TaskExecutor;
import android.annotation.SuppressLint;

@SuppressLint("RestrictedApi")
final class LiveDataTestExecutor implements AutoCloseable {
    LiveDataTestExecutor() {
        ArchTaskExecutor.getInstance().setDelegate(new TaskExecutor() {
            @Override
            public void executeOnDiskIO(Runnable runnable) {
                runnable.run();
            }

            @Override
            public void postToMainThread(Runnable runnable) {
                runnable.run();
            }

            @Override
            public boolean isMainThread() {
                return true;
            }
        });
    }

    @Override
    public void close() {
        ArchTaskExecutor.getInstance().setDelegate(null);
    }
}
