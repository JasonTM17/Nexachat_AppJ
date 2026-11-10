package com.nexachat.feature.media;

import io.reactivex.rxjava3.disposables.Disposable;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

public final class MediaViewerSubscriptionsTest {

    @Test
    public void clearingImageLoadsDoesNotDisposeExports() {
        MediaViewerSubscriptions subscriptions = new MediaViewerSubscriptions();
        Disposable export = mock(Disposable.class);
        Disposable imageLoad = mock(Disposable.class);
        subscriptions.addExport(export);
        subscriptions.addImageLoad(imageLoad);
        subscriptions.clearImageLoads();
        verify(imageLoad).dispose();
        verify(export, never()).dispose();
    }

    @Test
    public void destroyViewClearDoesNotDisposeExports() {
        MediaViewerSubscriptions subscriptions = new MediaViewerSubscriptions();
        Disposable export = mock(Disposable.class);
        Disposable imageLoad = mock(Disposable.class);
        subscriptions.addExport(export);
        subscriptions.addImageLoad(imageLoad);
        subscriptions.clearOnDestroyView();
        verify(imageLoad).dispose();
        verify(export, never()).dispose();
    }
}
