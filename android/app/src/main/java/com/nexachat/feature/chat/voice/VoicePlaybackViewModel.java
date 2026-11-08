package com.nexachat.feature.chat.voice;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModel;
import com.nexachat.core.common.Result;
import com.nexachat.core.network.dto.AttachmentDto;
import com.nexachat.data.AttachmentRepository;
import dagger.hilt.EntryPoints;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** Stores one player controller in the chat Fragment's ViewModelStore. */
public final class VoicePlaybackViewModel extends ViewModel {

    private VoicePlaybackController controller;
    private Lifecycle observedLifecycle;
    private final LifecycleEventObserver lifecycleObserver = this::onLifecycleEvent;

    @NonNull
    public synchronized VoicePlaybackController controller(@NonNull Context context) {
        if (controller == null) {
            Context appContext = context.getApplicationContext();
            AttachmentRepository repository = EntryPoints.get(
                    appContext, VoicePlaybackDependencies.class).attachmentRepository();
            controller = new VoicePlaybackController(
                    new RepositoryAttachmentSource(repository),
                    file -> {
                        try {
                            return WaveformExtractor.extract(MediaCodecPcmDecoder.open(file));
                        } catch (Exception exception) {
                            return WaveformExtractor.flatBars();
                        }
                    },
                    () -> new Media3VoicePlayer(appContext),
                    new AndroidVoiceAudioFocus(appContext),
                    new AndroidVoiceScheduler());
        }
        return controller;
    }

    public synchronized void observeViewLifecycle(@NonNull Lifecycle lifecycle) {
        if (observedLifecycle == lifecycle) {
            return;
        }
        if (observedLifecycle != null) {
            observedLifecycle.removeObserver(lifecycleObserver);
        }
        observedLifecycle = lifecycle;
        lifecycle.addObserver(lifecycleObserver);
    }

    private synchronized void onLifecycleEvent(
            @NonNull LifecycleOwner source,
            @NonNull Lifecycle.Event event) {
        if (controller == null) {
            return;
        }
        if (event == Lifecycle.Event.ON_STOP) {
            controller.stop();
        } else if (event == Lifecycle.Event.ON_DESTROY) {
            controller.releaseForViewDestroy();
            if (observedLifecycle != null) {
                observedLifecycle.removeObserver(lifecycleObserver);
                observedLifecycle = null;
            }
        }
    }

    @Override
    protected synchronized void onCleared() {
        if (observedLifecycle != null) {
            observedLifecycle.removeObserver(lifecycleObserver);
            observedLifecycle = null;
        }
        if (controller != null) {
            controller.close();
            controller = null;
        }
    }

    private static final class RepositoryAttachmentSource
            implements VoicePlaybackController.AttachmentSource {
        private final AttachmentRepository repository;
        private final Map<String, Disposable> downloads = new HashMap<>();

        RepositoryAttachmentSource(AttachmentRepository repository) {
            this.repository = repository;
        }

        @Override
        public synchronized void download(
                @NonNull AttachmentDto attachment,
                @NonNull VoicePlaybackController.DownloadCallback callback) {
            String id = attachment.getId();
            if (id == null || id.trim().isEmpty()) {
                callback.onFailure(new IllegalArgumentException("Attachment id is missing"));
                return;
            }
            Disposable previous = downloads.remove(id);
            if (previous != null) {
                previous.dispose();
            }
            Disposable download = repository.downloadToCache(id, attachment.getOriginalName())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(result -> {
                        remove(id);
                        if (result instanceof Result.Success<?> success
                                && success.getValue() instanceof File file) {
                            callback.onLoaded(file);
                        } else {
                            callback.onFailure(new IllegalStateException("Attachment download failed"));
                        }
                    }, failure -> {
                        remove(id);
                        callback.onFailure(failure);
                    });
            downloads.put(id, download);
        }

        @Override
        public synchronized void cancel(@NonNull String attachmentId) {
            Disposable pending = downloads.remove(attachmentId);
            if (pending != null) {
                pending.dispose();
            }
        }

        private synchronized void remove(String id) {
            downloads.remove(id);
        }
    }
}
