package com.nexachat.data;

import android.content.Context;
import androidx.annotation.NonNull;
import dagger.hilt.android.qualifiers.ApplicationContext;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public final class OfflineOutboxScheduler {
    private final Context appContext;

    @Inject
    public OfflineOutboxScheduler(@ApplicationContext @NonNull Context appContext) {
        this.appContext = appContext.getApplicationContext();
    }

    public void schedule() {
        OutboxSyncWorker.enqueue(appContext);
    }
}
