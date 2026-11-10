package com.nexachat.feature.media;

import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.disposables.Disposable;

/** Separates in-flight image loads from SAF export work in the media viewer. */
final class MediaViewerSubscriptions {

    private final CompositeDisposable imageLoads = new CompositeDisposable();
    private final CompositeDisposable exports = new CompositeDisposable();

    void addImageLoad(Disposable disposable) {
        imageLoads.add(disposable);
    }

    void addExport(Disposable disposable) {
        exports.add(disposable);
    }

    void clearImageLoads() {
        imageLoads.clear();
    }

    void clearOnDestroyView() {
        imageLoads.clear();
    }

    void clearOnDestroy() {
        exports.clear();
        imageLoads.clear();
    }
}
